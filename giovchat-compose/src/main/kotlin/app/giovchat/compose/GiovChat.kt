package app.giovchat.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.giovchat.core.GIOV_DEFAULT_BASE_URL
import app.giovchat.core.GiovChatController
import app.giovchat.core.GiovChatException
import app.giovchat.core.GiovChatPhase
import app.giovchat.core.GiovChatStrings
import app.giovchat.core.GiovConnectionStatus
import app.giovchat.core.GiovContact
import app.giovchat.core.GiovHeaderKind
import app.giovchat.core.GiovHttp
import app.giovchat.core.GiovPickedFile
import app.giovchat.core.GiovSessionStore
import app.giovchat.core.GiovSocketFactory
import app.giovchat.core.GiovThemeMode
import app.giovchat.core.AttachmentProblem
import app.giovchat.core.InMemoryGiovSessionStore
import app.giovchat.core.MAX_ATTACHED_FILES
import app.giovchat.core.OkHttpGiovHttp
import app.giovchat.core.OkHttpGiovSocketFactory
import app.giovchat.core.resolveGiovStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

/** What the host's picker is asked for. */
public class GiovPickerRequest(
    /** MIME types the chat accepts right now (the bot's policy). */
    public val allowedTypes: List<String>,
    /** How many more files fit in the message. */
    public val maxFiles: Int,
    public val maxSizeMb: Double,
)

/**
 * Opens the app's own file picker (an ActivityResult contract) and returns what the visitor chose; return an empty
 * list when they cancel. The SDK checks the files against the policy, so the picker need not.
 */
public typealias GiovAttachmentPicker = suspend (GiovPickerRequest) -> List<GiovPickedFile>

private const val BRANDING_URL = "https://giovchat.app/?utm_source=android_widget&utm_medium=powered_by"

/**
 * Creates a [GiovChatController] and keeps it for as long as the arguments stay the same: in a ViewModel of the
 * current [LocalViewModelStoreOwner] (the activity, fragment or navigation destination), so a configuration change
 * (rotation, dark mode, resizing) keeps the connection, the outbox and the conversation. It is closed when that owner
 * is cleared, or when the arguments change. Use it when you need the controller yourself (to call
 * `registerPushToken`, `reset` or read the state) and want [GiovChat] to follow it.
 *
 * The callbacks are always the latest ones; the other arguments are read when the controller is created and are
 * compared with `equals`, so pass stable values: `remember { MySessionStore(context) }`, not a new instance on every
 * recomposition (a new instance, or a new `http` / `socketFactory`, creates a new controller).
 */
@Composable
public fun rememberGiovChatController(
    tenantKey: String,
    baseUrl: String = GIOV_DEFAULT_BASE_URL,
    contact: GiovContact? = null,
    sessionKey: String? = null,
    sessionStore: GiovSessionStore? = null,
    keepConnected: Boolean = false,
    visible: Boolean = true,
    locale: String? = null,
    strings: ((GiovChatStrings) -> GiovChatStrings)? = null,
    onError: ((GiovChatException) -> Unit)? = null,
    onConversationStart: ((String) -> Unit)? = null,
    onConnectionStatusChange: ((GiovConnectionStatus) -> Unit)? = null,
    onUnreadCountChange: ((Int) -> Unit)? = null,
    http: GiovHttp? = null,
    socketFactory: GiovSocketFactory? = null,
): GiovChatController {
    val store = sessionStore ?: ProcessSessionStore
    val arguments = listOf(tenantKey, baseUrl, contact, sessionKey, store, keepConnected, locale, http, socketFactory)
    val owner = LocalViewModelStoreOwner.current
    // One holder per call site, so two chats on one screen keep their own controllers.
    val holder: ControllerHolder = if (owner != null) {
        // An explicit factory: no reflection, so nothing to keep in a minified app.
        viewModel(viewModelStoreOwner = owner, key = "giov-chat:" + currentCompositeKeyHash.toString(36), factory = HolderFactory)
    } else {
        remember { ControllerHolder() }.also { fallback ->
            DisposableEffect(fallback) { onDispose { fallback.onCleared() } }
        }
    }
    // The controller can outlive this composition: it reaches the callbacks through the holder, which always has
    // the latest ones (never those of a destroyed activity).
    SideEffect {
        holder.onError = onError
        holder.onConversationStart = onConversationStart
        holder.onConnectionStatusChange = onConnectionStatusChange
        holder.onUnreadCountChange = onUnreadCountChange
    }
    return holder.obtain(arguments) {
        holder.onError = onError
        holder.onConversationStart = onConversationStart
        holder.onConnectionStatusChange = onConnectionStatusChange
        holder.onUnreadCountChange = onUnreadCountChange
        GiovChatController(
            tenantKey = tenantKey,
            baseUrl = baseUrl,
            contact = contact,
            sessionKey = sessionKey,
            sessionStore = store,
            keepConnected = keepConnected,
            visible = visible,
            locale = locale,
            overrideStrings = strings,
            onError = { holder.onError?.invoke(it) },
            onConversationStart = { holder.onConversationStart?.invoke(it) },
            onConnectionStatusChange = { holder.onConnectionStatusChange?.invoke(it) },
            onUnreadCountChange = { holder.onUnreadCountChange?.invoke(it) },
            http = http ?: OkHttpGiovHttp(),
            socketFactory = socketFactory ?: OkHttpGiovSocketFactory(),
        )
    }
}

