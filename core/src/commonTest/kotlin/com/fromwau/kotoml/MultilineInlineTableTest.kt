package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [InlineTable.isMultiline], [InlineTable.setMultiline] and the layout [Item.setInline] picks for its inline tables. */
class MultilineInlineTableTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    private fun TomlDocument.inlineAt(vararg path: String): InlineTable {
        var item = this[path.first()].getOrFail()
        for (key in path.drop(1)) item = item.tableOrNull!!.getItem(key)!!
        return item.inlineTableOrNull!!
    }

    private fun inlined(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
        force: Boolean = false,
    ): String {
        val doc = parse(text, version)
        doc["t"].getOrFail().setInline(true, force).getOrFail()
        return doc.toString()
    }

    @Test
    fun `an inline table read across lines reads multi-line`() {
        assertTrue(parse("t = {\n  a = 1,\n  b = 2\n}\n").inlineAt("t").isMultiline)
        assertTrue(parse("t = { a = 1,\n  b = 2 }\n").inlineAt("t").isMultiline)
        assertTrue(parse("t = { a = 1 # c\n}\n").inlineAt("t").isMultiline)
    }

    @Test
    fun `an inline table read on one line reads one-line`() {
        assertFalse(parse("t = { a = 1, b = 2 }\n").inlineAt("t").isMultiline)
        assertFalse(parse("t = {}\n").inlineAt("t").isMultiline)
    }

    @Test
    fun `an inline table on one line holding an array across lines reads one-line`() {
        assertFalse(parse("t = { a = [\n  1,\n  2,\n] }\n").inlineAt("t").isMultiline)
    }

    @Test
    fun `an inline table built in code reads one-line`() {
        assertFalse(inlineTableOf("a" to Value.Integer(1L)).getOrFail().isMultiline)
    }

    @Test
    fun `setting multi-line puts each entry on a line of its own`() {
        val doc = parse("t = { a = 1, b.c = 2, d = { e = 3 } } # tc\n")
        val table = doc.inlineAt("t")
        table.setMultiline(true).getOrFail()
        assertTrue(table.isMultiline)
        assertEquals("t = {\n  a = 1,\n  b.c = 2,\n  d = { e = 3 },\n} # tc\n", doc.toString())
    }

    @Test
    fun `setting multi-line in a document with CRLF line breaks ends the lines in CRLF`() {
        val doc = parse("x = 1\r\nt = { a = 1, b = 2 }\r\n")
        doc.inlineAt("t").setMultiline(true).getOrFail()
        assertEquals("x = 1\r\nt = {\r\n  a = 1,\r\n  b = 2,\r\n}\r\n", doc.toString())
    }

    @Test
    fun `setting multi-line on an empty inline table puts its braces on two lines`() {
        val doc = parse("t = {}\n")
        doc.inlineAt("t").setMultiline(true).getOrFail()
        assertEquals("t = {\n}\n", doc.toString())
        assertTrue(doc.inlineAt("t").isMultiline)
    }

    @Test
    fun `an inline table inside one across lines goes across lines indented from its own line`() {
        val doc = parse("t = {\n  a = 1,\n  u = { x = 1, y = 2 },\n}\n")
        doc.inlineAt("t", "u").setMultiline(true).getOrFail()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n    y = 2,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table in an array across lines goes across lines indented from its element's line`() {
        val doc = parse("a = [\n  { x = 1, y = 2 },\n]\n")
        doc["a"].getOrFail().arrayOrNull!![0].getOrFail().inlineTableOrNull!!.setMultiline(true).getOrFail()
        assertEquals("a = [\n  {\n    x = 1,\n    y = 2,\n  },\n]\n", doc.toString())
    }

    @Test
    fun `setting multi-line lays out only the table itself`() {
        val doc = parse("t = { a = 1, u = { x = 1 } }\n")
        doc.inlineAt("t").setMultiline(true).getOrFail()
        assertFalse(doc.inlineAt("t", "u").isMultiline)
        assertEquals("t = {\n  a = 1,\n  u = { x = 1 },\n}\n", doc.toString())
    }

    @Test
    fun `setting one-line is refused while comments inside would go`() {
        val text = "t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n"
        val doc = parse(text)
        val error = doc.inlineAt("t").setMultiline(false).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 4), error)
        assertEquals("t: the new layout would drop 4 comments; pass force = true to drop them", error.message)
        assertTrue(doc.inlineAt("t").isMultiline)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `setting one-line with force puts the entries on one line and drops the comments inside`() {
        val doc = parse("t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n")
        val table = doc.inlineAt("t")
        table.setMultiline(false, force = true).getOrFail()
        assertFalse(table.isMultiline)
        assertEquals("t = { a = 1, b.c = 2 } # tc\n", doc.toString())
    }

    @Test
    fun `setting one-line keeps the layout of an inline table inside`() {
        val doc = parse("t = {\n  a = 1,\n  u = {\n    x = 1, # xc\n  },\n}\n")
        doc.inlineAt("t").setMultiline(false).getOrFail()
        assertTrue(doc.inlineAt("t", "u").isMultiline)
        assertEquals("t = { a = 1, u = {\n    x = 1, # xc\n  } }\n", doc.toString())
    }

    @Test
    fun `setting the layout a table already has changes nothing`() {
        val text = "t = { a=1,b=2 }\nu = {\n    a = 1, # ac\n}\n"
        val doc = parse(text)
        doc.inlineAt("t").setMultiline(false).getOrFail()
        doc.inlineAt("u").setMultiline(true).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a dotted inline table reads one-line and setting the layout changes nothing`() {
        val text = "t = {\n  a.x = 1,\n  a.y = 2,\n}\n"
        val doc = parse(text)
        val dotted = doc.inlineAt("t", "a")
        assertFalse(dotted.isMultiline)
        dotted.setMultiline(true).getOrFail()
        dotted.setMultiline(false).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table across lines in a TOML 1_0 document is refused when written`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        doc.inlineAt("t").setMultiline(true).getOrFail()
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
    }

    @Test
    fun `a table across lines set back on one line in a TOML 1_0 document is written`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        doc.inlineAt("t").setMultiline(true).getOrFail()
        doc.inlineAt("t").setMultiline(false).getOrFail()
        doc.writeTo(Buffer()).getOrFail()
    }

    @Test
    fun `a comment given to a value of an inline table inside one across lines indents the new lines`() {
        val doc = parse("t = {\n  u = { a = 1, b = 2 },\n}\n")
        doc.inlineAt("t", "u").set("a", Value.Integer(5L).apply { decor = Decor(suffix = " # c") }).getOrFail()
        assertEquals("t = {\n  u = {\n    a = 5, # c\n    b = 2,\n  },\n}\n", doc.toString())
    }

    @Test
    fun `setting inline writes a table with no comments and every table under it on one line`() {
        val text = "[t]\na = 1\nb.c = 2\n\n[t.sub]\nx = 1\n\n[[t.list]]\ny = 2\n"
        assertEquals("t = { a = 1, b = { c = 2 }, sub = { x = 1 }, list = [{ y = 2 }] }\n", inlined(text))
    }

    @Test
    fun `setting inline then multi-line lays out only the table in the slot across lines`() {
        val doc = parse("[t]\na = 1\n\n[t.sub]\nx = 1\n")
        doc["t"].getOrFail().setInline(true).getOrFail()
        doc.inlineAt("t").setMultiline(true).getOrFail()
        assertEquals("t = {\n  a = 1,\n  sub = { x = 1 },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table set across lines in a TOML 1_0 document is refused when written`() {
        val doc = parse("[t]\na = 1\n", TomlVersion.V1_0)
        doc["t"].getOrFail().setInline(true).getOrFail()
        doc.inlineAt("t").setMultiline(true).getOrFail()
        assertEquals("t = {\n  a = 1\n}\n", doc.toString())
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
    }

    @Test
    fun `setting inline keeps a table with comments across lines and one under it without them on one line`() {
        val text = "[t]\na = 1\n\n[t.sub] # sc\nx = 1 # xc\n\n[t.other]\ny = 2\n"
        val expected = "t = {\n  a = 1,\n  sub = { # sc\n    x = 1, # xc\n  },\n  other = { y = 2 },\n}\n"
        assertEquals(expected, inlined(text))
    }

    @Test
    fun `setting one-line on a table made inline with comments inside is refused unless forced`() {
        val doc = parse("[t] # hc\na = 1 # ac\nb = 2\n")
        doc["t"].getOrFail().setInline(true).getOrFail()
        val error = doc.inlineAt("t").setMultiline(false).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 2), error)
        assertEquals("t = { # hc\n  a = 1, # ac\n  b = 2,\n}\n", doc.toString())
        doc.inlineAt("t").setMultiline(false, force = true).getOrFail()
        assertEquals("t = { a = 1, b = 2 }\n", doc.toString())
    }

    @Test
    fun `setting inline in a TOML 1_0 document is refused when comments inside would go`() {
        val text = "[t]\na = 1 # ac\n\n[t.sub] # sc\nx = 1 # xc\n"
        val doc = parse(text, TomlVersion.V1_0)
        val error = doc["t"].getOrFail().setInline(true).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 3), error)
        assertEquals(text, doc.toString())
        assertEquals("t = { a = 1, sub = { x = 1 } }\n", inlined(text, TomlVersion.V1_0, force = true))
    }

    @Test
    fun `setting inline in a TOML 1_0 document keeps the header comment after the braces`() {
        assertEquals("t = { a = 1 } # hc\n", inlined("[t] # hc\na = 1\n", TomlVersion.V1_0))
    }

    @Test
    fun `a table with tables and comments made inline and expanded again prints as before`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1 # ac\n# above b\nb.c = 2 # bc\n# above d\nb.d = 3\n\n" +
            "# about sub\n[t.sub] # sc\n# in sub\nc = 3 # cc\n\n[t.sub.deep] # dc\nd = 4 # d4\n\n" +
            "# about list\n[[t.list]] # lc\ne = 5 # ec\n\n# second\n[[t.list]]\n# above e\ne = 6\n\n[z]\ny = 1\n"
        val doc = parse(text)
        doc["t"].getOrFail().setInline(true).getOrFail()
        doc["t"].getOrFail().setInline(false).getOrFail()
        assertEquals(text, doc.toString())
    }
}
