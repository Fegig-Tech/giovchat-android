package app.giovchat.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.giovchat.core.markdown.ChatMarkdown
import app.giovchat.core.markdown.MarkdownBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    private fun firstParagraph(markdown: String, open: (String) -> Unit = {}) =
        inlineText((ChatMarkdown.parse(markdown).first() as MarkdownBlock.Paragraph).lines, Color.Gray, open)

    @Test
    fun marksBecomeSpanStyles() {
        val text = firstParagraph("**bold** *it* ~~gone~~ `code`")
        assertEquals("bold it gone code", text.text)
        val spans = text.spanStyles
        assertTrue(spans.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "bold" })
        assertTrue(spans.any { it.item.fontStyle == FontStyle.Italic && text.text.substring(it.start, it.end) == "it" })
        assertTrue(spans.any { it.item.textDecoration == TextDecoration.LineThrough && text.text.substring(it.start, it.end) == "gone" })
        val code = spans.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("code", text.text.substring(code.start, code.end))
        assertEquals(Color.Gray, code.item.background)
    }

    @Test
    fun onlyHttpAndMailtoHrefsBecomeLinks() {
        val opened = mutableListOf<String>()
        val text = firstParagraph(
            "[a](https://x.test/a) [m](mailto:me@x.test) [bad](javascript:alert(1)) [f](file:///etc/passwd) [ftp](ftp://x.test) https://bare.test/z",
            opened::add,
        )
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://x.test/a", "mailto:me@x.test", "https://bare.test/z"), links.map { (it.item as LinkAnnotation.Clickable).tag })
        links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
        assertEquals(listOf("https://x.test/a", "mailto:me@x.test", "https://bare.test/z"), opened)
        assertTrue("unsafe link syntax stays literal text", text.text.contains("[bad](javascript:alert(1))"))
    }

    @Test
    fun linesJoinWithNewlines() {
        assertEquals("one\ntwo", firstParagraph("one\ntwo").text)
    }

    @Test
    fun openGiovLinkChecksTheScheme() {
        val calls = mutableListOf<String>()
        openGiovLink({ calls += it }, "javascript:alert(1)")
        openGiovLink({ calls += it }, "file:///etc/passwd")
        openGiovLink({ calls += it }, null)
        assertTrue(calls.isEmpty())
        openGiovLink({ calls += it }, "HTTPS://example.com")
        openGiovLink({ calls += it }, "mailto:a@b.test")
        assertEquals(2, calls.size)
        openGiovLink({ error("no app") }, "https://x.test") // a link that cannot open is ignored
    }

    @Test
    fun brandColoursParseWithAFallback() {
        assertEquals(Color(0xFF123456), parseBrandColor("#123456"))
        assertEquals(Color(0xFFFFFFFF), parseBrandColor("#fff"))
        assertEquals(Color(0x80123456), parseBrandColor("#80123456"))
        assertEquals(DefaultBrand, parseBrandColor("nonsense"))
        assertEquals(DefaultBrand, parseBrandColor(null))
        assertEquals(DefaultBrand, parseBrandColor("#12345"))
    }

    @Test
    fun imageBoxesReserveTheFinalShape() {
        assertEquals(200 to 150, previewBoxSize(null))
        assertEquals(200 to 150, previewBoxSize(app.giovchat.core.GiovAttachmentPreview(width = 400, height = 300)))
        assertEquals(100 to 150, previewBoxSize(app.giovchat.core.GiovAttachmentPreview(width = 200, height = 300)))
        assertEquals(50 to 40, previewBoxSize(app.giovchat.core.GiovAttachmentPreview(width = 50, height = 40)))
    }

    @Test
    fun preChatValidationFollowsTheRequiredFields() {
        val fields = app.giovchat.core.GiovRequestUserInfo(email = true, firstName = true)
        assertFalse(preChatValid(fields, "", "", "a@b.co", ""))
        assertFalse(preChatValid(fields, "Ada", "", "nope", ""))
        assertTrue(preChatValid(fields, "Ada", "", "a@b.co", ""))
    }
}