/**
 * The store used when the host passes none. It lives as long as the process, as documented: a store remembered in the
 * composition would be lost with every configuration change (rotation, dark mode, resizing), and with it the
 * conversation. Sessions are keyed by API, workspace and visitor, so sharing it is safe.
 */
private val ProcessSessionStore: GiovSessionStore = InMemoryGiovSessionStore()

private val HolderFactory = viewModelFactory { initializer { ControllerHolder() } }

/** Keeps one controller across configuration changes; a new one when the arguments change. */
internal class ControllerHolder : ViewModel() {
    private var arguments: List<Any?>? = null
    private var controller: GiovChatController? = null
    @Volatile var onError: ((GiovChatException) -> Unit)? = null
    @Volatile var onConversationStart: ((String) -> Unit)? = null
    @Volatile var onConnectionStatusChange: ((GiovConnectionStatus) -> Unit)? = null
    @Volatile var onUnreadCountChange: ((Int) -> Unit)? = null

    fun obtain(arguments: List<Any?>, create: () -> GiovChatController): GiovChatController {
        val current = controller
        if (current != null && arguments == this.arguments) return current
        current?.close()
        return create().also {
            controller = it
            this.arguments = arguments
        }
    }

    public override fun onCleared() {
        controller?.close()
        controller = null
        arguments = null
    }
}

/**
 * The full chat screen, creating its own controller with [rememberGiovChatController] (kept across configuration
 * changes, closed with the screen). Embed it in any Activity, Fragment or navigation destination; it fills the space
 * it is given.
 *
 * ```kotlin
 * GiovChat(tenantKey = "ws_123", onClose = { navController.popBackStack() })
 * ```
 *
 * It draws every phase of the session (loading, pre-chat form, greeting, connecting, active conversation, error
 * with retry), forwards the app's foreground/background changes to the controller and tells it when the chat is
 * visible. The dashboard's colour and theme apply unless overridden here.
 *
 * @param tenantKey the workspace key; see [GiovChatController] for the connection options.
 * @param contact what you know about the visitor; also pre-fills the pre-chat form.
 * @param sessionStore back this with encrypted storage to resume the conversation across launches.
 * @param visible whether the chat is on screen. A hidden chat disconnects (unless [keepConnected]); showing it
 *   clears unread replies.
 * @param onClose shows a close button in the header when set.
 * @param theme overrides the dashboard's theme; [GiovThemeMode.AUTO] follows the device.
 * @param primaryColor overrides the dashboard's brand colour.
 * @param styleOverrides fonts and colours per surface; layout and the branding footer stay SDK-controlled.
 * @param strings replaces any interface string, e.g. `{ it.withOverrides(sendMessage = "Envoyer") }`.
 * @param attachmentPicker enables attachments with the app's own picker; without it the attach button is hidden.
 * @param onOpenUrl how links and attachments open (default: `ACTION_VIEW`). Only http(s) and mailto links reach it.
 * @param imageLoader loads avatars and image attachments (default: OkHttp with a memory cache).
 * @param safeArea keeps content clear of the status bar, cutouts, the keyboard and the navigation bar; turn it off
 *   when a parent already applies the insets.
 * @param lifecycleOwner whose start/stop mean the app is in the foreground/background.
 */
