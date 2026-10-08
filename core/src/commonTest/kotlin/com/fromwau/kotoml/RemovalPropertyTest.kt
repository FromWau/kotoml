package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kotoml.parser.ErrorSink
import com.fromwau.kotoml.parser.Event
import com.fromwau.kotoml.parser.EventKind
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.parser.eventReceiver
import com.fromwau.kotoml.parser.parseDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Removing any top-level key from a document with comments, with force, prints the original text with exactly that
 * key's own lines taken out and every other byte unchanged, and the removed item stores with exactly the comments of
 * those lines. Without force, a key whose own lines hold comments is refused with `CommentsWouldBeLost` counting them,
 * changing nothing.
 *
 * A key's own lines are worked out from the parser's events, apart from the document model:
 * - a key/value line before the first header that starts with the key: from the start of the comment lines right
 *   above it, with no blank line between, to the end of the line its value ends on, the comment after it included. A
 *   line right after another of the same key takes the comment and blank lines between them too;
 * - a header whose path starts with the key, and its body: from the start of the comment lines right above the first
 *   such header to the end of the last key/value line under it. The comment and blank lines after that line belong to
 *   what follows;
 * - the comment and blank lines above every such header but the first, which sit inside what the key removes.
 *
 * Where removed lines leave blank lines meeting blank lines, or the end of the file, the blank lines above them go;
 * removed key/value lines that started the file take the blank lines after them; and a file not read starting with a
 * blank line does not print its first table below one once no key/value line stands above it.
 */
class RemovalPropertyTest {

    private val handWritten = listOf(
        "# the port to listen on\nport = 8080\n# bind address\nhost = \"0.0.0.0\"\n",
        "\n# leading\n\na = 1 # one\n\n# about b\nb = [\n  1, # first\n  2,\n] # two\n# the end\n",
        "# about a.x\na.x = 1\n# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n",
        "title = 't'\n\n# about server\n[server]\n# inside\nhost = 'h'\n\n# about client\n[client]\nport = 1\n",
        "# first\n[[x]]\nn = 1\n\n# between\n[y]\n\n# second\n[[x]]\nn = 2\n# trailing\n",
        "# about fruit\n[fruit]\napple.color = 'red'\n# about texture\n[fruit.apple.texture]\nsmooth = true\n",
        "a = 1\r\n# about b\r\nb = 2\r\n\r\n# about t\r\n[t]\r\nc = 3\r\n",
        "x = 1\n# about s\ns = \"\"\"\nmulti # not a comment\n\"\"\"\ny = 2",
    )

