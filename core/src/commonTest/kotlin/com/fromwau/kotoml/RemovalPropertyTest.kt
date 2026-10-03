package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.ErrorSink
import com.fromwau.kotoml.parser.Event
import com.fromwau.kotoml.parser.EventKind
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.parser.eventReceiver
import com.fromwau.kotoml.parser.parseDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Removing any top-level key from a document with comments prints the original text with exactly that key's own
 * lines taken out and every other byte unchanged.
 *
 * A key's own lines are worked out from the parser's events, apart from the document model:
 * - a key/value line before the first header that starts with the key: from the start of the line its key is on
 *   to the end of the line its value ends on, the comment after it included;
 * - a header whose path starts with the key, and its body: from the start of the header's line to the end of
 *   the last key/value line under it. The comment and blank lines after that line belong to what follows;
 * - the comment and blank lines above every such header but the first, which sit inside what the key removes.
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
    fun `removing any top-level key from a hand-written document removes only its own lines`() {
        val failures = handWritten.flatMap { text -> failuresOf(text, TomlVersion.V1_1) }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `removing any top-level key from a commented suite file removes only its own lines`() {
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

    /** The top-level keys whose removal from [text] prints anything but the text without their own lines. */
    private fun failuresOf(
        text: String,
        version: TomlVersion,
    ): List<String> {
        val keys = TomlDocument.parse(text, version)
            .unwrap()
            .root
            .items()
            .map { it.first }
        return keys.filter { key ->
            val document = TomlDocument.parse(text, version).unwrap()
            document.remove(key)
            !printsWithout(text, document.toString(), ownLines(text, version, key))
        }
    }

    /**
     * Whether [printed] is [text] without the byte ranges [removed]. A document that was read without a final
     * newline ends with one once it is changed, so that newline is allowed.
     */
    private fun printsWithout(
        text: String,
        printed: String,
        removed: List<IntRange>,
    ): Boolean {
        val bytes = text.encodeToByteArray()
        val expected = bytes
            .filterIndexed { index, _ -> removed.none { index in it } }
            .toByteArray()
            .decodeToString()
        if (printed == expected) return true
        return !expected.endsWith('\n') && printed.removeSuffix("\n").removeSuffix("\r") == expected
    }

    /** One key/value line or header line, as byte offsets, and the top-level key it belongs to. */
    private class Statement(val owner: String, val lineStart: Int, val end: Int, val isHeader: Boolean)

    /** The byte ranges of [key]'s own lines in [text]. */
    private fun ownLines(
        text: String,
        version: TomlVersion,
        key: String,
    ): List<IntRange> {
        val statements = statements(text, version)
        val ranges = mutableListOf<IntRange>()
        var seenHeader = false
        var index = 0
        while (index < statements.size) {
            val statement = statements[index]
            if (!statement.isHeader) {
                if (statement.owner == key) ranges += statement.lineStart until statement.end
                index++
                continue
            }
            var last = index
            while (last + 1 < statements.size && !statements[last + 1].isHeader) last++
            if (statement.owner == key) {
                val start = if (seenHeader) statements.getOrNull(index - 1)?.end ?: 0 else statement.lineStart
                ranges += start until statements[last].end
                seenHeader = true
            }
            index = last + 1
        }
        return ranges
    }

    private fun statements(
        text: String,
        version: TomlVersion,
    ): List<Statement> {
        val source = TomlSource(text)
        val bytes = source.bytes
        val events = mutableListOf<Event>()
        parseDocument(source.lex().intoVec(), eventReceiver { events += it }, ErrorSink.IGNORE)

        fun keyName(event: Event): String {
            val span = event.span()
            return Key.parse(bytes.decodeToString(span.start, span.end), version).unwrap().name
        }

        val statements = mutableListOf<Statement>()
        var section: String? = null
        var depth = 0
        var start: Event? = null
        var owner: String? = null
        for (event in events) {
            when (event.kind()) {
                EventKind.ArrayOpen, EventKind.InlineTableOpen -> depth++
                EventKind.ArrayClose, EventKind.InlineTableClose -> depth--
                else -> {}
            }
            val begins = event.kind() in listOf(EventKind.SimpleKey, EventKind.StdTableOpen, EventKind.ArrayTableOpen)
            if (depth == 0 && start == null && begins) {
                start = event
                if (event.kind() == EventKind.SimpleKey) owner = section ?: keyName(event)
            } else if (start != null && owner == null && event.kind() == EventKind.SimpleKey) {
                owner = keyName(event)
                section = owner
            }
            val ends = event.kind() == EventKind.Newline && depth == 0
            if (start != null && (ends || event === events.last())) {
                val lineStart = bytes.lastIndexOf('\n'.code.toByte(), start.span().start) + 1
                val isHeader = start.kind() != EventKind.SimpleKey
                statements += Statement(owner.orEmpty(), lineStart, event.span().end, isHeader)
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
}
