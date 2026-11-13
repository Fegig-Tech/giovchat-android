package app.giovchat.compose

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovChatController
import app.giovchat.core.GiovChatPhase
import app.giovchat.core.GiovWidgetConfig

// The "Powered by GiovChat" footer follows the app's style but stays on: its colours are the host's only while they
// stay readable (WCAG AA), and only the server's `showBranding: false` removes it.

private const val BRANDING_URL = "https://giovchat.app/?utm_source=android_widget&utm_medium=powered_by"

internal val BrandingDefaultText = Color(0xFF52525B)
internal val BrandingDefaultBrand = Color(0xFF7C3AED)
internal val BrandingDefaultBackground = Color.White
internal val BrandingDivider = Color(0xFFEBEBEB)
internal const val BRANDING_MIN_CONTRAST = 4.5f

/** What the footer draws after the host's overrides were checked. */
internal class BrandingColors(
    val text: Color,
    val brand: Color,
    val background: Color,
    /** The host set a background: the divider turns translucent grey instead of #EBEBEB. */
    val customBackground: Boolean,
    /** One line per rejected override, for the developer's log. */
    val warnings: List<String>,
)

private fun Color.opaque(): Boolean = alpha >= 1f

/**
 * Checks the host's colours. The background is used only when opaque. Text and link colours are used only when opaque
 * and at least 4.5:1 against the effective background; otherwise the property's default, and when the default fails on
 * that background too, black or white (whichever reads).
 */
internal fun resolveBranding(override: GiovBrandingOverride?): BrandingColors {
    val warnings = mutableListOf<String>()
    var effective = BrandingDefaultBackground
    var custom = false
    val background = override?.backgroundColor
    if (background != null) {
        if (background.opaque()) {
            effective = background
            custom = true
        } else {
            warnings += "GiovBrandingOverride.backgroundColor is translucent; the footer keeps a white background."
        }
    }

    fun readable(color: Color) = color.opaque() && contrastRatio(color, effective) >= BRANDING_MIN_CONTRAST

    fun pick(value: Color?, fallback: Color, name: String): Color {
        if (value != null) {
            if (readable(value)) return value
            warnings += "GiovBrandingOverride.$name is translucent or below 4.5:1 contrast on the footer background; using the default."
        }
        return if (readable(fallback)) fallback else readableOn(effective)
    }

    return BrandingColors(
        text = pick(override?.color, BrandingDefaultText, "color"),
        brand = pick(override?.brandColor, BrandingDefaultBrand, "brandColor"),
        background = effective,
        customBackground = custom,
        warnings = warnings,
    )
}

/**
 * Whether the footer shows: an explicit value wins, then the workspace's `showBranding`; only an explicit `false` from
 * the server removes it. While the configuration has not loaded nothing is drawn, unless the chat failed (then the
 * footer shows, as a failed configuration keeps it).
 */
internal fun shouldShowBranding(explicit: Boolean?, config: GiovWidgetConfig?, phase: GiovChatPhase): Boolean = when {
    explicit != null -> explicit
    config != null -> config.showBranding
    else -> phase == GiovChatPhase.ERROR
}

/**
 * The "Powered by GiovChat" footer as a component, for apps that build their own chat UI on [GiovChatController].
 *
 * It follows [GiovChatStyleOverrides.branding] (and the top-level [GiovChatStyleOverrides.fontFamily]) and shows unless
 * the workspace removed the branding (Business and Advanced plans). The style cannot hide it: colours are used only
 * while they stay readable (4.5:1), the size and layout are fixed.
 *
 * ```kotlin
 * Column {
 *     MyMessages()
 *     GiovBranding(controller = chat)   // or GiovBranding(showBranding = config.showBranding)
 * }
 * ```
 *
 * @param controller follows its configuration: shown unless it says `showBranding: false`; nothing while the
 *   configuration is loading, unless the chat failed (then the footer shows).
 * @param showBranding shown or not as given; wins over [controller].
 * @param onOpenUrl how the link opens (default: `ACTION_VIEW`).
 */
@Composable
public fun GiovBranding(
    controller: GiovChatController? = null,
    showBranding: Boolean? = null,
    styleOverrides: GiovChatStyleOverrides = GiovChatStyleOverrides(),
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val ui = controller?.state?.collectAsState()?.value
    if (!shouldShowBranding(showBranding, ui?.config, ui?.phase ?: GiovChatPhase.LOADING)) return
    val context = LocalContext.current
    val open: (String) -> Unit = onOpenUrl ?: { url -> openWithSystem(context, url) }
    BrandingFooter(styleOverrides, open)
}

@Composable
internal fun BrandingFooter(style: GiovChatStyleOverrides, openUrl: (String) -> Unit) {
    val override = style.branding
    val colors = remember(override) { resolveBranding(override) }
    LaunchedEffect(colors) {
        for (warning in colors.warnings) {
            try {
                Log.w("GiovChat", warning)
            } catch (_: Exception) {
                // No log in this environment.
            }
        }
    }
    val family = override?.fontFamily ?: style.fontFamily
    val text: AnnotatedString = remember(colors.brand) {
        buildAnnotatedString {
            append("Powered by ")
            withStyle(SpanStyle(color = colors.brand, fontWeight = FontWeight.Bold)) { append("GiovChat") }
        }
    }
    val divider = if (colors.customBackground) Color(0x4D808080) else BrandingDivider
    Box(
        Modifier
            .testTag("giov-branding")
            .fillMaxWidth()
            .background(colors.background)
            .topHairline(divider)
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
        BasicText(text, style = TextStyle(color = colors.text, fontSize = 12.sp, fontFamily = family))
    }
}
