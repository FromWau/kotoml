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
}
