# Giov chat for Android

Add the Giov visitor chat to an Android app: the same conversations, bot (GiovBot) and team inbox as the web widget,
the React Native SDK and the Flutter SDK.

| Module | What it is |
| --- | --- |
| `giovchat-core` | Pure Kotlin/JVM: config, sessions, realtime socket, outbox, streaming replies, attachments, unread counts, push registration and the chat markdown parser. No UI, no Android dependency. |
| `giovchat-compose` | Jetpack Compose chat screen on top of the core: `GiovChat(...)`. |
| `sample` | A runnable app: the chat, an unread badge, an encrypted session store, a file picker, and commented push registration. |

The behaviour follows the Giov visitor protocol, so it matches the other SDKs. Full documentation:
[giovchat.app/docs](https://giovchat.app/docs).

## Requirements

- Android 7.0 (API 24) or newer (`minSdk 24`); the library is compiled against API 35.
- Java 17 bytecode (`compileOptions` / `jvmTarget` 17).
- Kotlin 2.1 or newer; for `giovchat-compose`, the Kotlin Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`).
- `giovchat-core` alone runs on any JVM 17+ (no Android dependency).

## Install

The SDK is on Maven Central. Make sure `mavenCentral()` is in your repositories (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Then add the module you need:

```kotlin
dependencies {
    implementation("app.giovchat:giovchat-compose:0.1.0")   // the Compose screen; brings giovchat-core
    // implementation("app.giovchat:giovchat-core:0.1.0")   // only the controller, for your own UI
}
```

The library declares the `INTERNET` permission.

## Quick start

```kotlin
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GiovChat(
                tenantKey = "YOUR_WORKSPACE_KEY",      // Settings > Chat widget in the dashboard (public)
                onClose = { finish() },
            )
        }
    }
}
```

`GiovChat` fills the space it is given, so it works as a whole Activity, a Fragment's `ComposeView`, a navigation
destination or a bottom sheet. It draws every phase (loading skeleton, pre-chat form, greeting, connecting, active
conversation, error with "Try again"), forwards the app's foreground/background changes to the controller, and tells
it whether the chat is on screen. Opening the screen never creates an empty conversation: the first message (or the
pre-chat form) starts one.

The dashboard decides the brand colour, the light/dark/auto theme, the greeting, the composer placeholder, the
pre-chat fields and whether "Powered by GiovChat" shows. You can override colour and theme in code (see Theming).

## Controller vs composable

There are two ways in, and both end up in the same screen.

**Let the composable own the controller.** `GiovChat(tenantKey = ...)` creates a `GiovChatController`, starts it and
keeps it in a ViewModel of the screen, so rotation and other configuration changes keep the connection and unsent
messages; it is closed when the screen (activity, fragment or navigation destination) goes away. Its arguments are
compared with `equals`: pass a remembered `sessionStore`, not a new instance on every recomposition. Pass the connection options straight to it: `contact`, `sessionStore`,
`sessionKey`, `keepConnected`, `baseUrl`, `locale`, `strings` and the `onError` / `onConversationStart` /
`onConnectionStatusChange` / `onUnreadCountChange` callbacks.

**Own the controller yourself** when something else needs it (push registration, sign-out, an unread badge, a chat
that must keep counting replies while the screen is closed):

```kotlin
val controller = rememberGiovChatController(tenantKey = "YOUR_WORKSPACE_KEY", keepConnected = true)
// rememberGiovChatController keeps it in a ViewModel of the screen (it survives rotation) and closes it when that
// screen goes away. Pass stable arguments: remember { MyStore(context) }, not a new instance per recomposition.
// Or create one in a ViewModel: GiovChatController(tenantKey = ..., ...); call close() in onCleared().

