package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [InlineTable.isMultiline] and the layout [Item.inline] picks for the inline tables it makes. */
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
        multiline: Boolean?,
        version: TomlVersion = TomlVersion.V1_1,
    ): String {
        val doc = parse(text, version)
        doc["t"].getOrFail().inline(multiline).getOrFail()
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
        table.isMultiline = true
        assertTrue(table.isMultiline)
        assertEquals("t = {\n  a = 1,\n  b.c = 2,\n  d = { e = 3 }\n} # tc\n", doc.toString())
    }

    @Test
    fun `setting multi-line in a document with CRLF line breaks ends the lines in CRLF`() {
        val doc = parse("x = 1\r\nt = { a = 1, b = 2 }\r\n")
        doc.inlineAt("t").isMultiline = true
        assertEquals("x = 1\r\nt = {\r\n  a = 1,\r\n  b = 2\r\n}\r\n", doc.toString())
    }

    @Test
    fun `setting multi-line on an empty inline table puts its braces on two lines`() {
        val doc = parse("t = {}\n")
        doc.inlineAt("t").isMultiline = true
        assertEquals("t = {\n}\n", doc.toString())
        assertTrue(doc.inlineAt("t").isMultiline)
    }

    @Test
    fun `an inline table inside one across lines goes across lines indented from its own line`() {
        val doc = parse("t = {\n  a = 1,\n  u = { x = 1, y = 2 },\n}\n")
        doc.inlineAt("t", "u").isMultiline = true
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n    y = 2\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table in an array across lines goes across lines indented from its element's line`() {
        val doc = parse("a = [\n  { x = 1, y = 2 },\n]\n")
        doc["a"].getOrFail().arrayOrNull!![0].getOrFail().inlineTableOrNull!!.isMultiline = true
        assertEquals("a = [\n  {\n    x = 1,\n    y = 2\n  },\n]\n", doc.toString())
    }

    @Test
    fun `setting multi-line lays out only the table itself`() {
        val doc = parse("t = { a = 1, u = { x = 1 } }\n")
        doc.inlineAt("t").isMultiline = true
        assertFalse(doc.inlineAt("t", "u").isMultiline)
        assertEquals("t = {\n  a = 1,\n  u = { x = 1 }\n}\n", doc.toString())
    }

    @Test
    fun `setting one-line puts the entries on one line and drops the comments inside`() {
        val doc = parse("t = { # open\n  a = 1, # ac\n  # above b\n  b.c = 2,\n  # end\n} # tc\n")
        val table = doc.inlineAt("t")
        table.isMultiline = false
        assertFalse(table.isMultiline)
        assertEquals("t = { a = 1, b.c = 2 } # tc\n", doc.toString())
    }

    @Test
    fun `setting one-line keeps the layout of an inline table inside`() {
        val doc = parse("t = {\n  a = 1,\n  u = {\n    x = 1, # xc\n  },\n}\n")
        doc.inlineAt("t").isMultiline = false
        assertTrue(doc.inlineAt("t", "u").isMultiline)
        assertEquals("t = { a = 1, u = {\n    x = 1, # xc\n  } }\n", doc.toString())
    }

    @Test
    fun `setting the layout a table already has changes nothing`() {
        val text = "t = { a=1,b=2 }\nu = {\n    a = 1, # ac\n}\n"
        val doc = parse(text)
        doc.inlineAt("t").isMultiline = false
        doc.inlineAt("u").isMultiline = true
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a dotted inline table reads one-line and setting the layout changes nothing`() {
        val text = "t = {\n  a.x = 1,\n  a.y = 2,\n}\n"
        val doc = parse(text)
        val dotted = doc.inlineAt("t", "a")
        assertFalse(dotted.isMultiline)
        dotted.isMultiline = true
        dotted.isMultiline = false
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table across lines in a TOML 1_0 document is refused when written`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        doc.inlineAt("t").isMultiline = true
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
    }

    @Test
    fun `a table across lines set back on one line in a TOML 1_0 document is written`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        doc.inlineAt("t").isMultiline = true
        doc.inlineAt("t").isMultiline = false
        doc.writeTo(Buffer()).getOrFail()
    }

    @Test
    fun `a comment given to a value of an inline table inside one across lines indents the new lines`() {
        val doc = parse("t = {\n  u = { a = 1, b = 2 },\n}\n")
        doc.inlineAt("t", "u").set("a", Value.Integer(5L).apply { decor = Decor(suffix = " # c") }).getOrFail()
        assertEquals("t = {\n  u = {\n    a = 5, # c\n    b = 2\n  },\n}\n", doc.toString())
    }

    @Test
    fun `inline across lines writes the table and every table under it across lines`() {
        val text = "[t]\na = 1\nb.c = 2\n\n[t.sub]\nx = 1\n\n[[t.list]]\ny = 2\n"
        val expected = "t = {\n  a = 1,\n  b.c = 2,\n  sub = {\n    x = 1\n  },\n  list = [\n    {\n      y = 2\n" +
            "    },\n  ]\n}\n"
        assertEquals(expected, inlined(text, multiline = true))
    }

    @Test
    fun `inline across lines in a TOML 1_0 document is refused when written`() {
        val doc = parse("[t]\na = 1\n", TomlVersion.V1_0)
        doc["t"].getOrFail().inline(multiline = true).getOrFail()
        assertEquals("t = {\n  a = 1\n}\n", doc.toString())
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
    }

    @Test
    fun `inline on one line writes every table on one line keeping only the header comment`() {
        val text = "[t] # hc\na = 1 # ac\n\n[t.sub]\n# in sub\nx = 1\n"
        assertEquals("t = { a = 1, sub = { x = 1 } } # hc\n", inlined(text, multiline = false))
    }

    @Test
    fun `inline without a layout keeps a table under it across lines when it has comments`() {
        val text = "[t]\na = 1\n\n[t.sub] # sc\nx = 1 # xc\n\n[t.other]\ny = 2\n"
        val expected = "t = {\n  a = 1,\n  sub = { # sc\n    x = 1 # xc\n  },\n  other = { y = 2 }\n}\n"
        assertEquals(expected, inlined(text, multiline = null))
    }

    @Test
    fun `inline without a layout in a TOML 1_0 document writes one line`() {
        val text = "[t]\na = 1 # ac\n\n[t.sub] # sc\nx = 1 # xc\n"
        assertEquals("t = { a = 1, sub = { x = 1 } }\n", inlined(text, multiline = null, TomlVersion.V1_0))
    }

    @Test
    fun `a table with tables and comments made inline across lines and expanded again prints as before`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1 # ac\n# above b\nb.c = 2 # bc\n# above d\nb.d = 3\n\n" +
            "# about sub\n[t.sub] # sc\n# in sub\nc = 3 # cc\n\n[t.sub.deep] # dc\nd = 4 # d4\n\n" +
            "# about list\n[[t.list]] # lc\ne = 5 # ec\n\n# second\n[[t.list]]\n# above e\ne = 6\n\n[z]\ny = 1\n"
        val doc = parse(text)
        doc["t"].getOrFail().inline(multiline = true).getOrFail()
        doc["t"].getOrFail().expand().getOrFail()
        assertEquals(text, doc.toString())
    }
}
