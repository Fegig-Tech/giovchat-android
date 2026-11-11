package app.giovchat.core.markdown

/**
 * Chat markdown, the grammar of the web widget's formatMessage (docs/protocol/README.md, "Markdown"),
 * parsed into a renderer-neutral tree. Paragraphs and line breaks, **bold**, *italic* or _italic_,
 * ~~strike~~, <u>underline</u>, `code`, fenced code, # headings, - / * / + and 1. lists (one nested
 * level), > quotes, --- rules, [links](https://…) and bare links. Only http(s) and mailto links get an
 * href. Unfinished markup (while a reply streams in) stays text until it is closed. All other text is
 * literal. Ported from the agent app's lib/chat-markdown.ts; docs/protocol/conformance/markdown-vectors.json
 * is the contract.
 */
public data class Marks(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val code: Boolean = false,
)

public data class InlineRun(val text: String, val marks: Marks = Marks(), val href: String? = null)

public sealed interface MarkdownBlock {
    public data class Paragraph(val lines: List<List<InlineRun>>) : MarkdownBlock
    public data class Heading(val content: List<InlineRun>) : MarkdownBlock
    public data class Quote(val lines: List<List<InlineRun>>) : MarkdownBlock
    public data class Code(val text: String) : MarkdownBlock
    public data object Rule : MarkdownBlock
    public data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MarkdownBlock
}

public data class ListItem(val lines: List<List<InlineRun>>, val children: List<MarkdownBlock.ListBlock>)

public object ChatMarkdown {
    // JavaScript's \s also matches Unicode spaces; spell them out so lines split the same way as the widget.
    private const val WS = "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
    private const val S = "[$WS]"
    private const val NS = "[^$WS]"

    // Placeholders and mark delimiters are control characters that never survive in chat text, and none
    // is a word character, so the italic rules see the same neighbours as in the widget's HTML.
    private const val CODE = '\u0000'
    private const val LINK = '\u0001'
    private const val BOLD_OPEN = '\u0010'
    private const val BOLD_CLOSE = '\u0011'
    private const val ITALIC_OPEN = '\u0012'
    private const val ITALIC_CLOSE = '\u0013'
    private const val STRIKE_OPEN = '\u0014'
    private const val STRIKE_CLOSE = '\u0015'
    private const val UNDERLINE_OPEN = '\u0016'
    private const val UNDERLINE_CLOSE = '\u0017'

    private val reserved = Regex("[\\u0000\\u0001\\u0010-\\u0017]")
    private val codeSpan = Regex("`([^`\\n]+)`")
    private val markdownLink = Regex("\\[([^\\]\\n]+)\\]\\(((?:https?://|mailto:)[^$WS)]+)\\)", RegexOption.IGNORE_CASE)
    private val bareLink = Regex("(^|[$WS(])((?:https?://)[^$WS<]+[^$WS<.,;:!?)'\"&])", RegexOption.IGNORE_CASE)
    private val boldStars = Regex("\\*\\*(?=$NS)([^*]+?$NS)\\*\\*")
    private val boldUnderscores = Regex("__(?=$NS)([^_]+?$NS)__")
    private val italicStar = Regex("(^|[^*\\w])\\*(?=$NS)([^*\\n]+?$NS)\\*(?![*\\w])")
    private val italicUnderscore = Regex("(^|[^_\\w])_(?=$NS)([^_\\n]+?$NS)_(?![_\\w])")
    private val strikeRe = Regex("~~(?=$NS)([^~]+?$NS)~~")
    private val underlineRe = Regex("<u>([\\s\\S]+?)</u>", RegexOption.IGNORE_CASE)

    private val fence = Regex("^$S*```")
    private val listStart = Regex("^$S*(?:[-*+]|\\d+[.)])$S+")
    private val headingRe = Regex("^(#{1,6})$S+(.*)$")
    private val ruleRe = Regex("^$S*(?:-{3,}|\\*{3,}|_{3,})$S*$")
    private val quoteRe = Regex("^>$S?(.*)$")
    private val itemRe = Regex("^($S*)([-*+]|\\d+[.)])$S+(.*)$")
    private val continuation = Regex("^$S+$NS")
    private val openable = Regex("^(?:https?://|mailto:)", RegexOption.IGNORE_CASE)

