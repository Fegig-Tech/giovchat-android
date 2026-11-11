package app.giovchat.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import app.giovchat.core.GiovChatPhase
import app.giovchat.core.GiovDelivery
import app.giovchat.core.GiovPickedFile
import app.giovchat.core.GiovThemeMode
import app.giovchat.core.giovSessionScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun SemanticsNodeInteraction.surface(): GiovSurface = fetchSemanticsNode().config[GiovSurfaceKey]

private fun SemanticsNodeInteraction.fill(): Int = fetchSemanticsNode().config[GiovFillKey]

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class ChatUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loadingShowsASkeletonThenTheGreetingTheBotHeaderAndTheBranding() {
        val rig = UiRig(rule)
        rule.setContent { rig.Chat() }
        rule.waitForIdle()
        rule.onNodeWithTag("giov-loading").assertExists()
        rule.onNodeWithText("Loading…").assertExists()

        rig.settle()
        assertEquals(GiovChatPhase.READY, rig.phase)
        rule.onNodeWithTag("giov-loading").assertDoesNotExist()
        rule.onNodeWithText("Hi").assertExists()
        rule.onNodeWithText("Atlas").assertExists()
        rule.onNodeWithText("AI assistant · Online").assertExists()
        rule.onNodeWithTag("giov-branding").assertExists()
        assertTrue("opening the chat starts nothing", rig.sockets.all.isEmpty())
        rig.finish()
    }

    @Test
    fun thePreChatFormAsksForTheRequiredFieldsAndStartsTheChatOnceTheyAreValid() {
        val rig = UiRig(rule, config = mapOf("requestUserInfo" to mapOf("email" to true, "firstName" to true)))
        rig.show()
        assertEquals(GiovChatPhase.PRE_CHAT, rig.phase)
        rule.onNodeWithText("Before we start…").assertExists()
        rule.onNodeWithTag("giov-field-firstName").assertExists()
        rule.onNodeWithTag("giov-field-email").assertExists()
        rule.onNodeWithTag("giov-field-phone").assertDoesNotExist()
        rule.onNodeWithTag("giov-start-chat").assertIsNotEnabled()

        rule.onNodeWithTag("giov-field-firstName").performTextInput("Ada")
        rule.onNodeWithTag("giov-field-email").performTextInput("ada")
        rig.settle()
        rule.onNodeWithTag("giov-start-chat").assertIsNotEnabled()
        assertEquals("a malformed email is refused", 0, rig.server.count("created"))

        rule.onNodeWithTag("giov-field-email").performTextInput("@example.com")
        rig.settle()
        rule.onNodeWithTag("giov-start-chat").assertIsEnabled().performScrollTo().performClick()
        rig.settle()
        assertEquals(1, rig.server.count("created"))
        assertEquals("ada@example.com", rig.server.lastCreate().json["email"].text())
        assertEquals("Ada", rig.server.lastCreate().json["firstName"].text())
        assertEquals(GiovChatPhase.CONNECTING, rig.phase)
        rule.onNodeWithTag("giov-pre-chat").assertDoesNotExist()
        rig.finish()
    }

    @Test
    fun aSentMessageShowsAtOnceAsSendingThenConfirmed() {
        val rig = UiRig(rule)
        rig.show()
        rule.onNodeWithTag("giov-input").performTextInput("  Hello there ")
        rule.onNodeWithTag("giov-send").performClick()
        rig.settle()
        rule.onNodeWithText("Sending…").assertExists()
        rule.onNodeWithText("Hello there").assertExists()
        rule.onNodeWithText("You").assertExists()
        rule.onNodeWithTag("giov-send").assertIsNotEnabled()

        rig.socket.accept()
        rig.settle()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        val frame = rig.lastFrame()
        assertEquals("Hello there", frame["content"].text())
        rig.ack(frame)
        rule.onAllNodesWithText("Sending…").assertCountEquals(0)
        assertEquals(GiovDelivery.SENT, rig.ui.messages.single().delivery)
        assertEquals(1, rule.onAllNodes(hasTestTagPrefix("bubble:")).fetchSemanticsNodes().size)
        rig.finish()
    }

    @Test
    fun aMessageThatFailedOffersRetryAndDiscard() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        val id = rig.lastFrame()["clientMessageId"].text()!!
        fun failure() = rig.receive("type" to "error", "error" to "message_failed", "clientMessageId" to id)
        failure()
        rig.elapse(2_001)
        failure()
        rig.elapse(4_001)
        failure()
        assertEquals(GiovDelivery.FAILED, rig.ui.messages.single().delivery)
        rule.onNodeWithText("Not sent · Tap to retry").assertExists()

        val before = rig.socket.messageFrames().size
        rule.onNodeWithTag("giov-retry").performClick()
        rig.settle()
        assertEquals(before + 1, rig.socket.messageFrames().size)
        assertEquals(id, rig.lastFrame()["clientMessageId"].text())
        rule.onAllNodesWithText("Not sent · Tap to retry").assertCountEquals(0)

        rig.receive("type" to "error", "error" to "invalid_message")
        rule.onNodeWithText("Not sent · Tap to retry").assertExists()
        rule.onNodeWithTag("giov-discard").performClick()
        rig.settle()
        assertTrue(rig.ui.messages.isEmpty())
        rule.onAllNodes(hasTestTagPrefix("bubble:")).assertCountEquals(0)
        rig.finish()
    }

    @Test
    fun aStreamingReplyIsShownInPlaceAndReplacedBySavedReplyWithoutBeingRebuilt() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        rig.receive("type" to "typing", "role" to "agent", "isBot" to true)
        rule.onNodeWithTag("giov-typing").assertExists()
        rule.onNodeWithContentDescription("Atlas is typing").assertExists()

        rig.receive("type" to "stream", "streamId" to "r1", "content" to "Our **hours**")
        rule.onNodeWithTag("giov-typing").assertDoesNotExist()
        rule.onNodeWithTag("bubble:stream:r1").assertExists()
        rule.onNodeWithText("Our hours").assertExists()
        val node = rule.onNodeWithTag("bubble:stream:r1").fetchSemanticsNode().id

        rig.receive("type" to "stream", "streamId" to "r1", "content" to "Our **hours** are 9–5.")
        rule.onNodeWithText("Our hours are 9–5.").assertExists()

        rig.receive(
            "id" to "m9", "role" to "agent", "content" to "Our **hours** are 9–5.", "createdAt" to rig.now() + 10,
            "isBot" to true, "isAutomated" to true, "streamId" to "r1",
        )
        rule.onNodeWithTag("bubble:stream:r1").assertExists()
        assertEquals("the same item carries the draft and the saved reply", node, rule.onNodeWithTag("bubble:stream:r1").fetchSemanticsNode().id)
        rule.onNodeWithText("Our hours are 9–5.").assertExists()
        assertEquals(2, rule.onAllNodes(hasTestTagPrefix("bubble:")).fetchSemanticsNodes().size)
        rig.finish()
    }

    @Test
    fun onlyHttpAndMailtoLinksOpen() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        var at = 10L
        fun reply(id: String, content: String) =
            rig.receive("id" to id, "role" to "agent", "content" to content, "createdAt" to rig.now() + at++)
        reply("m1", "[the site](https://example.com/a)")
        rule.onNodeWithText("the site").performClick()
        reply("m2", "[mail us](mailto:hi@example.com)")
        rule.onNodeWithText("mail us").performClick()
        assertEquals(listOf("https://example.com/a", "mailto:hi@example.com"), rig.opened)
        for ((id, text) in listOf("m3" to "[bad](javascript:alert(1))", "m4" to "[file](file:///etc/passwd)", "m5" to "[ftp](ftp://x.test/y)")) {
            reply(id, text)
            rule.onNodeWithText(text).performClick()
        }
        assertEquals("text that is not a link opens nothing", 2, rig.opened.size)
        rig.finish()
    }

    @Test
    fun theHeaderSwitchesFromTheBotToTheTeammateWhoReplies() {
        val rig = UiRig(rule)
        rig.show()
        rule.onNodeWithText("Atlas").assertExists()
        rule.onNodeWithText("AI assistant · Online").assertExists()
        rig.begin()
        rule.onNodeWithText("Atlas").assertExists()

        rig.receive("id" to "a1", "role" to "agent", "content" to "Hi, Ada here.", "createdAt" to rig.now() + 10, "senderName" to "Ada")
        rule.onAllNodesWithText("Ada").assertCountEquals(2) // the header and the sender label
        rule.onAllNodesWithText("Atlas").assertCountEquals(0)
        rule.onNodeWithText("Live agent").assertExists()
        rule.onAllNodesWithText("AI assistant · Online").assertCountEquals(0)
        rig.finish()
    }

    @Test
    fun aTeamWithoutABotShowsTheTeamNameAndStatus() {
        val rig = UiRig(rule, config = mapOf("botEnabled" to false))
        rig.show()
        rule.onNodeWithText("Acme").assertExists()
        rule.onNodeWithText("We'll be with you shortly").assertExists()
        rule.onAllNodesWithTag("giovbot-mark", useUnmergedTree = true).assertCountEquals(0)
        rig.finish()
    }

    @Test
    fun theBotWithoutAPhotoShowsTheGiovBotMark() {
        val rig = UiRig(rule)
        rig.show()
        rule.onNodeWithTag("giovbot-mark", useUnmergedTree = true).assertExists()
        rig.finish()
    }

    @Test
    fun brandingShowsUnlessTheServerTurnsItOff() {
        val rig = UiRig(rule, config = mapOf("showBranding" to false))
        rig.show()
        rule.onNodeWithTag("giov-branding").assertDoesNotExist()
        rig.finish()
    }

    @Test
    fun tappingTheBrandingOpensGiovChat() {
        val rig = UiRig(rule)
        rig.show()
        rule.onNodeWithTag("giov-branding").performClick()
        assertEquals(listOf("https://giovchat.app/?utm_source=android_widget&utm_medium=powered_by"), rig.opened)
        rule.onNodeWithContentDescription("Powered by GiovChat").assertExists()
        rig.finish()
    }

    @Test
    fun theDashboardThemeAndTheThemeParameterPickLightOrDark() {
        val dashboardDark = UiRig(rule, config = mapOf("theme" to "dark"))
        dashboardDark.show()
        assertTrue(rule.onNodeWithTag("giov-root").surface().dark)
        assertEquals(Color(0xFF0A0A0A).toArgb(), rule.onNodeWithTag("giov-root").surface().background)
        dashboardDark.finish()
    }

    @Test
    fun theThemeParameterWinsOverTheDashboard() {
        val rig = UiRig(rule, config = mapOf("theme" to "dark"))
        rig.show(theme = GiovThemeMode.LIGHT)
        assertFalse(rule.onNodeWithTag("giov-root").surface().dark)
        assertEquals(Color.White.toArgb(), rule.onNodeWithTag("giov-root").surface().background)
        rig.finish()
    }

    @Test
    @Config(qualifiers = "+night")
    fun autoFollowsTheDevice() {
        val rig = UiRig(rule, config = mapOf("theme" to "auto"))
        rig.show()
        assertTrue(rule.onNodeWithTag("giov-root").surface().dark)
        rig.finish()
    }

    @Test
    fun theBrandColourPaintsTheHeaderAndTheVisitorBubble() {
        val rig = UiRig(rule)
        rig.show()
        assertEquals(Color(0xFF123456).toArgb(), rule.onNodeWithTag("giov-root").surface().header)
        rig.begin()
        val bubble = rule.onAllNodes(hasTestTagPrefix("bubble:")).onFirst()
        assertEquals(Color(0xFF123456).toArgb(), bubble.fill())
        rig.finish()
    }

    @Test
    fun anErrorShowsASentenceAndTryAgainStartsOver() {
        val rig = UiRig(rule)
        rig.server.configStatus = 500
        rig.show()
        assertEquals(GiovChatPhase.ERROR, rig.phase)
        rule.onNodeWithText("Chat is unavailable. Please try again.").assertExists()
        val before = rig.server.count("config")
        rig.server.configStatus = 200
        rule.onNodeWithTag("giov-try-again").performClick()
        rig.settle()
        assertTrue(rig.server.count("config") > before)
        assertEquals(GiovChatPhase.READY, rig.phase)
        rule.onNodeWithTag("giov-try-again").assertDoesNotExist()
        rig.finish()
    }

    @Test
    fun attachmentsThePickerRunsThroughThePolicyAndChipsShowUntilSent() {
        val rig = UiRig(rule)
        var asked: GiovPickerRequest? = null
        val picker: GiovAttachmentPicker = { request ->
            asked = request
            listOf(
                GiovPickedFile.bytes("photo.png", "image/png", byteArrayOf(1, 2, 3)),
                GiovPickedFile.bytes("notes.txt", "text/plain", byteArrayOf(1)),
            )
        }
        rig.show(picker = picker)
        rule.onNodeWithTag("giov-attach").assertDoesNotExist() // uploads need a conversation
        rig.begin()
        rule.onNodeWithTag("giov-attach").assertExists()

        rule.onNodeWithTag("giov-attach").performClick()
        rig.settle()
        assertEquals(5, asked!!.maxFiles)
        assertTrue("image/png" in asked!!.allowedTypes)
        rule.onNodeWithText("photo.png").assertExists()
        rule.onNodeWithText("notes.txt: this file type is not allowed.").assertExists()

        rule.onNodeWithTag("giov-remove-0").performClick()
        rig.settle()
        rule.onAllNodesWithText("photo.png").assertCountEquals(0)
        rig.finish()
    }

    @Test
    fun attachmentsRenderAsAnImageBoxAndAsFileRowsWithAuthenticatedLinks() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        val base = "https://api.example.test/conversations/conv-1/attachments"
        rig.receive(
            "id" to "m1", "role" to "agent", "content" to "Files", "createdAt" to rig.now() + 10,
            "attachmentId" to JsonArray(listOf(JsonPrimitive("$base/pic.png"), JsonPrimitive("$base/report.pdf"))).toString(),
            "attachmentPreviews" to listOf(mapOf("width" to 400, "height" to 300), null),
        )
        rule.onNodeWithTag("giov-image").assertWidthIsAtLeast(200.dp).assertHeightIsAtLeast(150.dp)
        assertTrue("the picture loads with the credential", FakeImages.requested.any { it.startsWith("$base/pic.png?") })
        rule.onNodeWithText("report.pdf").assertExists()
        rule.onNodeWithTag("giov-file").performClick()
        assertEquals(1, rig.opened.size)
        assertTrue(rig.opened.single().startsWith("$base/report.pdf?"))
        assertTrue(rig.opened.single().substringAfter('?').let { it.startsWith("at=") || it.startsWith("token=") })
        rig.finish()
    }

    @Test
    fun tapTargetsAreAtLeast48DpAndLargeTextDoesNotBreakTheLayout() {
        val rig = UiRig(rule)
        var closed = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.5f)) {
                rig.Chat(onClose = { closed = true }, picker = { emptyList() })
            }
        }
        rig.settle()
        rig.begin()
        for (tag in listOf("giov-send", "giov-attach", "giov-close", "giov-branding")) {
            rule.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithContentDescription("Close chat").performClick()
        assertTrue(closed)
        rig.finish()
    }

    @Test
    fun theAppLifecycleReachesTheControllerAndLeavingTheScreenHidesTheChat() {
        val rig = UiRig(rule, keepConnected = true)
        var show by mutableStateOf(true)
        rule.setContent { if (show) rig.Chat() }
        rig.settle()
        rig.begin()
        val first = rig.socket
        rig.lifecycle.state = Lifecycle.State.CREATED // the app went to the background
        rig.elapse(6_000)
        assertTrue("the socket closes after the background grace", first.closedByClient)
        rig.lifecycle.state = Lifecycle.State.STARTED
        rig.settle()
        assertEquals(2, rig.sockets.all.size)

        assertTrue(rig.ui.visible)
        show = false
        rig.settle()
        assertFalse("a keepConnected chat keeps counting unread replies", rig.ui.visible)
        rig.finish()
    }

    @Test
    fun theComposableCanOwnItsController() {
        val server = FakeServer { System.currentTimeMillis() }
        val sockets = Sockets()
        rule.setContent { GiovChat(tenantKey = "t", baseUrl = BASE, locale = "fr", http = server, socketFactory = sockets, imageLoader = FakeImages) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Assistant IA · En ligne").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Écrivez un message…").assertExists()
    }

    @Test
    fun aConfigurationChangeKeepsTheConversationWithTheDefaultStore() {
        // Rotation, a dark-mode switch or a resize recreate the activity: everything remembered is lost, so the
        // default (in-memory) store must outlive the composition or the visitor's conversation vanishes.
        val server = FakeServer { System.currentTimeMillis() }
        val sockets = Sockets()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            GiovChat(tenantKey = "t-config-change", baseUrl = BASE, locale = "en", http = server, socketFactory = sockets, imageLoader = FakeImages)
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("giov-input").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("giov-input").performTextInput("Hello")
        rule.onNodeWithTag("giov-send").performClick()
        rule.waitUntil(5_000) { sockets.all.isNotEmpty() }
        sockets.all.last().accept()
        rule.waitUntil(5_000) { server.count("history") == 1 }

        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(5_000) { server.count("resumed") == 1 }
        assertEquals("the stored conversation is resumed, not replaced", 1, server.count("created"))
    }

    @Test
    fun styleOverridesReachTheSurfaces() {
        val rig = UiRig(rule)
        rig.show(
            style = GiovChatStyleOverrides(
                header = Color(0xFF0F172A),
                container = Color(0xFFF8FAFC),
                headerTitle = GiovTextOverride(color = Color.White, fontWeight = FontWeight.ExtraBold),
            ),
        )
        val surface = rule.onNodeWithTag("giov-root").surface()
        assertEquals(Color(0xFF0F172A).toArgb(), surface.header)
        assertEquals(Color(0xFFF8FAFC).toArgb(), surface.background)
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("giov-header-title").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        assertEquals(FontWeight.ExtraBold, results.single().layoutInput.style.fontWeight)
        rig.finish()
    }

    @Test
    fun stringOverridesReplaceInterfaceText() {
        val rig = UiRig(rule)
        rule.setContent { rig.Chat(strings = { it.withOverrides(sendMessage = "Mandar") }) }
        rig.settle()
        rule.onNodeWithContentDescription("Mandar").assertExists()
        rig.finish()
    }

    @Test
    fun theListFollowsNewMessagesAndKeepsItsPlaceWhileReadingOlderOnes() {
        val store = app.giovchat.core.InMemoryGiovSessionStore()
        kotlinx.coroutines.runBlocking { store.write(giovSessionScope(BASE, "t", null, null), testSession) }
        val rig = UiRig(rule, sessionStore = store)
        rig.server.history = (1..40).map {
            mapOf("id" to "h$it", "role" to if (it % 2 == 0) "agent" else "user", "content" to "Message number $it", "createdAt" to EPOCH + it)
        }
        rig.show()
        assertEquals(GiovChatPhase.CONNECTING, rig.phase)
        rig.socket.accept()
        rig.settle()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        rule.onNodeWithText("Message number 40").assertIsDisplayed()

        rig.receive("id" to "n1", "role" to "agent", "content" to "A fresh reply", "createdAt" to rig.now() + 100)
        rule.onNodeWithText("A fresh reply").assertIsDisplayed()

        repeat(12) { rule.onNodeWithTag("giov-messages").performTouchInput { swipeDown() } }
        rig.settle()
        rule.onAllNodesWithText("A fresh reply").assertCountEquals(0)
        rig.receive("id" to "n2", "role" to "agent", "content" to "Another reply", "createdAt" to rig.now() + 200)
        rule.onAllNodesWithText("Another reply").assertCountEquals(0)
        assertNotEquals("the reader stayed up in the history", 0, rule.onAllNodes(hasTestTagPrefix("bubble:")).fetchSemanticsNodes().size)
        rig.finish()
    }
}

private fun hasTestTagPrefix(prefix: String) =
    androidx.compose.ui.test.SemanticsMatcher("TestTag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

