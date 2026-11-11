package app.giovchat.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.giovchat.core.GiovChatPhase
import app.giovchat.core.GiovPickedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Regressions found in the pre-publish review (and ported from the Flutter and iOS fixes).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class ReviewFixesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun inputText(): String =
        rule.onNodeWithTag("giov-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun aMessageIsCappedAt2000Utf16UnitsWithoutSplittingAnEmoji() {
        assertEquals(2000, limitMessage("a".repeat(2100)).length)
        val emoji = "a".repeat(1999) + "😀" // the pair would end at 2001
        assertEquals("a".repeat(1999), limitMessage(emoji))
        assertEquals("short", limitMessage("short"))

        val rig = UiRig(rule)
        rig.show()
        rule.onNodeWithTag("giov-input").performTextInput("x".repeat(2100))
        rig.settle()
        assertEquals("a paste over the limit keeps what fits", 2000, inputText().length)
        rig.finish()
    }

    @Test
    fun theTextFieldStaysEditableWhileFilesUploadOnlySendWaits() {
        val rig = UiRig(rule)
        rig.server.uploadDelayMs = 1_000
        val picker: GiovAttachmentPicker = { listOf(GiovPickedFile.bytes("photo.png", "image/png", byteArrayOf(1, 2, 3))) }
        rig.show(picker = picker)
        rig.begin()
        rule.onNodeWithTag("giov-attach").performClick()
        rig.settle()
        rule.onNodeWithTag("giov-send").performClick()
        rig.settle()
        assertTrue(rig.ui.sending)
        rule.onNodeWithTag("giov-input").assertIsEnabled()
        rule.onNodeWithTag("giov-input").performTextInput("next one")
        rig.settle()
        assertEquals("next one", inputText())
        rule.onNodeWithTag("giov-send").assertIsNotEnabled()
        rig.elapse(1_000)
        assertFalse(rig.ui.sending)
        assertEquals("what was typed meanwhile is kept", "next one", inputText())
        rig.finish()
    }

    @Test
    fun aDroppedSocketShowsReconnectingUntilItIsBack() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        rule.onNodeWithTag("giov-reconnecting").assertDoesNotExist()
        rig.socket.drop()
        rig.settle()
        rule.onNodeWithTag("giov-reconnecting").assertExists()
        rule.onNodeWithText("Connection lost. Reconnecting…").assertExists()
        rig.elapse(1_500)
        rig.socket.accept()
        rig.settle()
        rule.onNodeWithTag("giov-reconnecting").assertDoesNotExist()
        rig.finish()
    }

    @Test
    fun theHeaderLinksToTheWorkspacePrivacyPolicyOrGiovs() {
        val rig = UiRig(rule, config = mapOf("privacyPolicyUrl" to "https://acme.test/privacy"))
        rig.show()
        rule.onNodeWithContentDescription("GDPR and privacy").performClick()
        assertEquals(listOf("https://acme.test/privacy"), rig.opened)
        rig.finish()

        val plain = UiRig(rule)
        assertEquals(GIOV_PRIVACY_URL, "https://giovchat.app/privacy")
        plain.controller.close()
    }

    @Test
    fun textOnTheBrandColourAndOnAgentBubblesIsReadable() {
        val green = Color(0xFF22C55E)
        assertEquals("dark text on a mid green (white was 2.3:1)", Color(0xFF111111), readableOn(green))
        assertTrue(contrastRatio(readableOn(green), green) >= 4.5f)
        assertEquals(Color.White, readableOn(Color(0xFF7C3AED)))
        val light = Color(0xFFF0F0F0)
        val dark = Color(0xFF242424)
        for ((text, bubble) in listOf(AgentLabelLight to light, AgentTimeLight to light, AgentLabelDark to dark, AgentTimeDark to dark)) {
            assertTrue("$text on $bubble", contrastRatio(text, bubble) >= 4.5f)
        }
    }

    @Test
    fun aFailedImageLoadsAgainWhenItsUrlChanges() {
        val loader = object : GiovImageLoader {
            override suspend fun load(url: String, cacheKey: String, maxPixels: Int): ImageBitmap? =
                if (url.contains("at=new")) Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap() else null
        }
        var url by mutableStateOf("https://api.example.test/a.png?token=expired")
        var seen: RemoteImage? = null
        rule.setContent { Probe(loader, url) { seen = it } }
        rule.waitForIdle()
        assertEquals(RemoteImage.Failed, seen)
        url = "https://api.example.test/a.png?at=new"
        rule.waitForIdle()
        assertTrue(seen is RemoteImage.Ready)
    }

    @Composable
    private fun Probe(loader: GiovImageLoader, url: String, report: (RemoteImage) -> Unit) {
        val state by rememberRemoteImage(loader, url, "https://api.example.test/a.png", 64)
        report(state)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTransparentImageGetsAWhitePlaceholderNotABlackOne() {
        val clear = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888) // every pixel transparent
        val thumb = GiovImagePlaceholder.create(clear)!!
        assertTrue(thumb.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(thumb.substringAfter(','), Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(32, decoded.width)
        assertEquals(24, decoded.height)
        val pixel = decoded.getPixel(16, 12)
        assertTrue("near white, was #${Integer.toHexString(pixel)}", android.graphics.Color.red(pixel) > 240 && android.graphics.Color.blue(pixel) > 240)
    }

    @Test
    fun aConfigurationChangeKeepsTheControllerItsSocketAndItsOutbox() {
        val server = FakeServer { System.currentTimeMillis() }
        val sockets = Sockets()
        val chat: ComponentActivity.() -> Unit = {
            setContent { GiovChat(tenantKey = "t-recreate", baseUrl = BASE, locale = "en", http = server, socketFactory = sockets, imageLoader = FakeImages) }
        }
        val scenario = rule.activityRule.scenario
        scenario.onActivity(chat)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("giov-input").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("giov-input").performTextInput("Hello")
        rule.onNodeWithTag("giov-send").performClick()
        rule.waitUntil(5_000) { sockets.all.isNotEmpty() }
        sockets.all.last().accept()
        rule.waitUntil(5_000) { server.count("history") == 1 && sockets.all.last().messageFrames().isNotEmpty() }

        scenario.recreate()
        scenario.onActivity(chat)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("giov-input").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        Thread.sleep(300) // let the controller's own thread run anything the recreation queued
        rule.waitForIdle()
        assertEquals("the same socket", 1, sockets.all.size)
        assertFalse(sockets.all.single().closedByClient)
        assertEquals("nothing resumed or started again", 0, server.count("resumed"))
        assertEquals(1, server.count("created"))
        rule.onNodeWithText("Sending…").assertExists() // the unacknowledged message is still in the outbox
    }
}
