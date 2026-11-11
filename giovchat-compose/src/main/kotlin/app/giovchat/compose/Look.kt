package app.giovchat.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovChatStrings
import app.giovchat.core.GiovWidgetConfig
import app.giovchat.core.markdown.ChatMarkdown

/** Typography and colour for one text slot. */
@Immutable
public class GiovTextOverride(
    public val fontFamily: FontFamily? = null,
    public val fontWeight: FontWeight? = null,
    public val fontStyle: FontStyle? = null,
    public val color: Color? = null,
)

/**
 * Look-and-feel overrides for [GiovChat], comparable to the React Native and Flutter SDKs: fonts and colours
 * only. Layout, sizes, spacing and the branding footer stay SDK-controlled. Every field is optional; the
 * dashboard's colours and the light/dark theme apply wherever a slot is not set.
 */
@Immutable
public class GiovChatStyleOverrides(
    /** Default font family for all customizable text. */
    public val fontFamily: FontFamily? = null,
    /** Background of the chat surface. */
    public val container: Color? = null,
    /** Header background. */
    public val header: Color? = null,
    public val headerTitle: GiovTextOverride? = null,
    public val headerSubtitle: GiovTextOverride? = null,
    /** Agent, greeting and typing bubbles. */
    public val agentBubble: Color? = null,
    /** The visitor's bubbles. */
    public val visitorBubble: Color? = null,
    /** Message content, sender labels and timestamps; also the greeting and typing bubbles. */
    public val agentText: GiovTextOverride? = null,
    public val visitorText: GiovTextOverride? = null,
    /** Composer row background. */
    public val composer: Color? = null,
    /** Text of the composer and the pre-chat inputs. */
    public val input: GiovTextOverride? = null,
    public val inputBackground: Color? = null,
    /** Send, start-chat and retry buttons. */
    public val button: Color? = null,
    public val buttonText: GiovTextOverride? = null,
    /** Pre-chat form background, heading and field labels. */
    public val form: Color? = null,
    public val formTitle: GiovTextOverride? = null,
    public val label: GiovTextOverride? = null,
    /** Loading hint, attachment chips, input placeholders. */
    public val hint: GiovTextOverride? = null,
    public val errorText: GiovTextOverride? = null,
) {
    /** [base] with this slot's overrides (and the global [fontFamily]) applied. */
    internal fun text(base: TextStyle, slot: GiovTextOverride?): TextStyle = base.copy(
        fontFamily = slot?.fontFamily ?: fontFamily ?: base.fontFamily,
        fontWeight = slot?.fontWeight ?: base.fontWeight,
        fontStyle = slot?.fontStyle ?: base.fontStyle,
        color = slot?.color ?: base.color,
    )
}

internal val DefaultBrand = Color(0xFF7C3AED)

private val NearBlack = Color(0xFF111111)

/** The WCAG 2 contrast ratio of two opaque colours, 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

/**
 * Text on a brand colour: white or near-black, whichever contrasts more. A fixed luminance threshold put white on
 * mid greens and ambers at about 2.3:1.
 */
internal fun readableOn(color: Color): Color = if (contrastRatio(color, NearBlack) > contrastRatio(color, Color.White)) NearBlack else Color.White

/** `#rgb`, `#rrggbb` or `#aarrggbb`, else [fallback]. */
internal fun parseBrandColor(value: String?, fallback: Color = DefaultBrand): Color {
    var hex = (value ?: "").trim()
    if (hex.startsWith("#")) hex = hex.substring(1)
    if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return fallback
    if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
    if (hex.length == 6) hex = "ff$hex"
    if (hex.length != 8) return fallback
    return Color(hex.toLong(16).toInt())
}

/** Only http(s) and mailto links are opened, whatever the text claims. */
internal fun openGiovLink(open: (String) -> Unit, href: String?) {
    if (!ChatMarkdown.isOpenableHref(href)) return
    try {
        open(href!!)
    } catch (_: Exception) {
        // A link that cannot open is ignored.
    }
}

private val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

internal fun isHttpUrl(url: String): Boolean = HTTP_URL.containsMatchIn(url)

/** Opens [url] with the system (browser, mail app). A link nothing can handle is ignored. */
internal fun openWithSystem(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No app can open it.
    }
}

/** What every chat component shares. Provided by [GiovChat]. */
@Stable
internal class GiovLook(
    val dark: Boolean,
    val primary: Color,
    val strings: GiovChatStrings,
    val style: GiovChatStyleOverrides,
    val openUrl: (String) -> Unit,
    val attachmentUrl: (String) -> String,
    val imageLoader: GiovImageLoader,
    val botName: String?,
    val config: GiovWidgetConfig?,
    val reduceMotion: Boolean,
) {
    /** White or near-black, whichever has the higher WCAG contrast on [color]. */
    fun on(color: Color): Color = readableOn(color)

    val background: Color get() = style.container ?: if (dark) Color(0xFF0A0A0A) else Color.White
    val headerColor: Color get() = style.header ?: primary
    val buttonColor: Color get() = style.button ?: primary
    val visitorBubble: Color get() = style.visitorBubble ?: primary
    val agentBubble: Color get() = style.agentBubble ?: if (dark) Color(0xFF242424) else Color(0xFFF0F0F0)
    val composerColor: Color get() = style.composer ?: background
    val divider: Color get() = if (dark) Color(0xFF1E1E1E) else Color(0xFFEBEBEB)
    val inputFill: Color get() = style.inputBackground ?: if (dark) Color(0xFF1C1C1C) else Color(0xFFF5F5F5)
    val inputBorder: Color get() = if (dark) Color(0xFF2E2E2E) else Color(0xFFE5E5E5)
    val text: Color get() = if (dark) Color(0xFFF0F0F0) else Color(0xFF111111)
    val secondaryText: Color get() = if (dark) Color(0xFFAAAAAA) else Color(0xFF555555)
    val error: Color get() = Color(0xFFDC2626)

    /** Text on the visitor bubble: the override, else a colour that reads on the bubble. */
    val onVisitorBubble: Color get() = style.visitorText?.color ?: on(visitorBubble)
    val onAgentBubble: Color get() = style.agentText?.color ?: text

    val hintStyle: TextStyle
        get() = style.text(TextStyle(fontSize = 14.sp, color = if (dark) Color(0xFF8A8A8A) else Color(0xFF777777)), style.hint)
    val errorStyle: TextStyle
        get() = style.text(TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = error), style.errorText)
    val buttonTextColor: Color get() = on(buttonColor)
    val buttonTextStyle: TextStyle
        get() = style.text(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = buttonTextColor), style.buttonText)
}

internal val LocalGiovLook = compositionLocalOf<GiovLook> { error("Giov chat components must be placed under GiovChat.") }

/**
 * Overrides the "reduce motion" setting (the system's animator duration scale of 0). Tests set it to keep
 * infinite animations out of the way.
 */
internal val LocalGiovReduceMotion = compositionLocalOf<Boolean?> { null }

@Composable
internal fun rememberReduceMotion(): Boolean {
    val override = LocalGiovReduceMotion.current
    val context = LocalContext.current
    val system = remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
    return override ?: system
}

/** What a UI test can read back about the resolved palette; invisible to accessibility services. */
internal class GiovSurface(val dark: Boolean, val background: Int, val header: Int)

internal val GiovSurfaceKey = SemanticsPropertyKey<GiovSurface>("GiovSurface")

/** The fill colour (ARGB) of a bubble, for UI tests. */
internal val GiovFillKey = SemanticsPropertyKey<Int>("GiovFill")