GiovChat(controller = controller, onClose = { ... })
```

The composable never closes a controller you pass in; when it leaves the screen it marks the chat not visible, so a
`keepConnected` controller keeps counting unread replies. If the controller lives longer than the screen, start it
yourself and forward the app lifecycle yourself:

```kotlin
LaunchedEffect(controller) { controller.start() }
GiovLifecycleEffect(controller)                  // onForeground / onBackground from ProcessLifecycleOwner
GiovChat(controller, autoStart = false, forwardLifecycle = false)
```

Using no Compose at all? `giovchat-core` is the same engine: collect `controller.state` (a `StateFlow<GiovChatUiState>`) and
call `send`, `submitContact`, `retry`, `reset`, `retryMessage`, `discardMessage`, `notifyTyping`, `checkFiles`,
`registerPushToken`.

### `GiovChat` parameters

Common to both overloads:

| Parameter | Purpose |
| --- | --- |
| `modifier` | Layout of the chat; it fills the space it gets. |
| `visible` | Whether the chat is on screen. A hidden chat disconnects unless `keepConnected`; showing it clears unread replies. |
| `onClose` | Shows a close button in the header when set. |
| `theme: GiovThemeMode?` | `LIGHT`, `DARK` or `AUTO` (device); overrides the dashboard. |
| `primaryColor: Color?` | Overrides the dashboard's brand colour (header, visitor bubbles, buttons). |
| `styleOverrides` | Fonts and colours per surface (below). |
| `attachmentPicker` | Enables attachments with your own picker (below). Without it the attach button is hidden. |
| `onOpenUrl: ((String) -> Unit)?` | How links and attachments open (default `ACTION_VIEW`). Only http(s) and mailto links reach it. |
| `imageLoader` | Loads avatars and image attachments; default is a small OkHttp loader with a memory cache. Implement `GiovImageLoader` to use Coil or Glide. |
| `safeArea` | Keeps content clear of the status bar, cutouts, keyboard and navigation bar. Turn off if a parent already applies the insets. |
| `lifecycleOwner` | Whose start/stop mean foreground/background (default `ProcessLifecycleOwner`). |

`GiovChat(tenantKey = ...)` also takes `baseUrl`, `contact`, `sessionKey`, `sessionStore`, `keepConnected`, `locale`,
`strings`, the four callbacks and `http` / `socketFactory` (for tests and proxies).
`GiovChat(controller = ...)` also takes `autoStart`, `forwardLifecycle`, `initialContact` (pre-fills the pre-chat
form), `locale` and `strings`.

## Session storage

The session token is a bearer secret that proves the device owns the conversation. The default store is in memory
and forgets the conversation when the process ends. Back `GiovSessionStore` with encrypted storage so visitors resume
their conversation across launches. Encrypt it with a key from the Android Keystore; no library is needed
(`androidx.security:security-crypto` and its `MasterKeys` are deprecated). The complete version is
`sample/.../EncryptedSessionStore.kt`; in short:

```kotlin
class EncryptedSessionStore(context: Context) : GiovSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("giov_chat_sessions", Context.MODE_PRIVATE)

    override suspend fun read(scope: String) = withContext(Dispatchers.IO) {
        val stored = prefs.getString(sha256(scope), null) ?: return@withContext null
        // AES/GCM/NoPadding with a Keystore key; a value that no longer decrypts (reinstall) is removed.
        decryptOrNull(stored)?.split('\n', limit = 2)?.let { (id, token) -> GiovSession(id, token) }
    }
    override suspend fun write(scope: String, session: GiovSession) = withContext(Dispatchers.IO) {
        prefs.edit().putString(sha256(scope), encrypt("${session.conversationId}\n${session.sessionToken}")).apply()
    }
    override suspend fun delete(scope: String) = withContext(Dispatchers.IO) { prefs.edit().remove(sha256(scope)).apply() }
}
```

Jetpack DataStore with the same Keystore encryption works too. Keep the file out of Auto Backup and device transfer
(`android:dataExtractionRules` and `android:fullBackupContent` excluding `sharedpref/giov_chat_sessions.xml`, as the
sample's `res/xml` does): the token is a bearer secret, and a restored copy could not be decrypted anyway because the
Keystore key never leaves the device.

The store is keyed by API, workspace, `userId` and `sessionKey` (not by name, email or phone, so a visitor who
corrects their details keeps their conversation), so signing in as someone else never resumes the previous person's
conversation. Call `controller.reset()` on sign-out to forget the conversation, the
stored session and the outbox. Storage failures are reported through `onError` as `GiovStorageException` and never crash the chat.

## Identity (`userId` + `userHash`)

Tell the chat who the visitor is so the team sees the right customer across devices:

```kotlin
GiovChat(
    tenantKey = "YOUR_WORKSPACE_KEY",
    contact = GiovContact(
        userId = account.id,
        userHash = account.giovUserHash,   // HMAC-SHA256(identity secret, userId), lowercase hex
        email = account.email,
        firstName = account.firstName,
    ),
)
```

`userHash` must be computed **on your server** with the identity secret from the dashboard and sent to the app after
sign-in. Never put the identity secret in the app. A `userId` without a matching `userHash` is treated like any other
untrusted detail. Details you supply also pre-fill the pre-chat form, which asks only for the fields the workspace
requires and you did not provide (and that are valid).

## Attachments

Attachments are enabled when you pass an `attachmentPicker` and a conversation exists (uploads need one). The SDK
passes your picker the types the bot accepts, how many more files fit and the size limit, then checks what comes back
against the policy (`controller.checkFiles`), shows the problems as a sentence and the files as removable chips, and
uploads with progress when the visitor sends.

The picker is a suspend lambda, so any ActivityResult contract fits. Return an empty list on cancel:

```kotlin
@Composable
fun rememberAttachmentPicker(): GiovAttachmentPicker {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<List<Uri>>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pending[0]?.complete(uris)
    }
    return remember(launcher, context) {
        val picker: GiovAttachmentPicker = { request ->
            val result = CompletableDeferred<List<Uri>>().also { pending[0] = it }
            launcher.launch(request.allowedTypes.toTypedArray())
            val uris = result.await().take(request.maxFiles)
            withContext(Dispatchers.IO) { uris.mapNotNull { describe(context, it) } }
        }
        picker
    }
}