@Composable
public fun GiovChat(
    tenantKey: String,
    modifier: Modifier = Modifier,
    baseUrl: String = GIOV_DEFAULT_BASE_URL,
    contact: GiovContact? = null,
    sessionKey: String? = null,
    sessionStore: GiovSessionStore? = null,
    keepConnected: Boolean = false,
    visible: Boolean = true,
    locale: String? = null,
    strings: ((GiovChatStrings) -> GiovChatStrings)? = null,
    onError: ((GiovChatException) -> Unit)? = null,
    onConversationStart: ((String) -> Unit)? = null,
    onConnectionStatusChange: ((GiovConnectionStatus) -> Unit)? = null,
    onUnreadCountChange: ((Int) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    theme: GiovThemeMode? = null,
    primaryColor: Color? = null,
    styleOverrides: GiovChatStyleOverrides = GiovChatStyleOverrides(),
    attachmentPicker: GiovAttachmentPicker? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    imageLoader: GiovImageLoader = DefaultImageLoader,
    safeArea: Boolean = true,
    lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
    http: GiovHttp? = null,
    socketFactory: GiovSocketFactory? = null,
) {
    val controller = rememberGiovChatController(
        tenantKey = tenantKey,
        baseUrl = baseUrl,
        contact = contact,
        sessionKey = sessionKey,
        sessionStore = sessionStore,
        keepConnected = keepConnected,
        visible = visible,
        locale = locale,
        strings = strings,
        onError = onError,
        onConversationStart = onConversationStart,
        onConnectionStatusChange = onConnectionStatusChange,
        onUnreadCountChange = onUnreadCountChange,
        http = http,
        socketFactory = socketFactory,
    )
    GiovChat(
        controller = controller,
        modifier = modifier,
        visible = visible,
        initialContact = contact,
        onClose = onClose,
        theme = theme,
        primaryColor = primaryColor,
        styleOverrides = styleOverrides,
        attachmentPicker = attachmentPicker,
        onOpenUrl = onOpenUrl,
        imageLoader = imageLoader,
        safeArea = safeArea,
        lifecycleOwner = lifecycleOwner,
    )
}

/**
 * The full chat screen on a controller you own. The composable never closes it; when it leaves the composition the
 * chat is marked not visible (so a `keepConnected` controller keeps counting unread replies).
 *
 * @param autoStart start the controller when the screen appears. Pass false when you already called
 *   [GiovChatController.start].
 * @param initialContact pre-fills the pre-chat form.
 * @param forwardLifecycle forward the app's foreground/background changes to the controller. Turn it off when you
 *   call [GiovLifecycleEffect] yourself (a `keepConnected` controller that lives longer than this screen).
 * @param locale interface language; defaults to the controller's.
 * @param strings replaces interface strings on top of the language.
 * See the other overload for the remaining parameters.
 */
@Composable
public fun GiovChat(
    controller: GiovChatController,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    autoStart: Boolean = true,
    initialContact: GiovContact? = null,
    onClose: (() -> Unit)? = null,
    theme: GiovThemeMode? = null,
    primaryColor: Color? = null,
    styleOverrides: GiovChatStyleOverrides = GiovChatStyleOverrides(),
    locale: String? = null,
    strings: ((GiovChatStrings) -> GiovChatStrings)? = null,
    attachmentPicker: GiovAttachmentPicker? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    imageLoader: GiovImageLoader = DefaultImageLoader,
    safeArea: Boolean = true,
    lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
    forwardLifecycle: Boolean = true,
) {
    val ui by controller.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable(controller) { mutableStateOf("") }
    val draft = remember(controller) { DraftState() }

    LaunchedEffect(controller, autoStart) { if (autoStart) controller.start() }
    LaunchedEffect(controller, visible) { controller.setVisible(visible) }
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(controller) {
        onDispose {
            // A configuration change recreates the screen at once: the chat stays visible and connected.
            if (activity?.isChangingConfigurations != true) controller.setVisible(false)
        }
    }

    if (forwardLifecycle) GiovLifecycleEffect(controller, lifecycleOwner)

    val texts = remember(controller, locale, strings) {
        if (locale != null || strings != null) resolveGiovStrings(locale, strings) else controller.strings
    }
    val config = ui.config
    val identity = ui.header
    val mode = theme ?: config?.theme ?: GiovThemeMode.LIGHT
    val dark = mode == GiovThemeMode.DARK || (mode == GiovThemeMode.AUTO && isSystemInDarkTheme())
    val primary = primaryColor ?: parseBrandColor(config?.primaryColor)
    val currentOpenUrl by rememberUpdatedState(onOpenUrl)
    val open: (String) -> Unit = remember(context) {
        { url ->
            val custom = currentOpenUrl
            if (custom != null) custom(url) else openWithSystem(context, url)
        }
    }
    val reduceMotion = rememberReduceMotion()
    val botName = config?.botName ?: if (identity?.kind == GiovHeaderKind.BOT) identity.title else null
    val look = remember(dark, primary, texts, styleOverrides, open, imageLoader, botName, config, reduceMotion, controller) {
        GiovLook(
            dark = dark,
            primary = primary,
            strings = texts,
            style = styleOverrides,
            openUrl = open,
            attachmentUrl = controller::attachmentUrl,
            imageLoader = imageLoader,
            botName = botName,
            config = config,
            reduceMotion = reduceMotion,
        )
    }

    val density = LocalDensity.current
    val clamped = remember(density.density, density.fontScale) { Density(density.density, density.fontScale.coerceAtMost(2f)) }
    val insets = if (safeArea) WindowInsets.safeDrawing else WindowInsets(0, 0, 0, 0)
    val phase = ui.phase
    val subtitle = when (phase) {
        GiovChatPhase.ACTIVE, GiovChatPhase.READY -> identity?.status(texts)
        GiovChatPhase.CONNECTING -> texts.connecting
        else -> null
    }

    CompositionLocalProvider(LocalGiovLook provides look, LocalDensity provides clamped) {
        Column(
            modifier
                .fillMaxSize()
                .background(look.background)
                .testTag("giov-root")
                .semantics { this[GiovSurfaceKey] = GiovSurface(look.dark, look.background.toArgb(), look.headerColor.toArgb()) },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(look.headerColor)
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                ChatHeader(
                    title = identity?.title ?: config?.title ?: "Chat",
                    subtitle = subtitle,
                    avatarUrl = identity?.avatar,
                    kind = identity?.kind,
                    onClose = onClose,
                    privacyUrl = config?.privacyPolicyUrl ?: GIOV_PRIVACY_URL,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (phase) {
                        GiovChatPhase.LOADING, GiovChatPhase.IDLE -> Skeleton(texts.loading)
                        GiovChatPhase.ERROR -> ErrorView(
                            text = ui.error?.let { texts.describeError(it) } ?: texts.unavailable,
                            onRetry = { scope.launch { controller.retry() } },
                        )
                        GiovChatPhase.PRE_CHAT -> PreChatForm(
                            fields = ui.requiredFields,
                            initial = initialContact,
                            onSubmit = { info -> scope.launch { controller.submitContact(info) } },
                        )
                        else -> Conversation(controller, ui, attachmentPicker, draft, input) { input = it }
                    }
                }
                if (config?.showBranding != false) Branding(look.openUrl)
            }
        }
    }
}

