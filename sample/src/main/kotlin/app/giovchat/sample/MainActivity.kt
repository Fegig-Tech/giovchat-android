package app.giovchat.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.compose.GiovChat
import app.giovchat.compose.GiovLifecycleEffect
import app.giovchat.compose.rememberGiovChatController
import app.giovchat.core.GiovContact
import app.giovchat.core.GiovUnread

/** Your workspace key (Settings > Chat widget in the dashboard). It is public: it is in every embed snippet. */
private const val TENANT_KEY = "YOUR_WORKSPACE_KEY"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SampleApp() }
    }
}

@Composable
private fun SampleApp() {
    val context = LocalContext.current
    var chatOpen by rememberSaveable { mutableStateOf(false) }

    // One controller for the whole app screen: it keeps counting unread replies while the chat is closed
    // (keepConnected) and resumes the stored conversation. Signed-in apps pass the identity; compute `userHash`
    // (HMAC-SHA256 of the user id with your identity secret, lowercase hex) on YOUR server, never in the app.
    val controller = rememberGiovChatController(
        tenantKey = TENANT_KEY,
        contact = GiovContact(
            // userId = account.id,
            // userHash = account.giovUserHash,
            // email = account.email,
            // firstName = account.firstName,
        ),
        sessionStore = remember { EncryptedSessionStore(context) },
        keepConnected = true,
        visible = chatOpen,
        onError = { /* log it */ },
    )

    // Push (not wired here, so the sample builds without Firebase). With Firebase Messaging in your app:
    //
    //   LaunchedEffect(controller) {
    //       val token = FirebaseMessaging.getInstance().token.await()   // kotlinx-coroutines-play-services
    //       controller.registerPushToken(token)                         // FCM is the default platform
    //   }
    //
    // and in your FirebaseMessagingService:
    //
    //   override fun onNewToken(token: String) { appScope.launch { controller.registerPushToken(token) } }
    //   override fun onMessageReceived(m: RemoteMessage) {
    //       if (m.data["type"] == "giov.message") { /* show a notification; on tap open the chat */ }
    //   }
    //
    // The workspace adds its Firebase service account in the dashboard under Settings > Chat widget > Push notifications.
    LaunchedEffect(controller) { /* registerPushToken goes here */ }

    // The controller outlives the chat screen, so start it and forward the app lifecycle here.
    LaunchedEffect(controller) { controller.start() }
    GiovLifecycleEffect(controller)

    BackHandler(enabled = chatOpen) { chatOpen = false }

    if (chatOpen) {
        GiovChat(
            controller = controller,
            visible = true,
            autoStart = false,
            forwardLifecycle = false,
            onClose = { chatOpen = false },
            attachmentPicker = rememberAttachmentPicker(),
        )
    } else {
        Home(onOpenChat = { chatOpen = true })
    }
}

@Composable
private fun Home(onOpenChat: () -> Unit) {
    val unread by GiovUnread.watch().collectAsState()
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF0A0A0A) else Color.White)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            "GiovChat sample",
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (dark) Color.White else Color(0xFF111111)),
        )
        Row(
            Modifier
                .padding(top = 24.dp)
                .defaultMinSize(minHeight = 48.dp)
                .background(Color(0xFF7C3AED), RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = onOpenChat)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("Chat with us", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
            if (unread > 0) {
                Box(Modifier.size(24.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    BasicText(
                        if (unread > 9) "9+" else unread.toString(),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED)),
                    )
                }
            }
        }
    }
}