// describe() turns a Uri into a GiovPickedFile: name and size from OpenableColumns, the MIME type from the
// ContentResolver, image width/height from BitmapFactory (bounds only), an optional ~32 px JPEG thumbnail
// ("data:image/jpeg;base64,..."), and `open = { contentResolver.openInputStream(uri)!! }` so the bytes are read
// only when the upload starts.
```

The complete version (with the thumbnail) is `sample/.../AttachmentPicker.kt`. Files must be one of the
supported types (images, PDF, text/CSV, Office documents, audio), within the bot's size limit (at most 10 MB) and at
most 5 per message. Messages show an image box of its final shape immediately (from the preview `width`/`height`),
a blurred placeholder from `thumbBase64` while the picture loads, and non-image files as tappable rows. Downloads
carry exactly one credential (`controller.attachmentUrl`).

## Unread badge

A controller created with `keepConnected = true` stays connected while the chat is closed (and the app is in the
foreground), counts replies the visitor has not seen and never starts a conversation. Read the total anywhere:

```kotlin
val unread by GiovUnread.watch().collectAsState()      // all chats; GiovUnread.watch("YOUR_WORKSPACE_KEY") for one
if (unread > 0) Badge(unread)
```

Opening the chat (`visible = true`) clears the count.

## Push notifications

When the chat is closed and the app is in the background, replies arrive as push notifications.

1. In the dashboard, under **Settings -> Chat widget -> Push notifications**, add your Firebase service account
   (the workspace sends through your Firebase project; the SDK does not include Firebase).
2. In the app, add Firebase Messaging and register the **FCM token** with the controller. `FCM` is the default
   platform; APNs is for iOS only and does not apply on Android.

```kotlin
// when the controller is created / the user signs in
scope.launch {
    val token = FirebaseMessaging.getInstance().token.await()
    controller.registerPushToken(token)               // never throws; returns a GiovPushResult
}

