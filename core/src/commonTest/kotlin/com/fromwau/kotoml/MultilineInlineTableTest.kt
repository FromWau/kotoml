package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** [TableLayout.MultilineInline] against [TableLayout.Inline]: how each reads, and the changes between them. */
class MultilineInlineTableTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    private fun TomlDocument.slotAt(vararg path: String): Item {
        var item = this[path.first()].assertSuccess()
        for (key in path.drop(1)) item = item.tableOrNull!!.getItem(key)!!
        return item
    }

    private fun laidOut(
        text: String,
        layout: TableLayout,
        version: TomlVersion = TomlVersion.V1_1,
        force: Boolean = false,
    ): String {
        val doc = parse(text, version)
        doc["t"].assertSuccess().setLayout(layout, force).assertSuccess()
        return doc.toString()
    }

    @Test
    fun `an inline table read across lines reads multi-line`() {
        assertEquals(TableLayout.MultilineInline, parse("t = {\n  a = 1,\n  b = 2\n}\n").slotAt("t").layout)
        assertEquals(TableLayout.MultilineInline, parse("t = { a = 1,\n  b = 2 }\n").slotAt("t").layout)
        assertEquals(TableLayout.MultilineInline, parse("t = { a = 1 # c\n}\n").slotAt("t").layout)
    }

    @Test
    fun `an inline table read on one line reads one-line`() {
        assertEquals(TableLayout.Inline, parse("t = { a = 1, b = 2 }\n").slotAt("t").layout)
        assertEquals(TableLayout.Inline, parse("t = {}\n").slotAt("t").layout)
    }

    @Test
    fun `an inline table on one line holding an array across lines reads one-line`() {
        assertEquals(TableLayout.Inline, parse("t = { a = [\n  1,\n  2,\n] }\n").slotAt("t").layout)
    }

    @Test
    fun `an inline table built in code reads one-line`() {
        assertEquals(TableLayout.Inline, inlineTableOf("a" to Value.Integer(1L)).assertSuccess().layout)
    }

    @Test
    fun `setting multi-line puts each entry on a line of its own`() {
        val doc = parse("t = { a = 1, b.c = 2, d = { e = 3 } } # tc\n")
        val table = doc.slotAt("t").inlineTableOrNull!!
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals(TableLayout.MultilineInline, table.layout)
        assertEquals("t = {\n  a = 1,\n  b.c = 2,\n  d = {\n    e = 3,\n  },\n} # tc\n", doc.toString())
    }

    @Test
    fun `setting multi-line in a document with CRLF line breaks ends the lines in CRLF`() {
        val doc = parse("x = 1\r\nt = { a = 1, b = 2 }\r\n")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("x = 1\r\nt = {\r\n  a = 1,\r\n  b = 2,\r\n}\r\n", doc.toString())
    }

    @Test
    fun `setting multi-line on an empty inline table puts its braces on two lines`() {
        val doc = parse("t = {}\n")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n}\n", doc.toString())
        assertEquals(TableLayout.MultilineInline, doc.slotAt("t").layout)
    }

    @Test
    fun `an inline table inside one across lines goes across lines indented from its own line`() {
        val doc = parse("t = {\n  a = 1,\n  u = { x = 1, y = 2 },\n}\n")
        doc.slotAt("t", "u").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n    y = 2,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table in an array across lines goes across lines indented from its element's line`() {
        val doc = parse("a = [\n  { x = 1, y = 2 },\n]\n")
        doc["a"][0].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("a = [\n  {\n    x = 1,\n    y = 2,\n  },\n]\n", doc.toString())
    }

    @Test
    fun `setting multi-line lays out the inline tables inside across lines too`() {
        val doc = parse("t = { a = 1, u = { x = 1 } }\n")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals(TableLayout.MultilineInline, doc.slotAt("t", "u").layout)
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `setting one-line is refused while comments inside would go`() {
        val text = "t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n"
        val doc = parse(text)
        val error = doc.slotAt("t").setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 4), error)
        assertEquals("t: the new layout would drop 4 comments; pass force = true to drop them", error.message)
        assertEquals(TableLayout.MultilineInline, doc.slotAt("t").layout)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `setting one-line with force puts the entries on one line and drops the comments inside`() {
        val doc = parse("t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n")
        doc.slotAt("t").setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals(TableLayout.Inline, doc.slotAt("t").layout)
        assertEquals("t = { a = 1, b.c = 2 } # tc\n", doc.toString())
    }

    @Test
    fun `setting one-line puts the inline tables inside on the line too`() {
        val doc = parse("t = {\n  a = 1,\n  u = {\n    x = 1, # xc\n  },\n}\n")
        val error = doc.slotAt("t").setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc.slotAt("t").setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals(TableLayout.Inline, doc.slotAt("t", "u").layout)
        assertEquals("t = { a = 1, u = { x = 1 } }\n", doc.toString())
    }

    @Test
    fun `setting the layout a table already has changes nothing`() {
        val text = "t = { a=1,b=2 }\nu = {\n    a = 1, # ac\n}\n"
        val doc = parse(text)
        doc.slotAt("t").setLayout(TableLayout.Inline).assertSuccess()
        doc.slotAt("u").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a dotted inline table reads dotted and setting dotted changes nothing`() {
        val text = "t = {\n  a.x = 1,\n  a.y = 2,\n}\n"
        val doc = parse(text)
        assertEquals(TableLayout.Dotted, doc.slotAt("t", "a").layout)
        doc.slotAt("t", "a").setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table set across lines in a TOML 1_0 document is refused`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        val error = doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a comment given to a value of an inline table inside one across lines indents the new lines`() {
        val doc = parse("t = {\n  u = { a = 1, b = 2 },\n}\n")
        val inner = doc.slotAt("t", "u").inlineTableOrNull!!
        inner.set("a", Value.Integer(5L).apply { decor = Decor(suffix = " # c") }).assertSuccess()
        assertEquals("t = {\n  u = {\n    a = 5, # c\n    b = 2,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `setting inline writes a table with no comments and every table under it on one line`() {
        val text = "[t]\na = 1\nb.c = 2\n\n[t.sub]\nx = 1\n\n[[t.list]]\ny = 2\n"
        val expected = "t = { a = 1, b = { c = 2 }, sub = { x = 1 }, list = [{ y = 2 }] }\n"
        assertEquals(expected, laidOut(text, TableLayout.Inline))
    }

    @Test
    fun `setting inline then multi-line lays out the tables inside across lines too`() {
        val doc = parse("[t]\na = 1\n\n[t.sub]\nx = 1\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n  sub = {\n    x = 1,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table made from a table in a TOML 1_0 document is refused across lines`() {
        val doc = parse("[t]\na = 1\n", TomlVersion.V1_0)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        val error = doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `setting multi-line inline keeps the comments of the tables inside and lays out each across lines`() {
        val text = "[t]\na = 1\n\n[t.sub] # sc\nx = 1 # xc\n\n[t.other]\ny = 2\n"
        val expected = "t = {\n  a = 1,\n  sub = { # sc\n    x = 1, # xc\n  },\n  other = {\n    y = 2,\n  },\n}\n"
        assertEquals(expected, laidOut(text, TableLayout.MultilineInline))
    }

    @Test
    fun `setting one-line on a table made inline with comments inside is refused unless forced`() {
        val doc = parse("[t] # hc\na = 1 # ac\nb = 2\n")
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        val error = doc.slotAt("t").setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        assertEquals("t = { # hc\n  a = 1, # ac\n  b = 2,\n}\n", doc.toString())
        doc.slotAt("t").setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { a = 1, b = 2 } # hc\n", doc.toString())
    }

    @Test
    fun `setting inline in a TOML 1_0 document is refused when comments inside would go`() {
        val text = "[t]\na = 1 # ac\n\n[t.sub] # sc\nx = 1 # xc\n"
        val doc = parse(text, TomlVersion.V1_0)
        val error = doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 3), error)
        assertEquals(text, doc.toString())
        val forced = laidOut(text, TableLayout.Inline, TomlVersion.V1_0, force = true)
        assertEquals("t = { a = 1, sub = { x = 1 } }\n", forced)
    }

    @Test
    fun `setting inline in a TOML 1_0 document keeps the header comment after the braces`() {
        assertEquals("t = { a = 1 } # hc\n", laidOut("[t] # hc\na = 1\n", TableLayout.Inline, TomlVersion.V1_0))
    }

    @Test
    fun `a table with tables and comments made inline and expanded again prints as before`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1 # ac\n# above b\nb.c = 2 # bc\n# above d\nb.d = 3\n\n" +
            "# about sub\n[t.sub] # sc\n# in sub\nc = 3 # cc\n\n[t.sub.deep] # dc\nd = 4 # d4\n\n" +
            "# about list\n[[t.list]] # lc\ne = 5 # ec\n\n# second\n[[t.list]]\n# above e\ne = 6\n\n[z]\ny = 1\n"
        val doc = parse(text)
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals(text, doc.toString())
    }

    private fun TomlDocument.removeFromDotted(vararg path: String) {
        slotAt(*path.dropLast(1).toTypedArray()).inlineTableOrNull!!.remove(path.last())
    }

    @Test
    fun `an empty dotted table last in a table set multi-line takes the trailing comma after its braces`() {
        val doc = parse("t = { a = 1, b.d.e = 2 }\n")
        doc.removeFromDotted("t", "b", "d", "e")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n  b.d = {},\n}\n", doc.toString())
        doc.format()
        assertEquals("t = {\n  a = 1,\n  b.d = {},\n}\n", doc.toString())
    }

    @Test
    fun `an empty dotted table last in a table made multi-line inline from a header takes the trailing comma`() {
        val doc = parse("[t]\na = 1\nb.d.e = 2\n")
        doc["t"]["b"]["d"].assertSuccess().standardTableOrNull!!.remove("e")
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n  b.d = {},\n}\n", doc.toString())
    }

    @Test
    fun `removing the last key of a dotted table across lines leaves its empty table on the key's line`() {
        val last = "t = {\n  a = 1,\n  b.d = {},\n}\n"
        val cases = listOf(
            Triple("t = {\n  a = 1,\n  b.d.e = 2,\n}\n", last, last),
            Triple("t = {\n  a = 1,\n  b.d.e = 2\n}\n", "t = {\n  a = 1,\n  b.d = {}\n}\n", last),
            Triple("t = {\n  a = 1,\n  b.d.e=2,\n}\n", "t = {\n  a = 1,\n  b.d={},\n}\n", last),
            Triple("t = {\n  b.d.e = 2,\n  a = 1,\n}\n", "t = {\n  b.d = {},\n  a = 1,\n}\n", null),
            Triple(
                "t = {\n  a = 1,\n  # about e\n  b.d.e = 2 # e\n}\n",
                "t = {\n  a = 1,\n  # about e\n  b.d = {}\n}\n",
                "t = {\n  a = 1,\n  # about e\n  b.d = {},\n}\n",
            ),
        )
        for ((text, expected, formatted) in cases) {
            val doc = parse(text)
            doc.removeFromDotted("t", "b", "d", "e")
            assertEquals(expected, doc.toString(), text)
            doc.format()
            assertEquals(formatted ?: expected, doc.toString(), text)
        }
    }

    @Test
    fun `a key put in an emptied dotted table across lines and removed again leaves the empty table on its line`() {
        val doc = parse("t = {\n  a = 1,\n  b.d.e = 2,\n  c = 3,\n}\n")
        val d = doc.slotAt("t", "b", "d").inlineTableOrNull!!
        d.remove("e")
        d["x"] = Value.Integer(5L)
        assertEquals("t = {\n  a = 1,\n  b.d.x = 5,\n  c = 3,\n}\n", doc.toString())
        d.remove("x")
        assertEquals("t = {\n  a = 1,\n  b.d = {},\n  c = 3,\n}\n", doc.toString())
    }

    @Test
    fun `removing the last key of a dotted table on one line leaves its empty table spaced as the key was`() {
        val cases = listOf(
            "t = { a = 1, b.d.e = 2 }\n" to "t = { a = 1, b.d = {} }\n",
            "t = { b.d.e = 2, a = 1 }\n" to "t = { b.d = {}, a = 1 }\n",
            "t = { a = 1, b.d.e = 2, c = 3 }\n" to "t = { a = 1, b.d = {}, c = 3 }\n",
        )
        for (version in TomlVersion.entries) {
            for ((text, expected) in cases) {
                val doc = parse(text, version)
                doc.removeFromDotted("t", "b", "d", "e")
                assertEquals(expected, doc.toString(), "$version $text")
                doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
                assertEquals(expected, doc.toString(), "$version $text")
            }
        }
    }
}
