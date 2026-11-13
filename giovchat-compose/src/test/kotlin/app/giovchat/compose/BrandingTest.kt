package app.giovchat.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.giovchat.core.GiovChatPhase
import app.giovchat.core.GiovWidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class BrandingRulesTest {
    @Test
    fun noOverridesKeepTheDefaults() {
        val colors = resolveBranding(null)
        assertEquals(BrandingDefaultText, colors.text)
        assertEquals(BrandingDefaultBrand, colors.brand)
        assertEquals(Color.White, colors.background)
        assertFalse(colors.customBackground)
        assertTrue(colors.warnings.isEmpty())
    }

    @Test
    fun readableOverridesAreApplied() {
        val dark = Color(0xFF111827)
        val colors = resolveBranding(GiovBrandingOverride(color = Color(0xFFF9FAFB), brandColor = Color(0xFFFBBF24), backgroundColor = dark))
        assertEquals(dark, colors.background)
        assertTrue(colors.customBackground)
        assertEquals(Color(0xFFF9FAFB), colors.text)
        assertEquals(Color(0xFFFBBF24), colors.brand)
        assertTrue(colors.warnings.isEmpty())
    }

    @Test
    fun lowContrastFallsBackToTheDefaultAndWarns() {
        val colors = resolveBranding(GiovBrandingOverride(color = Color(0xFFDDDDDD), brandColor = Color(0xFFFFFF00)))
        assertEquals(BrandingDefaultText, colors.text)
        assertEquals(BrandingDefaultBrand, colors.brand)
        assertEquals(2, colors.warnings.size)
    }

    @Test
    fun whenTheDefaultAlsoFailsTheTextIsBlackOrWhite() {
        val background = Color(0xFF0A0A0A)
        val colors = resolveBranding(GiovBrandingOverride(color = Color(0xFF222222), backgroundColor = background))
        assertEquals(readableOn(background), colors.text)
        assertEquals(readableOn(background), colors.brand)
        assertTrue(contrastRatio(colors.text, colors.background) >= 4.5f)
    }

    @Test
    fun alphaIsIgnored() {
        val translucentBackground = resolveBranding(GiovBrandingOverride(backgroundColor = Color(0x80000000)))
        assertEquals(Color.White, translucentBackground.background)
        assertFalse(translucentBackground.customBackground)
        assertEquals(1, translucentBackground.warnings.size)
        val translucentText = resolveBranding(GiovBrandingOverride(color = Color(0xE6000000), brandColor = Color(0x33000000)))
        assertEquals(BrandingDefaultText, translucentText.text)
        assertEquals(BrandingDefaultBrand, translucentText.brand)
    }

    @Test
    fun theAAThresholdIsExact() {
        assertEquals("4.54:1 passes", Color(0xFF767676), resolveBranding(GiovBrandingOverride(color = Color(0xFF767676))).text)
        assertEquals("4.48:1 fails", BrandingDefaultText, resolveBranding(GiovBrandingOverride(color = Color(0xFF777777))).text)
    }

    @Test
    fun theFooterIsHiddenOnlyWhenTheServerSaysSo() {
        fun config(show: Boolean) = GiovWidgetConfig(title = "A", showBranding = show)
        assertTrue(shouldShowBranding(null, config(true), GiovChatPhase.ACTIVE))
        assertFalse(shouldShowBranding(null, config(false), GiovChatPhase.ACTIVE))
        assertFalse("nothing while the configuration loads", shouldShowBranding(null, null, GiovChatPhase.LOADING))
        assertTrue("a failed configuration keeps it", shouldShowBranding(null, null, GiovChatPhase.ERROR))
        assertTrue("an explicit value wins", shouldShowBranding(true, config(false), GiovChatPhase.ACTIVE))
        assertFalse(shouldShowBranding(false, config(true), GiovChatPhase.ACTIVE))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class BrandingComponentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theComponentRendersOpensTheLinkAndOmitsItWhenAskedTo() {
        val opened = mutableListOf<String>()
        rule.setContent { GiovBranding(showBranding = true, onOpenUrl = { opened += it }) }
        rule.onNodeWithTag("giov-branding").assertIsDisplayed()
        rule.onNodeWithTag("giov-branding").performClick()
        assertEquals(1, opened.size)
        assertTrue(opened.single().startsWith("https://giovchat.app/"))
    }

    @Test
    fun explicitFalseRendersNothing() {
        rule.setContent { GiovBranding(showBranding = false) }
        rule.onAllNodesWithTag("giov-branding").assertCountEquals(0)
    }

    @Test
    fun withoutAControllerOrAValueNothingIsDrawn() {
        rule.setContent { GiovBranding() }
        rule.onAllNodesWithTag("giov-branding").assertCountEquals(0)
    }
}