@Composable
private fun Conversation(
    controller: GiovChatController,
    ui: app.giovchat.core.GiovChatUiState,
    picker: GiovAttachmentPicker?,
    draft: DraftState,
    input: String,
    onInput: (String) -> Unit,
) {
    val look = LocalGiovLook.current
    val strings = look.strings
    val config = ui.config
    val scope = rememberCoroutineScope()
    val currentInput by rememberUpdatedState(input)
    var files by draft::files
    var problem by draft::problem
    var busy by draft::busy
    var stick by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val composing = ui.phase == GiovChatPhase.READY || ui.phase == GiovChatPhase.CONNECTING || ui.phase == GiovChatPhase.ACTIVE
    val canSend = composing && !ui.sending && !busy && (input.trim().isNotEmpty() || files.isNotEmpty())
    val policy = config?.attachmentPolicy
    val canAttach = picker != null && ui.hasConversation && policy != null && policy.enabled && policy.allowedTypes.isNotEmpty()

    // The list follows new content only while the reader is at the bottom (or just sent something).
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) stick = !listState.canScrollForward
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                stick = !listState.canScrollForward
                return Velocity.Zero
            }
        }
    }
    val last = ui.messages.lastOrNull()
    val count = 1 + ui.messages.size + (if (ui.agentTyping) 1 else 0)
    LaunchedEffect(ui.messages.size, last?.content?.length, ui.agentTyping, ui.phase) {
        if (!stick || count <= 0) return@LaunchedEffect
        val animate = last?.isStreaming != true && !look.reduceMotion
        if (animate) listState.animateScrollToItem(count - 1) else listState.scrollToItem(count - 1)
        listState.scrollBy(100_000f)
        // A lazy list estimates its extent; settle once more after the frame it laid out.
        withFrameNanos { }
        if (stick) listState.scrollBy(100_000f)
    }

    fun send() {
        if (!canSend) return
        val text = input.trim()
        val sent = files
        stick = true
        busy = true
        scope.launch {
            val ok = try {
                controller.send(text, sent)
            } finally {
                busy = false
            }
            if (!ok) return@launch
            if (currentInput.trim() == text) onInput("")
            if (files === sent) files = emptyList()
            problem = null
        }
    }

    fun attach() {
        val pick = picker ?: return
        val rules = policy ?: return
        problem = null
        scope.launch {
            try {
                val picked = pick(GiovPickerRequest(rules.allowedTypes, maxOf(1, MAX_ATTACHED_FILES - files.size), rules.maxSizeMb))
                val checked = controller.checkFiles(picked, files.size)
                if (checked.accepted.isNotEmpty()) files = files + checked.accepted
                if (checked.problems.isNotEmpty()) problem = checked.problems.joinToString(" ") { problemText(it, strings) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The picker was dismissed or failed; nothing changes.
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val error = ui.error
        // A socket that was connected is coming back (or a first connection is retried with the backoff).
        val reconnecting = ui.phase == GiovChatPhase.CONNECTING && ui.connectionStatus == GiovConnectionStatus.DISCONNECTED
        if (error == null && reconnecting) {
            BasicText(
                strings.reconnecting,
                style = look.hintStyle.copy(fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .testTag("giov-reconnecting")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (error != null) {
            BasicText(
                strings.describeError(error),
                style = look.errorStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .testTag("giov-error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(connection).testTag("giov-messages"),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item(key = "giov-greeting") { if (config != null) GreetingBubble(config.greetingText) }
            items(ui.messages, key = { it.key }) { message ->
                val id = message.clientMessageId
                MessageBubble(
                    message = message,
                    agentName = ui.agentName,
                    onRetry = id?.let { { controller.retryMessage(it) } },
                    onDiscard = id?.let { { controller.discardMessage(it) } },
                )
            }
            if (ui.agentTyping) item(key = "giov-typing-row") { TypingIndicator(ui.agentTypingIsBot) }
        }
        if (files.isNotEmpty() || problem != null) {
            PendingFiles(files, ui.uploads, ui.sending, problem, onRemove = { index -> files = files.filterIndexed { i, _ -> i != index } })
        }
        Composer(
            text = input,
            onTextChange = {
                onInput(it)
                if (it.trim().isNotEmpty()) controller.notifyTyping()
            },
            placeholder = config?.textArea ?: strings.composerPlaceholder,
            // Typing stays possible while files upload; only sending waits (canSend).
            enabled = composing,
            canSend = canSend,
            onSend = ::send,
            onAttach = if (canAttach) ::attach else null,
            attachEnabled = !ui.sending && files.size < MAX_ATTACHED_FILES,
        )
    }
}

private fun problemText(problem: AttachmentProblem, strings: GiovChatStrings): String = when (problem) {
    AttachmentProblem.Disabled -> strings.attachmentsOff
    is AttachmentProblem.TypeNotAllowed -> strings.typeNotAllowed(problem.name)
    is AttachmentProblem.TooLarge -> strings.fileTooLarge(problem.name, problem.maxSizeMb)
    is AttachmentProblem.TooMany -> strings.tooManyFiles(problem.max)
}

@Composable
private fun Skeleton(label: String) {
    val look = LocalGiovLook.current
    val fill = if (look.dark) Color(0xFF1C1C1C) else Color(0xFFF0F0F0)
    @Composable
    fun Bar(width: Int, height: Int, right: Boolean = false) {
        Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(width.dp, height.dp).background(fill, RoundedCornerShape(16.dp)))
        }
    }
    Column(
        Modifier
            .testTag("giov-loading")
            .fillMaxSize()
            .padding(16.dp)
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Spacer(Modifier.height(8.dp))
        Bar(220, 44)
        Bar(150, 36, right = true)
        Bar(250, 60)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BasicText(label, style = look.hintStyle) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ErrorView(text: String, onRetry: () -> Unit) {
    val look = LocalGiovLook.current
    val label = look.strings.tryAgain
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text,
            style = look.errorStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.testTag("giov-error").semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .testTag("giov-try-again")
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .background(look.buttonColor, RoundedCornerShape(8.dp))
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 28.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = look.buttonTextStyle)
        }
    }
}

/** "Powered by GiovChat". Deliberately isolated from the style overrides, like the other SDKs. */
@Composable
private fun Branding(openUrl: (String) -> Unit) {
    val text: AnnotatedString = remember {
        buildAnnotatedString {
            append("Powered by ")
            withStyle(SpanStyle(color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold)) { append("GiovChat") }
        }
    }
    Box(
        Modifier
            .testTag("giov-branding")
            .fillMaxWidth()
            .background(Color.White)
            .topHairline(Color(0xFFEBEBEB))
            .defaultMinSize(minHeight = 48.dp)
            .semantics {
                contentDescription = "Powered by GiovChat"
                role = Role.Button
            }
            .clickable(role = Role.Button) {
                try {
                    openUrl(BRANDING_URL)
                } catch (_: Exception) {
                    // Nothing can open it.
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color(0xFF52525B), fontSize = 12.sp))
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

internal val DefaultImageLoader: GiovImageLoader by lazy { OkHttpGiovImageLoader() }

/** What the visitor is composing, kept while the screen shows other phases (an error, a reconnect). */
@Stable
private class DraftState {
    var files: List<GiovPickedFile> by mutableStateOf(emptyList())
    var problem: String? by mutableStateOf(null)
    var busy: Boolean by mutableStateOf(false)
}

/**
 * Forwards the app's foreground/background changes to [controller] (`onForeground` / `onBackground`) while it is in the
 * composition. [GiovChat] does this itself; call it directly for a controller that outlives the chat screen, such as
 * a `keepConnected` one that counts unread replies.
 */
@Composable
public fun GiovLifecycleEffect(controller: GiovChatController, lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()) {
    DisposableEffect(controller, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        var registered = false
        val observer = LifecycleEventObserver { _, event ->
            // Adding an observer replays the current state; the screen starting is not the app returning.
            if (!registered) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> controller.onForeground()
                Lifecycle.Event.ON_STOP -> controller.onBackground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        registered = true
        onDispose { lifecycle.removeObserver(observer) }
    }
}
