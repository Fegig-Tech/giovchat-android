package app.giovchat.compose

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.giovchat.core.GiovChatController
import app.giovchat.core.GiovChatPhase
import app.giovchat.core.GiovChatStrings
import app.giovchat.core.GiovSessionStore
import app.giovchat.core.GiovThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals

const val BASE = "https://api.example.test"

/** Every picture loads as a 10 x 10 bitmap. */
object FakeImages : GiovImageLoader {
    val requested = mutableListOf<String>()

    override suspend fun load(url: String, cacheKey: String, maxPixels: Int): ImageBitmap {
        requested += url
        return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/** A test lifecycle the rig moves by hand. */
class TestLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    var state: Lifecycle.State
        get() = registry.currentState
        set(value) {
            registry.currentState = value
        }
}

/** The chat screen over the real controller, a fake server and hand-driven sockets, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class UiRig(
    val rule: ComposeContentTestRule,
    config: Map<String, Any?> = emptyMap(),
    keepConnected: Boolean = false,
    sessionStore: GiovSessionStore? = null,
) {
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    val now: () -> Long = { EPOCH + scheduler.currentTime }
    val server = FakeServer(now).also { it.config = it.config + config }
    val sockets = Sockets()
    val opened = mutableListOf<String>()
    val lifecycle = TestLifecycle().also { it.state = Lifecycle.State.STARTED }

    val controller = GiovChatController(
        tenantKey = "t",
        baseUrl = BASE,
        locale = "en",
        keepConnected = keepConnected,
        sessionStore = sessionStore ?: app.giovchat.core.InMemoryGiovSessionStore(),
        http = server,
        socketFactory = sockets,
        dispatcher = dispatcher,
        clock = now,
        random = NoJitter,
    )

    val ui get() = controller.state.value
    val phase: GiovChatPhase get() = ui.phase
    val socket: FakeSocket get() = sockets.last

    @Composable
    fun Chat(
        modifier: Modifier = Modifier,
        theme: GiovThemeMode? = null,
        primaryColor: Color? = null,
        onClose: (() -> Unit)? = null,
        picker: GiovAttachmentPicker? = null,
        style: GiovChatStyleOverrides = GiovChatStyleOverrides(),
        strings: ((GiovChatStrings) -> GiovChatStrings)? = null,
        forwardLifecycle: Boolean = true,
    ) {
        CompositionLocalProvider(LocalGiovReduceMotion provides true) {
            GiovChat(
                controller = controller,
                modifier = modifier,
                theme = theme,
                primaryColor = primaryColor,
                onClose = onClose,
                attachmentPicker = picker,
                styleOverrides = style,
                strings = strings,
                onOpenUrl = { opened += it },
                imageLoader = FakeImages,
                lifecycleOwner = lifecycle,
                forwardLifecycle = forwardLifecycle,
            )
        }
    }

    fun show(
        theme: GiovThemeMode? = null,
        primaryColor: Color? = null,
        onClose: (() -> Unit)? = null,
        picker: GiovAttachmentPicker? = null,
        style: GiovChatStyleOverrides = GiovChatStyleOverrides(),
    ) {
        rule.setContent { Chat(theme = theme, primaryColor = primaryColor, onClose = onClose, picker = picker, style = style) }
        settle()
    }

    /** Runs what the controller has queued and lets the screen react, a few rounds so chained work settles. */
    fun settle(rounds: Int = 4) {
        repeat(rounds) {
            repeat(12) { scheduler.runCurrent() }
            rule.waitForIdle()
        }
    }

    /** Moves virtual time forward. */
    fun elapse(ms: Long) {
        scheduler.advanceTimeBy(ms)
        settle()
    }

    fun receive(vararg pairs: Pair<String, Any?>) {
        socket.receive(*pairs)
        settle()
    }

    /** Types a first message, sends it, opens the socket and catches up: the conversation is active. */
    fun begin(text: String = "Hello") {
        rule.onNodeWithTag("giov-input").performTextInput(text)
        rule.onNodeWithTag("giov-send").performClick()
        settle()
        socket.accept()
        settle()
        assertEquals(GiovChatPhase.ACTIVE, phase)
    }

    fun lastFrame(): JsonObject = socket.messageFrames().last()

    fun ack(frame: JsonObject, id: String = "srv-1") = receive(
        "type" to "message_ack",
        "clientMessageId" to frame["clientMessageId"].text(),
        "message" to mapOf("id" to id, "role" to "visitor", "content" to frame["content"].text(), "createdAt" to now()),
    )

    fun finish() {
        controller.close()
        settle()
    }
}
