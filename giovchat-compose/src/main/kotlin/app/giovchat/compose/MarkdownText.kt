package app.giovchat.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.giovchat.core.markdown.ChatMarkdown
import app.giovchat.core.markdown.InlineRun
import app.giovchat.core.markdown.ListItem
import app.giovchat.core.markdown.MarkdownBlock

/**
 * Inline runs as styled text. Only http(s) and mailto hrefs become tappable link annotations, whatever the text
 * claims; [onOpenLink] receives the href.
 */
internal fun inlineText(lines: List<List<InlineRun>>, codeBackground: Color, onOpenLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            for (run in line) {
                val decorations = buildList {
                    if (run.marks.underline || run.href != null) add(TextDecoration.Underline)
                    if (run.marks.strike) add(TextDecoration.LineThrough)
                }
                val style = SpanStyle(
                    fontWeight = if (run.marks.bold) FontWeight.Bold else null,
                    fontStyle = if (run.marks.italic) FontStyle.Italic else null,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                    fontFamily = if (run.marks.code) FontFamily.Monospace else null,
                    background = if (run.marks.code) codeBackground else Color.Unspecified,
                    fontSize = if (run.marks.code) 13.sp else TextUnit.Unspecified,
                )
                val href = run.href
                if (href != null && ChatMarkdown.isOpenableHref(href)) {
                    withLink(LinkAnnotation.Clickable(tag = href, linkInteractionListener = { onOpenLink(href) })) {
                        withStyle(style) { append(run.text) }
                    }
                } else {
                    withStyle(style) { append(run.text) }
                }
            }
        }
    }

/** A chat message's markdown drawn as native text. */
@Composable
internal fun ChatMarkdownView(
    content: String,
    textStyle: TextStyle,
    accent: Color,
    codeBackground: Color,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(content) { ChatMarkdown.parse(content) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) MarkdownBlockView(block, textStyle, accent, codeBackground, onOpenLink)
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    textStyle: TextStyle,
    accent: Color,
    codeBackground: Color,
    onOpenLink: (String) -> Unit,
) {
    when (block) {
        is MarkdownBlock.Paragraph -> BasicText(inlineText(block.lines, codeBackground, onOpenLink), style = textStyle)
        is MarkdownBlock.Heading -> BasicText(
            inlineText(listOf(block.content), codeBackground, onOpenLink),
            style = textStyle.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.semantics { heading() },
        )
        is MarkdownBlock.Code -> Box(
            Modifier.fillMaxWidth().background(codeBackground, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            BasicText(block.text, style = textStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp))
        }
        MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(accent))
        is MarkdownBlock.Quote -> Box(
            Modifier
                .alpha(0.85f)
                .drawBehind { drawLine(accent, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx()) }
                .padding(start = 11.dp),
        ) {
            BasicText(inlineText(block.lines, codeBackground, onOpenLink), style = textStyle)
        }
        is MarkdownBlock.ListBlock -> MarkdownList(block, textStyle, accent, codeBackground, onOpenLink)
    }
}

@Composable
private fun MarkdownList(
    list: MarkdownBlock.ListBlock,
    textStyle: TextStyle,
    accent: Color,
    codeBackground: Color,
    onOpenLink: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.items.forEachIndexed { index, item: ListItem ->
            Row {
                BasicText(
                    if (list.ordered) "${list.start + index}." else "•",
                    style = textStyle,
                    modifier = Modifier.width(22.dp).clearAndSetSemantics { },
                )
                Column {
                    BasicText(inlineText(item.lines, codeBackground, onOpenLink), style = textStyle)
                    for (child in item.children) MarkdownList(child, textStyle, accent, codeBackground, onOpenLink)
                }
            }
        }
    }
}