    @Test
    fun `removing any top-level key from a hand-written document takes exactly its own lines and comments`() {
        val failures = handWritten.flatMap { text -> failuresOf(text, TomlVersion.V1_1) }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `removing any top-level key from a commented suite file takes exactly its own lines and comments`() {
        val root = "../toml-test/tests/valid"
        val failures = walkFiles(root)
            .filter { it.endsWith(".toml") }
            .flatMap { file ->
                val text = readBytes("$root/$file").decodeToString()
                if ('#' !in text) return@flatMap emptyList()
                val version = TomlVersion.entries.last { TomlDocument.parse(text, it) is Result.Success }
                failuresOf(text, version).map { "$file: $it" }
            }
        assertEquals(emptyList(), failures)
    }

    /** What goes wrong removing each top-level key of [text], with force and without. */
    private fun failuresOf(
        text: String,
        version: TomlVersion,
    ): List<String> {
        val keys = TomlDocument.parse(text, version)
            .assertSuccess()
            .root
            .entries
            .map { it.first }
        return keys.mapNotNull { key ->
            val own = ownLines(text, version, key)
            val bytes = text.encodeToByteArray()
            val ownText = own.joinToString("") { bytes.decodeToString(it.range.first, it.range.last + 1) }
            val ownComments = commentsIn(ownText)
            val refused = TomlDocument.parse(text, version).assertSuccess()
            val unforced = refused.remove(key)
            val document = TomlDocument.parse(text, version).assertSuccess()
            val removed = document.remove(key, force = true).assertSuccess()
            val stored = commentsIn(TomlDocument().also { it.set(key, removed).assertSuccess() }.toString())
            val tablesFirst = document.root.all { (_, item) -> item.printsOwnHeader }
            when {
                !printsWithout(text, document.toString(), own, tablesFirst) -> "$key: removed other lines"
                ownComments.isEmpty() && unforced is Result.Error -> "$key: refused without comments"
                ownComments.isNotEmpty() && (unforced as? Result.Error)?.error != lost(key, ownComments) -> {
                    "$key: gave $unforced"
                }
                ownComments.isNotEmpty() && refused.toString() != text -> "$key: a refused removal changed the document"
                stored.sorted() != ownComments.sorted() -> "$key: the removed item stores with $stored"
                else -> null
            }
        }
    }

    private fun lost(
        key: String,
        comments: List<String>,
    ): TomlEditError = TomlEditError.CommentsWouldBeLost(listOf(key), comments.size)

    /**
     * Whether [printed] is [text] without the byte ranges [removed], with the blank lines removed lines leave meeting
     * others dropped. A document that was read without a final newline keeps none, so when its last line goes, the
     * line break ending the line before it goes too.
     */
    private fun printsWithout(
        text: String,
        printed: String,
        removed: List<OwnLines>,
        tablesFirst: Boolean,
    ): Boolean {
        val bytes = text.encodeToByteArray()
        val kept = StringBuilder()
        val cuts = mutableListOf<Int>()
        var keyLinesStartFile = false
        var index = 0
        while (index < bytes.size) {
            val cut = removed.firstOrNull { index in it.range }
            if (cut != null) {
                cuts += kept.length
                if (!cut.isHeader && kept.isEmpty()) keyLinesStartFile = true
                index = cut.range.last + 1
                continue
            }
            val next = removed
                .map { it.range.first }
                .filter { it > index }
                .minOrNull() ?: bytes.size
            kept.append(bytes.decodeToString(index, next))
            index = next
        }
        for (cut in cuts.distinct().asReversed()) {
            val blankAfter = cut == kept.length || BLANK_LINES_AT_START.containsMatchIn(kept.substring(cut))
            val blankBefore = BLANK_LINES_AT_END.find(kept.substring(0, cut)) ?: continue
            if (blankAfter) kept.deleteRange(cut - blankBefore.groupValues[2].length, cut)
        }
        if (keyLinesStartFile) BLANK_LINES_AT_START.find(kept)?.let { kept.deleteRange(0, it.value.length) }
        val readStartingBlank = text.substringBefore('\n', "x").isBlank()
        val expected = if (readStartingBlank || !tablesFirst) {
            kept.toString()
        } else {
            kept.replace(BLANK_LINES_AT_START, "")
        }
        if (printed == expected) return true
        return !text.endsWith('\n') && expected.removeSuffix("\n").removeSuffix("\r") == printed
    }

    /** One key/value line or header line, as byte offsets, and the top-level key it belongs to. */
    private class Statement(val owner: String, val lineStart: Int, val end: Int, val isHeader: Boolean)

    /** A byte range of a key's own lines, and whether it holds a header. */
    private class OwnLines(val range: IntRange, val isHeader: Boolean)

    /** The byte ranges of [key]'s own lines in [text]. */
    private fun ownLines(
        text: String,
        version: TomlVersion,
        key: String,
    ): List<OwnLines> {
        val statements = statements(text, version)
        val ranges = mutableListOf<OwnLines>()
        var seenHeader = false
        var index = 0
        while (index < statements.size) {
            val statement = statements[index]
            if (!statement.isHeader) {
                val before = statements.getOrNull(index - 1)
                val continues = before != null && !before.isHeader && before.owner == statement.owner
                val floor = before?.end ?: 0
                val start = if (continues) floor else commentLinesAbove(text, statement.lineStart, floor)
                if (statement.owner == key) ranges += OwnLines(start until statement.end, isHeader = false)
                index++
                continue
            }
            var last = index
            while (last + 1 < statements.size && !statements[last + 1].isHeader) last++
            if (statement.owner == key) {
                val start = if (seenHeader) {
                    statements.getOrNull(index - 1)?.end ?: 0
                } else {
                    commentLinesAbove(text, statement.lineStart, floor = statements.getOrNull(index - 1)?.end ?: 0)
                }
                ranges += OwnLines(start until statements[last].end, isHeader = true)
                seenHeader = true
            }
            index = last + 1
        }
        return ranges
    }

    /** Where the comment lines right above the line at [lineStart] start, no blank line between, from [floor] on. */
    private fun commentLinesAbove(
        text: String,
        lineStart: Int,
        floor: Int,
    ): Int {
        val bytes = text.encodeToByteArray()
        var start = lineStart
        while (start > floor) {
            val above = bytes.lastIndexOf('\n'.code.toByte(), start - 1) + 1
            if (!bytes.decodeToString(above, start).trimStart().startsWith("#")) break
            start = above
        }
        return start
    }

    private fun statements(
        text: String,
        version: TomlVersion,
    ): List<Statement> {
        val source = TomlSource(text)
        val bytes = source.bytes
        val events = mutableListOf<Event>()
        parseDocument(source.lex().toList(), eventReceiver { events += it }, ErrorSink.IGNORE)

        fun keyName(event: Event): String {
            val span = event.span
            return Key.parse(bytes.decodeToString(span.start, span.end), version).assertSuccess().name
        }

        val statements = mutableListOf<Statement>()
        var section: String? = null
        var depth = 0
        var start: Event? = null
        var owner: String? = null
        for (event in events) {
            when (event.kind) {
                EventKind.ArrayOpen, EventKind.InlineTableOpen -> depth++
                EventKind.ArrayClose, EventKind.InlineTableClose -> depth--
                else -> {}
            }
            val begins = event.kind in listOf(EventKind.SimpleKey, EventKind.StdTableOpen, EventKind.ArrayTableOpen)
            if (depth == 0 && start == null && begins) {
                start = event
                if (event.kind == EventKind.SimpleKey) owner = section ?: keyName(event)
            } else if (start != null && owner == null && event.kind == EventKind.SimpleKey) {
                owner = keyName(event)
                section = owner
            }
            val ends = event.kind == EventKind.Newline && depth == 0
            if (start != null && (ends || event === events.last())) {
                val lineStart = bytes.lastIndexOf('\n'.code.toByte(), start.span.start) + 1
                val isHeader = start.kind != EventKind.SimpleKey
                statements += Statement(owner.orEmpty(), lineStart, event.span.end, isHeader)
                start = null
                owner = null
            }
        }
        return statements
    }

    private fun ByteArray.lastIndexOf(
        byte: Byte,
        before: Int,
    ): Int {
        for (index in before - 1 downTo 0) if (this[index] == byte) return index
        return -1
    }

    private companion object {
        val BLANK_LINES_AT_START = Regex("^([ \t\r]*\n)+")
        val BLANK_LINES_AT_END = Regex("(^|\n)((?:[ \t\r]*\n)+)$")
    }
}