    /** Links are opened only for these schemes, whatever the text claims. */
    public fun isOpenableHref(href: String?): Boolean = href != null && openable.containsMatchIn(href)

    /** Inline markup on one line, in the widget's order: code spans and links are cut out first. */
    public fun parseInline(text: String): List<InlineRun> {
        val codes = mutableListOf<String>()
        var out = reserved.replace(text, "")
        out = codeSpan.replace(out) { match ->
            codes += match.groupValues[1]
            "$CODE${codes.size - 1}$CODE"
        }
        val links = mutableListOf<Pair<String, String>>()
        fun keep(label: String, href: String): String {
            links += label to href
            return "$LINK${links.size - 1}$LINK"
        }
        out = markdownLink.replace(out) { match -> keep(match.groupValues[1], match.groupValues[2]) }
        out = bareLink.replace(out) { match -> match.groupValues[1] + keep(match.groupValues[2], match.groupValues[2]) }
        out = boldStars.replace(out) { "$BOLD_OPEN${it.groupValues[1]}$BOLD_CLOSE" }
        out = boldUnderscores.replace(out) { "$BOLD_OPEN${it.groupValues[1]}$BOLD_CLOSE" }
        out = italicStar.replace(out) { it.groupValues[1] + "$ITALIC_OPEN${it.groupValues[2]}$ITALIC_CLOSE" }
        out = italicUnderscore.replace(out) { it.groupValues[1] + "$ITALIC_OPEN${it.groupValues[2]}$ITALIC_CLOSE" }
        out = strikeRe.replace(out) { "$STRIKE_OPEN${it.groupValues[1]}$STRIKE_CLOSE" }
        out = underlineRe.replace(out) { "$UNDERLINE_OPEN${it.groupValues[1]}$UNDERLINE_CLOSE" }

        val runs = mutableListOf<InlineRun>()
        var bold = 0
        var italic = 0
        var strike = 0
        var underline = 0
        fun current() = Marks(bold = bold > 0, italic = italic > 0, strike = strike > 0, underline = underline > 0)
        fun push(run: InlineRun) {
            if (run.text.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && last.href == null && run.href == null && last.marks == run.marks) {
                runs[runs.size - 1] = last.copy(text = last.text + run.text)
            } else {
                runs += run
            }
        }
        val buffer = StringBuilder()
        fun flush() {
            push(InlineRun(buffer.toString(), current()))
            buffer.setLength(0)
        }
        var index = 0
        while (index < out.length) {
            when (val char = out[index]) {
                BOLD_OPEN -> { flush(); bold++ }
                BOLD_CLOSE -> { flush(); bold-- }
                ITALIC_OPEN -> { flush(); italic++ }
                ITALIC_CLOSE -> { flush(); italic-- }
                STRIKE_OPEN -> { flush(); strike++ }
                STRIKE_CLOSE -> { flush(); strike-- }
                UNDERLINE_OPEN -> { flush(); underline++ }
                UNDERLINE_CLOSE -> { flush(); underline-- }
                CODE, LINK -> {
                    val end = out.indexOf(char, index + 1)
                    val slot = out.substring(index + 1, end).toInt()
                    flush()
                    if (char == CODE) {
                        push(InlineRun(codes[slot], current().copy(code = true)))
                    } else {
                        val (label, href) = links[slot]
                        push(InlineRun(label, current(), href))
                    }
                    index = end
                }
                else -> buffer.append(char)
            }
            index++
        }
        flush()
        return runs
    }

    private class OpenList(val ordered: Boolean, val start: Int, val indent: Int) {
        val items = mutableListOf<OpenItem>()
        fun build(): MarkdownBlock.ListBlock = MarkdownBlock.ListBlock(ordered, start, items.map { it.build() })
    }

