package app.giovchat.core.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every entry of the protocol's markdown conformance vectors (src/test/resources/markdown-vectors.json), normalised
 * as the conformance runner describes: marks sorted, empty runs dropped, neighbours with the same marks and no link
 * merged.
 */
class MarkdownVectorsTest {
    private val vectors: List<JsonObject> by lazy {
        // A byte-for-byte copy of the protocol's conformance/markdown-vectors.json, read from the test classpath.
        val stream = javaClass.getResourceAsStream("/markdown-vectors.json") ?: error("markdown-vectors.json is not on the test classpath")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun everyVectorMatches() {
        assertTrue("the vectors file has more than 40 entries", vectors.size > 40)
        val failures = mutableListOf<String>()
        for (vector in vectors) {
            val input = vector["input"]!!.jsonPrimitive.content
            val actual = normalizeTree(JsonArray(ChatMarkdown.parse(input).map(::blockJson)))
            val expected = normalizeTree(vector["tree"]!!.jsonArray)
            if (actual != expected) failures += "${vector["name"]!!.jsonPrimitive.content}: input=$input\n  expected=$expected\n  actual=$actual"
        }
        assertEquals("markdown vectors failed:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    private fun runJson(run: InlineRun): JsonElement = buildJsonObject {
        put("text", run.text)
        put("marks", buildJsonObject {
            if (run.marks.bold) put("bold", true)
            if (run.marks.italic) put("italic", true)
            if (run.marks.strike) put("strike", true)
            if (run.marks.underline) put("underline", true)
            if (run.marks.code) put("code", true)
        })
        if (run.href != null) put("href", run.href)
    }

    private fun lineJson(line: List<InlineRun>) = JsonArray(line.map(::runJson))

    private fun blockJson(block: MarkdownBlock): JsonElement = when (block) {
        is MarkdownBlock.Paragraph -> buildJsonObject { put("type", "paragraph"); put("lines", JsonArray(block.lines.map(::lineJson))) }
        is MarkdownBlock.Quote -> buildJsonObject { put("type", "quote"); put("lines", JsonArray(block.lines.map(::lineJson))) }
        is MarkdownBlock.Heading -> buildJsonObject { put("type", "heading"); put("content", lineJson(block.content)) }
        is MarkdownBlock.Code -> buildJsonObject { put("type", "code"); put("text", block.text) }
        MarkdownBlock.Rule -> buildJsonObject { put("type", "rule") }
        is MarkdownBlock.ListBlock -> buildJsonObject {
            put("type", "list")
            put("ordered", block.ordered)
            put("start", block.start)
            put("items", JsonArray(block.items.map { item ->
                buildJsonObject {
                    put("lines", JsonArray(item.lines.map(::lineJson)))
                    put("children", JsonArray(item.children.map(::blockJson)))
                }
            }))
        }
    }

    private fun normalizeLine(runs: JsonArray): JsonArray {
        val out = mutableListOf<JsonObject>()
        for (element in runs) {
            val run = element.jsonObject
            val text = run["text"]!!.jsonPrimitive.content
            if (text.isEmpty()) continue
            val marks = (run["marks"] as? JsonObject).orEmpty().filter { (_, value) -> value.jsonPrimitive.booleanOrNull == true }
            val sortedMarks = JsonObject(marks.toSortedMap())
            val href = (run["href"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotEmpty() }?.content
            val last = out.lastOrNull()
            if (last != null && last["href"] == null && href == null && last["marks"] == sortedMarks) {
                out[out.size - 1] = JsonObject(last + ("text" to JsonPrimitive(last["text"]!!.jsonPrimitive.content + text)))
            } else {
                out += JsonObject(buildMap {
                    put("text", JsonPrimitive(text))
                    put("marks", sortedMarks)
                    if (href != null) put("href", JsonPrimitive(href))
                })
            }
        }
        return JsonArray(out)
    }

    private fun normalizeTree(blocks: JsonArray): JsonArray = buildJsonArray {
        for (element in blocks) {
            val block = element.jsonObject
            when (val type = block["type"]!!.jsonPrimitive.content) {
                "paragraph", "quote" -> add(buildJsonObject {
                    put("type", type)
                    put("lines", JsonArray(block["lines"]!!.jsonArray.map { normalizeLine(it.jsonArray) }))
                })
                "heading" -> add(buildJsonObject { put("type", "heading"); put("content", normalizeLine(block["content"]!!.jsonArray)) })
                "code" -> add(buildJsonObject { put("type", "code"); put("text", block["text"]!!.jsonPrimitive.content) })
                "rule" -> add(buildJsonObject { put("type", "rule") })
                "list" -> {
                    val ordered = block["ordered"]?.jsonPrimitive?.boolean == true
                    add(buildJsonObject {
                        put("type", "list")
                        put("ordered", ordered)
                        put("start", if (ordered) block["start"]?.jsonPrimitive?.int ?: 1 else 1)
                        put("items", JsonArray(block["items"]!!.jsonArray.map { item ->
                            buildJsonObject {
                                put("lines", JsonArray(item.jsonObject["lines"]!!.jsonArray.map { normalizeLine(it.jsonArray) }))
                                put("children", normalizeTree((item.jsonObject["children"] as? JsonArray) ?: JsonArray(emptyList())))
                            }
                        }))
                    })
                }
                else -> error("unknown block $type")
            }
        }
    }
}
