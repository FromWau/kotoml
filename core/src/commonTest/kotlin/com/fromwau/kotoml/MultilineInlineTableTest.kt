package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** [TableLayout.MultilineInline] against [TableLayout.Inline]: how each reads, and the changes between them. */
class MultilineInlineTableTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    private fun TomlDocument.slotAt(vararg path: String): Item {
        var item = this[path.first()].getOrFail()
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
        doc["t"].getOrFail().setLayout(layout, force).getOrFail()
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
        assertEquals(TableLayout.Inline, inlineTableOf("a" to Value.Integer(1L)).getOrFail().layout)
    }

    @Test
    fun `setting multi-line puts each entry on a line of its own`() {
        val doc = parse("t = { a = 1, b.c = 2, d = { e = 3 } } # tc\n")
        val table = doc.slotAt("t").inlineTableOrNull!!
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals(TableLayout.MultilineInline, table.layout)
        assertEquals("t = {\n  a = 1,\n  b.c = 2,\n  d = {\n    e = 3,\n  },\n} # tc\n", doc.toString())
    }

    @Test
    fun `setting multi-line in a document with CRLF line breaks ends the lines in CRLF`() {
        val doc = parse("x = 1\r\nt = { a = 1, b = 2 }\r\n")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("x = 1\r\nt = {\r\n  a = 1,\r\n  b = 2,\r\n}\r\n", doc.toString())
    }

    @Test
    fun `setting multi-line on an empty inline table puts its braces on two lines`() {
        val doc = parse("t = {}\n")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("t = {\n}\n", doc.toString())
        assertEquals(TableLayout.MultilineInline, doc.slotAt("t").layout)
    }

    @Test
    fun `an inline table inside one across lines goes across lines indented from its own line`() {
        val doc = parse("t = {\n  a = 1,\n  u = { x = 1, y = 2 },\n}\n")
        doc.slotAt("t", "u").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n    y = 2,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table in an array across lines goes across lines indented from its element's line`() {
        val doc = parse("a = [\n  { x = 1, y = 2 },\n]\n")
        doc["a"][0].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("a = [\n  {\n    x = 1,\n    y = 2,\n  },\n]\n", doc.toString())
    }

    @Test
    fun `setting multi-line lays out the inline tables inside across lines too`() {
        val doc = parse("t = { a = 1, u = { x = 1 } }\n")
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals(TableLayout.MultilineInline, doc.slotAt("t", "u").layout)
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `setting one-line is refused while comments inside would go`() {
        val text = "t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n"
        val doc = parse(text)
        val error = doc.slotAt("t").setLayout(TableLayout.Inline).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 4), error)
        assertEquals("t: the new layout would drop 4 comments; pass force = true to drop them", error.message)
        assertEquals(TableLayout.MultilineInline, doc.slotAt("t").layout)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `setting one-line with force puts the entries on one line and drops the comments inside`() {
        val doc = parse("t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n")
        doc.slotAt("t").setLayout(TableLayout.Inline, force = true).getOrFail()
        assertEquals(TableLayout.Inline, doc.slotAt("t").layout)
        assertEquals("t = { a = 1, b.c = 2 } # tc\n", doc.toString())
    }

    @Test
    fun `setting one-line puts the inline tables inside on the line too`() {
        val doc = parse("t = {\n  a = 1,\n  u = {\n    x = 1, # xc\n  },\n}\n")
        val error = doc.slotAt("t").setLayout(TableLayout.Inline).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc.slotAt("t").setLayout(TableLayout.Inline, force = true).getOrFail()
        assertEquals(TableLayout.Inline, doc.slotAt("t", "u").layout)
        assertEquals("t = { a = 1, u = { x = 1 } }\n", doc.toString())
    }

    @Test
    fun `setting the layout a table already has changes nothing`() {
        val text = "t = { a=1,b=2 }\nu = {\n    a = 1, # ac\n}\n"
        val doc = parse(text)
        doc.slotAt("t").setLayout(TableLayout.Inline).getOrFail()
        doc.slotAt("u").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a dotted inline table reads dotted and setting dotted changes nothing`() {
        val text = "t = {\n  a.x = 1,\n  a.y = 2,\n}\n"
        val doc = parse(text)
        assertEquals(TableLayout.Dotted, doc.slotAt("t", "a").layout)
        doc.slotAt("t", "a").setLayout(TableLayout.Dotted).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table across lines in a TOML 1_0 document is refused when written`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
    }

    @Test
    fun `a table across lines set back on one line in a TOML 1_0 document is written`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        doc.slotAt("t").setLayout(TableLayout.Inline).getOrFail()
        doc.writeTo(Buffer()).getOrFail()
    }

    @Test
    fun `a comment given to a value of an inline table inside one across lines indents the new lines`() {
        val doc = parse("t = {\n  u = { a = 1, b = 2 },\n}\n")
        val inner = doc.slotAt("t", "u").inlineTableOrNull!!
        inner.set("a", Value.Integer(5L).apply { decor = Decor(suffix = " # c") }).getOrFail()
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
        doc["t"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("t = {\n  a = 1,\n  sub = {\n    x = 1,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table set across lines in a TOML 1_0 document is refused when written`() {
        val doc = parse("[t]\na = 1\n", TomlVersion.V1_0)
        doc["t"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        doc.slotAt("t").setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("t = {\n  a = 1\n}\n", doc.toString())
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
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
        doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        val error = doc.slotAt("t").setLayout(TableLayout.Inline).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 2), error)
        assertEquals("t = { # hc\n  a = 1, # ac\n  b = 2,\n}\n", doc.toString())
        doc.slotAt("t").setLayout(TableLayout.Inline, force = true).getOrFail()
        assertEquals("t = { a = 1, b = 2 }\n", doc.toString())
    }

    @Test
    fun `setting inline in a TOML 1_0 document is refused when comments inside would go`() {
        val text = "[t]\na = 1 # ac\n\n[t.sub] # sc\nx = 1 # xc\n"
        val doc = parse(text, TomlVersion.V1_0)
        val error = doc["t"].getOrFail().setLayout(TableLayout.Inline).errorOrFail()
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
        doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        doc["t"].getOrFail().setLayout(TableLayout.Header).getOrFail()
        assertEquals(text, doc.toString())
    }
}