// FirebaseMessagingService
override fun onNewToken(token: String) { appScope.launch { controller.registerPushToken(token) } }
```

`registerPushToken` registers at once when the conversation is connected and otherwise as soon as it connects, so you
can call it right at start-up and again whenever Firebase hands out a new token. `unregisterPushToken(token)` stops
pushes on sign-out; it works while the chat is closed, so call it before `reset()`.

The push carries a data payload you can use to open the right screen when the visitor taps the notification:

```
data = { type: "giov.message", tenantId, conversationId, messageId }
```

Show the notification yourself in `onMessageReceived` when `data["type"] == "giov.message"` (for data-only messages) and
open the activity that hosts `GiovChat` from its `PendingIntent`. The `sample` app has this wiring as comments.

## Languages and text

The interface text ships in English, French, Spanish, Portuguese and German and follows the device language. Pick one,
or replace individual strings:

```kotlin
GiovChat(
    tenantKey = "...",
    locale = "pt-BR",                                              // "fr", "es", "de", ...
    strings = { it.withOverrides(sendMessage = "Enviar", composerPlaceholder = "Escreva aqui…") },
)
```

Text the business writes in the dashboard (greeting, placeholder, bot name) is shown as written and is not translated.

## Theming

The look comes from the dashboard (brand colour, light/dark/auto). Override it in code:

```kotlin
GiovChat(
    tenantKey = "...",
    theme = GiovThemeMode.DARK,
    primaryColor = Color(0xFF0EA5E9),
    styleOverrides = GiovChatStyleOverrides(
        fontFamily = myFonts,
        header = Color(0xFF0F172A),
        headerTitle = GiovTextOverride(color = Color.White, fontWeight = FontWeight.ExtraBold),
        visitorBubble = Color(0xFF0EA5E9),
        agentBubble = Color(0xFFF1F5F9),
        container = Color(0xFFF8FAFC),
        button = Color(0xFF0F172A),
    ),
)
```

Style overrides cover fonts and colours only (`fontFamily`, `container`, `header`, `headerTitle`, `headerSubtitle`,
`agentBubble`, `visitorBubble`, `agentText`, `visitorText`, `composer`, `input`, `inputBackground`, `button`,
`buttonText`, `form`, `formTitle`, `label`, `hint`, `errorText`). Layout, sizes, spacing and the "Powered by GiovChat"
footer stay SDK-controlled, like the other SDKs.

### Accessibility

Targets are at least 48 dp, controls have content descriptions in the chosen language, the header and error text are
announced as headings and live regions, the typing indicator describes who is typing, and text follows the system font
size (clamped at 2x so the layout holds). The typing dots stand still when animations are turned off (animator
duration scale 0). Text on the brand colour is black or white, whichever has the higher contrast, and the labels and
times on agent bubbles meet 4.5:1.

## Behaviour worth knowing

- A message you send shows at once as "Sending…", is queued in the outbox and goes out when a socket is open, so text
  typed while reconnecting is never lost. A message that cannot be delivered shows "Not sent" with **Retry** and
  **Discard**.
- GiovBot replies stream in as a draft that is replaced in place by the saved reply (same list key, no flicker).
- Only `http(s)` and `mailto:` links are tappable; anything else stays plain text.
- A first connection that fails is retried with the protocol backoff; while a socket comes back the chat shows
  "Connection lost. Reconnecting…". Calling `start()` again on a started controller does nothing; `retry()` restarts.
- The header links to the workspace's privacy policy (dashboard setting), else to Giov's, like the web widget.
- For image attachments, `GiovImagePlaceholder.create(bitmap)` makes the placeholder other viewers see while the
  image loads (transparent images are drawn over white).
- The list follows new messages while the visitor is at the bottom and keeps its place while they read older ones.
- The header shows the teammate once one has written, else the bot, else the team (name and photo from the dashboard).

## App size

Measured on release APKs shrunk with R8 (`minifyEnabled`, `shrinkResources`, minSdk 24), 0.1.0:

| Your app | Added by Giov |
| --- | --- |
| Uses Jetpack Compose, adds `giovchat-compose` | about **0.4 MB** |
| Uses Views only, adds `giovchat-core` and builds its own UI | about **0.3 MB** (OkHttp, coroutines and serialization included; less if you already ship them) |
| Uses Views only, adds `giovchat-compose` | about **1.2 MB** (Compose UI and foundation included) |

The compose module uses Compose UI and foundation only: no Material 3, no icon library, no image library and no
Firebase. Only `giovchat-core` is needed for a custom UI.

## Build and test

```
./gradlew test :sample:assembleDebug
./gradlew publishToMavenLocal          # installs app.giovchat:*:<version> into ~/.m2 for local testing
```

The Compose tests run on the JVM with Robolectric (the first run downloads the Android runtime jar), drive the real
controller with in-memory fakes of the Giov API and sockets, and cover each phase, sending and failing, streaming,
links, the header, branding, themes, lifecycle and 48 dp targets at a large font scale.
Set `sdk.dir` in `local.properties` (or `ANDROID_HOME`) to build.

## Licence

MIT, see [LICENSE](LICENSE).
