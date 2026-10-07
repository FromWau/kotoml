package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A layout change is a call returning a result: without force it keeps every comment or is refused with
 * [TomlEditError.CommentsWouldBeLost], counting exactly the comments forcing it drops, and changes nothing.
 */
class LayoutChangeTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    // The texts here hold no `#` but in comments.
    private fun commentCount(text: String): Int = text.count { it == '#' }

    /**
     * Checks that [change] without force is refused for [keyPath] with the number of comments that forcing it then
     * drops, leaving the text as it was until forced.
     */
    private fun assertRefusedThenForced(
        text: String,
        keyPath: List<String>,
        version: TomlVersion = TomlVersion.V1_1,
        change: (TomlDocument, Boolean) -> EmptyResult<TomlEditError>,
    ): String {
        val doc = parse(text, version)
        val error = change(doc, false).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(text, doc.toString())
        change(doc, true).assertSuccess()
        val after = doc.toString()
        assertEquals(TomlEditError.CommentsWouldBeLost(keyPath, commentCount(text) - commentCount(after)), error)
        return after
    }

    @Test
    fun `a table made dotted counts its header comment and the comment lines inside`() {
        val text = "# above\n[t] # header\n# inside\na = 1 # a\n# also inside\nb = 2\n"
        val after = assertRefusedThenForced(text, listOf("t")) { doc, force ->
            doc["t"].assertSuccess().setLayout(TableLayout.Dotted, force)
        }
        assertEquals("# above\nt.a = 1 # a\nt.b = 2\n", after)
    }

    @Test
    fun `a nested table made dotted names its whole key path`() {
        assertRefusedThenForced("[a.b] # b\nc = 1\n", listOf("a", "b")) { doc, force ->
            doc["a"]["b"].assertSuccess().setLayout(TableLayout.Dotted, force)
        }
    }

    @Test
    fun `an inline table across lines made dotted counts the comments in its braces and after them`() {
        val text = "t = { # open\n  # above a\n  a = 1, # a\n  # end\n} # close\n"
        assertRefusedThenForced(text, listOf("t")) { doc, force ->
            doc["t"].assertSuccess().setLayout(TableLayout.Dotted, force)
        }
    }

    @Test
    fun `an inline table in an array put on one line names the array's key`() {
        val text = "a = [\n  { # open\n    x = 1, # x\n  },\n]\n"
        assertRefusedThenForced(text, listOf("a")) { doc, force ->
            doc["a"][0].assertSuccess().setLayout(TableLayout.Inline, force)
        }
    }

    @Test
    fun `a table made inline in a TOML 1_0 document counts every comment its one line drops`() {
        val text = "[t] # header\n# inside\na = 1 # a\n\n[t.sub] # sub\nb = 2 # b\n"
        val after = assertRefusedThenForced(text, listOf("t"), TomlVersion.V1_0) { doc, force ->
            doc["t"].assertSuccess().setLayout(TableLayout.Inline, force)
        }
        assertEquals("t = { a = 1, sub = { b = 2 } } # header\n", after)
    }

    @Test
    fun `a table with comments made inline across lines in a TOML 1_1 document keeps them all`() {
        val text = "# above\n[t] # header\n# inside\na = 1 # a\n\n[t.sub] # sub\nb = 2 # b\n"
        val doc = parse(text)
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals(commentCount(text), commentCount(doc.toString()))
        assertEquals(TableLayout.MultilineInline, doc["t"].assertSuccess().layout)
    }

    @Test
    fun `a change that keeps every comment needs no force`() {
        val doc = parse("[t]\na = 1 # a\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals("t.a = 1 # a\n", doc.toString())
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t]\na = 1 # a\n", doc.toString())
    }

    @Test
    fun `a refused change leaves the layout as it was`() {
        val doc = parse("t = { # open\n  a = 1, # a\n}\n")
        val slot = doc["t"].assertSuccess()
        slot.setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TableLayout.MultilineInline, slot.layout)
        slot.setLayout(TableLayout.Dotted).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TableLayout.MultilineInline, slot.layout)
    }

    @Test
    fun `asking for the layout a table has changes nothing and succeeds`() {
        val text = "[t] # header\na = 1\nu = { # open\n  b = 2,\n}\n"
        val doc = parse(text)
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        doc["t"]["u"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a slot reads the layout of the table it holds`() {
        val doc = parse("a = { x = 1 }\nb.c = 1\nd = [{ x = 1 }, { x = 2 }]\ne = []\nf = [{ x = 1 }, 2]\ng = 1\n" +
            "[h]\n[[i]]\n")
        assertEquals(
            mapOf(
                "a" to TableLayout.Inline,
                "b" to TableLayout.Dotted,
                "d" to TableLayout.Inline,
                "e" to null,
                "f" to null,
                "g" to null,
                "h" to TableLayout.Header,
                "i" to TableLayout.Header,
            ),
            doc.keys.associateWith { doc[it].assertSuccess().layout },
        )
    }

    @Test
    fun `layout reads what setLayout changes`() {
        val doc = parse("[t]\na = 1\n[[s]]\nb = 2\n")
        val t = doc["t"].assertSuccess()
        val s = doc["s"].assertSuccess()
        t.setLayout(TableLayout.Inline).assertSuccess()
        s.setLayout(TableLayout.Inline).assertSuccess()
        assertEquals(TableLayout.Inline, t.layout)
        assertEquals(TableLayout.Inline, s.layout)
        t.setLayout(TableLayout.Header).assertSuccess()
        s.setLayout(TableLayout.Header).assertSuccess()
        assertEquals(TableLayout.Header, t.layout)
        assertEquals(TableLayout.Header, s.layout)
        assertEquals("[t]\na = 1\n\n[[s]]\nb = 2\n", doc.toString())
    }

    @Test
    fun `an inline table written as dotted keys in a slot reads dotted`() {
        val doc = parse("t = { a.b = 1 }\n")
        val inner = doc["t"]["a"].assertSuccess()
        assertEquals(TableLayout.Dotted, inner.layout)
    }

    @Test
    fun `setImplicit hides and shows a header`() {
        val doc = parse("[a]\n[a.b]\nc = 1\n")
        val table = doc["a"].assertSuccess().standardTableOrNull!!
        table.setImplicit(true)
        assertTrue(table.isImplicit)
        assertEquals("[a.b]\nc = 1\n", doc.toString())
        table.setImplicit(false)
        assertEquals("[a]\n[a.b]\nc = 1\n", doc.toString())
    }

    // Removing from a dotted table in braces keeps the comment lines above the removed key in that table.
    private fun removedThenMadeHeader(
        text: String,
        dottedKey: String,
        removed: String,
    ): TomlDocument {
        val doc = parse(text)
        doc["t"][dottedKey].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove(removed)
        doc["t"].assertSuccess().setLayout(TableLayout.Header, force = true).assertSuccess()
        return doc
    }

    @Test
    fun `a comment kept above an emptied dotted table prints on its own line above it under a header`() {
        val doc = removedThenMadeHeader("t = {\n  # about c\n  b.c = 1,\n}\n", "b", "c")
        assertEquals("[t]\n# about c\nb = {}\n", doc.toString())
    }

    @Test
    fun `a comment kept above an emptied dotted table stays above it with a key after it under a header`() {
        val doc = removedThenMadeHeader("t = {\n  # about c\n  b.c = 1,\n  d = 2,\n}\n", "b", "c")
        assertEquals("[t]\n# about c\nb = {}\nd = 2\n", doc.toString())
    }

    @Test
    fun `a comment kept below the last line of a dotted table ends the table on a line of its own under a header`() {
        val doc = removedThenMadeHeader("t = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n", "b", "d")
        assertEquals("[t]\nb.c = 1\n# about d\n", doc.toString())
    }

    @Test
    fun `a comment kept in a dotted table prints on its own CRLF line under a header`() {
        val doc = removedThenMadeHeader("t = {\r\n  # about c\r\n  b.c = 1,\r\n}\r\n", "b", "c")
        assertEquals("[t]\r\n# about c\r\nb = {}\r\n", doc.toString())
    }

    @Test
    fun `the comment after the value before a removed dotted key ends that value's line under a header`() {
        val doc = removedThenMadeHeader("t = {\n  x = 0, # on x\n  # about c\n  b.c = 1,\n}\n", "b", "c")
        assertEquals("[t]\nx = 0 # on x\n# about c\nb = {}\n", doc.toString())
    }

    @Test
    fun `a comment kept in a dotted table in an array of inline tables prints on its own line under a header`() {
        val doc = parse("a = [\n  {\n    x = 0,\n    # about c\n    b.c = 1,\n  },\n]\n")
        doc["a"][0]["b"].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove("c")
        doc["a"].assertSuccess().setLayout(TableLayout.Header, force = true).assertSuccess()
        assertEquals("[[a]]\nx = 0\n# about c\nb = {}\n", doc.toString())
    }

    @Test
    fun `a comment above a later dotted key in braces stays above that key under a header`() {
        val doc = parse("t = {\n  x = 0,\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t]\nx = 0\nb.c = 1\n# about d\nb.d = 2\n", doc.toString())
    }

    @Test
    fun `a comment above a dotted key in braces stays above its emptied table when made a header first`() {
        val doc = madeHeaderThenRemoved("t = {\n  # about c\n  b.c = 1,\n  d = 2,\n}\n", listOf("b"), "c")
        assertEquals("[t]\n# about c\nb = {}\nd = 2\n", doc.toString())
    }

    @Test
    fun `a CRLF comment above a later dotted key in braces stays above that key under a header`() {
        val doc = parse("t = {\r\n  b.c = 1,\r\n  # about d\r\n  b.d = 2,\r\n}\r\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t]\r\nb.c = 1\r\n# about d\r\nb.d = 2\r\n", doc.toString())
    }

    @Test
    fun `a comment after a value under a dotted key in braces prints once under a header`() {
        val doc = parse("t = {\n  b.c = 1, # on c\n  b.d = 2,\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t]\nb.c = 1 # on c\nb.d = 2\n", doc.toString())
    }

    // Makes `t` a header, then removes [removed] from the dotted table at [dottedPath] under it.
    private fun madeHeaderThenRemoved(
        text: String,
        dottedPath: List<String>,
        removed: String,
    ): TomlDocument {
        val doc = parse(text)
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        val dotted = dottedPath.fold(doc["t"].assertSuccess()) { item, key -> item[key].assertSuccess() }
        dotted.valueOrNull!!.inlineTableOrNull!!.remove(removed)
        return doc
    }

    @Test
    fun `a comment above a dotted key in braces stays above the next line when the key before is removed`() {
        val doc = madeHeaderThenRemoved("t = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n", listOf("b"), "c")
        assertEquals("[t]\n# about d\nb.d = 2\n", doc.toString())
    }

    @Test
    fun `a comment above a removed dotted key in braces ends the dotted table's lines under a header`() {
        val doc = madeHeaderThenRemoved("t = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n", listOf("b"), "d")
        assertEquals("[t]\nb.c = 1\n# about d\n", doc.toString())
    }

    @Test
    fun `comments above every removed key of a dotted table stay above its emptied line under a header`() {
        val doc = parse("t = {\n  # about c\n  b.c = 1,\n  # about d\n  b.d = 2,\n  x = 0,\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        val b = doc["t"]["b"].assertSuccess().valueOrNull!!.inlineTableOrNull!!
        b.remove("c")
        b.remove("d")
        assertEquals("[t]\n# about c\n# about d\nb = {}\nx = 0\n", doc.toString())
    }

    @Test
    fun `a comment above the key of a nested dotted table stays above that table once emptied under a header`() {
        val doc = madeHeaderThenRemoved("t = {\n  b.c = 1,\n  # about e\n  b.d.e = 2,\n}\n", listOf("b", "d"), "e")
        assertEquals("[t]\nb.c = 1\n# about e\nb.d = {}\n", doc.toString())
    }
}