    private class OpenItem(firstLine: List<InlineRun>) {
        val lines = mutableListOf(firstLine)
        val children = mutableListOf<OpenList>()
        fun build(): ListItem = ListItem(lines.toList(), children.map { it.build() })
    }

    /** Parses one message into blocks; an empty or blank message has none. */
    public fun parse(content: String?): List<MarkdownBlock> {
        if (content.isNullOrEmpty()) return emptyList()
        val lines = content.replace(Regex("\\r\\n?"), "\n").split("\n")
        // Blocks in order; a top-level list stays open (and mutable) until the document is built.
        val blocks = mutableListOf<Any>()
        var paragraph = mutableListOf<String>()
        var quote = mutableListOf<String>()
        // Open lists, outermost first; one nested level, as in the widget.
        val lists = mutableListOf<OpenList>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(paragraph.map(::parseInline))
            paragraph = mutableListOf()
        }
        fun closeLists(toIndent: Int = -1) {
            while (lists.isNotEmpty() && lists.last().indent > toIndent) lists.removeAt(lists.size - 1)
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) blocks += MarkdownBlock.Quote(quote.map(::parseInline))
            quote = mutableListOf()
        }
        fun flushAll() {
            flushParagraph()
            closeLists()
            flushQuote()
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            // Fenced code: everything up to the closing fence (or the end, while streaming) is literal.
            if (fence.containsMatchIn(line)) {
                flushAll()
                val body = mutableListOf<String>()
                while (++index < lines.size && !fence.containsMatchIn(lines[index])) body += lines[index]
                blocks += MarkdownBlock.Code(body.joinToString("\n"))
                index++
                continue
            }
            if (line.trim().isEmpty()) {
                flushParagraph()
                flushQuote()
                // A blank line between list items keeps the list going.
                if (lists.isNotEmpty() && !listStart.containsMatchIn(lines.getOrElse(index + 1) { "" })) closeLists()
                index++
                continue
            }
            val heading = headingRe.find(line)
            if (heading != null) {
                flushAll()
                blocks += MarkdownBlock.Heading(parseInline(heading.groupValues[2]))
                index++
                continue
            }
            if (ruleRe.containsMatchIn(line)) {
                flushAll()
                blocks += MarkdownBlock.Rule
                index++
                continue
            }
            val quoted = quoteRe.find(line)
            if (quoted != null) {
                flushParagraph()
                closeLists()
                quote += quoted.groupValues[1]
                index++
                continue
            }
            flushQuote()
            val item = itemRe.find(line)
            if (item != null) {
                flushParagraph()
                val indent = item.groupValues[1].replace("\t", "  ").length
                val marker = item.groupValues[2]
                val ordered = marker.any(Char::isDigit)
                val entry = OpenItem(parseInline(item.groupValues[3]))
                closeLists(indent)
                val top = lists.lastOrNull()
                if (top != null && top.indent == indent && top.ordered == ordered) {
                    top.items += entry
                } else {
                    if (top != null && top.indent == indent) closeLists(indent - 1)
                    val parent = lists.lastOrNull()
                    // Only one nested level: deeper indentation joins the nested list.
                    val nested = parent != null && indent > parent.indent
                    if (lists.size >= 2 && nested) {
                        parent!!.items += entry
                        index++
                        continue
                    }
                    val start = if (ordered) marker.takeWhile(Char::isDigit).toIntOrNull()?.takeIf { it != 0 } ?: 1 else 1
                    val list = OpenList(ordered, start, indent)
                    list.items += entry
                    if (nested) parent!!.items.last().children += list else blocks += list
                    lists += list
                }
                index++
                continue
            }
            // A line indented under a list item continues that item.
            if (lists.isNotEmpty() && continuation.containsMatchIn(line)) {
                lists.last().items.last().lines += parseInline(line.trim())
                index++
                continue
            }
            closeLists()
            paragraph += line
            index++
        }
        flushAll()
        return blocks.map { if (it is OpenList) it.build() else it as MarkdownBlock }
    }
}
