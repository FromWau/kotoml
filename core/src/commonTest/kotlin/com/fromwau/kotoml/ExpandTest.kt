package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** [Item.expand] turns an inline table or an array of inline tables in a slot into a table or array of tables. */
class ExpandTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    private fun expand(
        text: String,
        key: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): String {
        val doc = parse(text, version)
        doc[key].getOrFail().setInline(false).getOrFail()
        return doc.toString()
    }

    @Test
    fun `an inline table becomes a table printed after the values of its parent`() {
        assertEquals("a = 1\nb = 2\n\n[t]\nx = 1\ny = 2\n", expand("a = 1\nt = { x = 1, y = 2 }\nb = 2\n", "t"))
    }

    @Test
    fun `an inline table becomes a table printed before the tables after its key`() {
        assertEquals("[t]\nx = 1\n\n[z]\nk = 1\n", expand("t = { x = 1 }\n\n[z]\nk = 1\n", "t"))
    }

    @Test
    fun `an inline table in a table becomes a sub-table printed after its parent`() {
        val doc = parse("[a]\nk = 1\nt = { x = 1 }\n\n[b]\nk = 2\n")
        doc["a"]["t"].getOrFail().setInline(false).getOrFail()
        assertEquals("[a]\nk = 1\n\n[a.t]\nx = 1\n\n[b]\nk = 2\n", doc.toString())
    }

    @Test
    fun `an inline table under a dotted key becomes a table under the dotted path`() {
        val doc = parse("x.t = { k = 1 }\n")
        doc["x"]["t"].getOrFail().setInline(false).getOrFail()
        assertEquals("[x.t]\nk = 1\n", doc.toString())
    }

    @Test
    fun `the comments of an inline table across lines go to the lines of the table`() {
        val text = "t = { # open\n  x = 1, # xc\n  # above y\n  y = 2,\n  # end\n}\n"
        assertEquals("[t] # open\nx = 1 # xc\n# above y\ny = 2\n# end\n", expand(text, "t"))
    }

    @Test
    fun `the comment after the braces becomes the header comment`() {
        assertEquals("[t] # tc\nx = 1\n", expand("t = { x = 1 } # tc\n", "t"))
    }

    @Test
    fun `the comment after the closing brace ends the table when the opening brace has one`() {
        assertEquals("[t] # open\nx = 1\n# tc\n", expand("t = { # open\n  x = 1\n} # tc\n", "t"))
    }

    @Test
    fun `the comment lines above the key go above the header`() {
        assertEquals("a = 1\n\n# about t\n[t]\nx = 1\n", expand("a = 1\n# about t\nt = { x = 1 }\n", "t"))
    }

    @Test
    fun `inline tables inside the inline table become tables at any depth`() {
        val text = "t = { a = 1, u = { x = 1, v = { y = 2 } } }\n"
        assertEquals("[t]\na = 1\n\n[t.u]\nx = 1\n\n[t.u.v]\ny = 2\n", expand(text, "t"))
    }

    @Test
    fun `an inline table holding only tables prints no header of its own`() {
        assertEquals("[t.u]\nx = 1\n", expand("t = { u = { x = 1 } }\n", "t"))
    }

    @Test
    fun `arrays of inline tables inside the inline table become arrays of tables at any depth`() {
        val text = "t = { a = 1, l = [{ x = 1 }, { x = 2, d = [{ y = 1 }] }] }\n"
        val expected = "[t]\na = 1\n\n[[t.l]]\nx = 1\n\n[[t.l]]\nx = 2\n\n[[t.l.d]]\ny = 1\n"
        assertEquals(expected, expand(text, "t"))
    }

    @Test
    fun `inline tables inside an array of inline tables become tables`() {
        assertEquals("[[t]]\nx = 1\n\n[t.u]\ny = 1\n", expand("t = [{ x = 1, u = { y = 1 } }]\n", "t"))
    }

    @Test
    fun `plain arrays and arrays holding more than inline tables stay values`() {
        val text = "t = { p = [1, 2], m = [{ x = 1 }, 2], e = [], n = [[{ x = 1 }]] }\n"
        assertEquals("[t]\np = [1, 2]\nm = [{ x = 1 }, 2]\ne = []\nn = [[{ x = 1 }]]\n", expand(text, "t"))
    }

    @Test
    fun `an empty inline table inside becomes an empty table`() {
        assertEquals("[t]\na = 1\n\n[t.s]\n", expand("t = { a = 1, s = {} }\n", "t"))
    }

    @Test
    fun `dotted keys inside stay dotted keys`() {
        assertEquals("[t]\na.b = 1 # c\nc = 2\n", expand("t = {\n  a.b = 1, # c\n  c = 2,\n}\n", "t"))
    }

    @Test
    fun `an inline table under a dotted key inside becomes a table under the dotted path`() {
        val text = "t = {\n  a.b = { x = 1 },\n  a.c = 2, # c\n}\n"
        assertEquals("[t]\na.c = 2 # c\n\n[t.a.b]\nx = 1\n", expand(text, "t"))
    }

    @Test
    fun `an inline table inside keeps its comments as a table`() {
        val text = "t = {\n  a = 1,\n  # about u\n  u = { # uc\n    x = 1, # xc\n  }, # after u\n  b = 2,\n}\n"
        assertEquals("[t]\na = 1\nb = 2\n\n# about u\n[t.u] # uc\nx = 1 # xc\n# after u\n", expand(text, "t"))
    }

    @Test
    fun `the comment after the braces of an inline table inside goes on its header`() {
        val text = "t = {\n  a = 1,\n  # above u\n  u = { x = 1 }, # uc\n}\n"
        assertEquals("[t]\na = 1\n\n# above u\n[t.u] # uc\nx = 1\n", expand(text, "t"))
    }

    @Test
    fun `an array of inline tables inside keeps its comments as an array of tables`() {
        val text = "t = {\n  a = 1,\n  l = [ # first\n    { x = 1 }, # one\n    # above two\n    { x = 2 },\n" +
            "  ], # after l\n}\n"
        val expected = "[t]\na = 1\n\n# first\n[[t.l]] # one\nx = 1\n\n# above two\n[[t.l]]\nx = 2\n# after l\n"
        assertEquals(expected, expand(text, "t"))
    }

    @Test
    fun `the comment after the opening bracket goes below the comment lines above the key`() {
        val text = "x = 1\n# about t\nt = [ # first\n  { x = 1 },\n]\n"
        assertEquals("x = 1\n\n# about t\n# first\n[[t]]\nx = 1\n", expand(text, "t"))
    }

    @Test
    fun `tables inside expanded in a document with CRLF line breaks print CRLF lines`() {
        val text = "t = {\r\n  a = 1,\r\n  # above u\r\n  u = { x = 1 }, # uc\r\n}\r\n"
        assertEquals("[t]\r\na = 1\r\n\r\n# above u\r\n[t.u] # uc\r\nx = 1\r\n", expand(text, "t"))
    }

    @Test
    fun `a table with tables and arrays of tables made inline in TOML 1_1 and expanded again prints as before`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1 # ac\n# above b\nb = 2\n\n# about sub\n[t.sub]\nc = 3\n\n" +
            "[t.sub.deep]\nd = 4\n\n# about list\n[[t.list]]\ne = 5\n\n[[t.list]]\ne = 6\n\n[[t.list.inner]]\nf = 7\n" +
            "\n[z]\ny = 1\n"
        val doc = parse(text)
        doc["t"].getOrFail().setInline(true).getOrFail()
        doc["t"].getOrFail().setInline(false).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table with tables and arrays of tables forced inline in TOML 1_0 and expanded again reads the same`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1 # ac\n# above b\nb = 2\n\n# about sub\n[t.sub]\nc = 3\n\n" +
            "[t.sub.deep]\nd = 4\n\n# about list\n[[t.list]]\ne = 5\n\n[[t.list]]\ne = 6\n\n[[t.list.inner]]\nf = 7\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc["t"].getOrFail().setInline(true, force = true).getOrFail()
        doc["t"].getOrFail().setInline(false).getOrFail()
        val kept = "x = 1\n\n# about t\n[t] # hc\na = 1\nb = 2\n\n[t.sub]\nc = 3\n\n[t.sub.deep]\nd = 4\n\n" +
            "[[t.list]]\ne = 5\n\n[[t.list]]\ne = 6\n\n[[t.list.inner]]\nf = 7\n"
        assertEquals(kept, doc.toString())
    }

    @Test
    fun `an array of tables holding tables made inline and expanded again prints as before`() {
        val text = "# about t\n[[t]] # hc\na = 1\n\n[t.sub]\nc = 3\n\n[[t.sub.list]]\nd = 4\n\n[[t]]\na = 2\n"
        for (version in listOf(TomlVersion.V1_1, TomlVersion.V1_0)) {
            val doc = parse(text, version)
            doc["t"].getOrFail().setInline(true).getOrFail()
            doc["t"].getOrFail().setInline(false).getOrFail()
            assertEquals(text, doc.toString(), version.toString())
        }
    }

    @Test
    fun `inline tables nested to fit their line but whose headers would not fit are TooDeep and change nothing`() {
        val depth = TomlDocument.NESTING_LIMIT - 1
        val text = "[a.b]\nt = " + "{ t = ".repeat(depth - 1) + "{ x = 1 }" + " }".repeat(depth - 1) + "\n"
        val doc = parse(text)
        val error = doc["a"]["b"]["t"].getOrFail().setInline(false).errorOrFail()
        assertEquals(TomlEditError.TooDeep(listOf("a", "b") + List(depth) { "t" }), error)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `inline tables nested to the limit of headers expand and read back`() {
        val depth = TomlDocument.NESTING_LIMIT - 1
        val text = "[a]\nt = " + "{ t = ".repeat(depth - 1) + "{ x = 1 }" + " }".repeat(depth - 1) + "\n"
        val doc = parse(text)
        doc["a"]["t"].getOrFail().setInline(false).getOrFail()
        assertEquals("[a]\n\n[a" + ".t".repeat(depth) + "]\nx = 1\n", doc.toString())
        parse(doc.toString())
    }

    @Test
    fun `a table made inline in TOML 1_1 and expanded again prints as before`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1 # ac\n# above b\nb = 2\n"
        val doc = parse(text)
        doc["t"].getOrFail().setInline(true).getOrFail()
        doc["t"].getOrFail().setInline(false).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table made inline in TOML 1_0 and expanded again prints as before`() {
        val text = "x = 1\n\n# about t\n[t] # hc\na = 1\nb = 2\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc["t"].getOrFail().setInline(true).getOrFail()
        doc["t"].getOrFail().setInline(false).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an array of inline tables becomes an array of tables`() {
        assertEquals("[[a]]\nx = 1\n\n[[a]]\nx = 2\n", expand("a = [{ x = 1 }, { x = 2 }]\n", "a"))
    }

    @Test
    fun `an array of inline tables keeps the comments on its lines`() {
        val text = "a = [ # first\n  { x = 1 }, # one\n  # above two\n  { # open\n    x = 2\n  }, # two\n  # end\n] # last\n"
        val expected = "# first\n[[a]] # one\nx = 1\n\n# above two\n[[a]] # open\nx = 2\n# two\n# end\n# last\n"
        assertEquals(expected, expand(text, "a"))
    }

    @Test
    fun `an element marked dotted prints in braces and keeps their comments as a table`() {
        val doc = parse("a = [\n  {\n    x = 1, # xc\n  },\n]\n")
        doc["a"].getOrFail().arrayOrNull!![0].getOrFail().inlineTableOrNull!!.setDotted(true).getOrFail()
        doc["a"].getOrFail().setInline(false).getOrFail()
        assertEquals("[[a]]\nx = 1 # xc\n", doc.toString())
    }

    @Test
    fun `the comment after the last element of an array without a trailing comma goes on its header`() {
        val text = "a = [\n  { x = 1 },\n  { x = 2 } # two\n]\n"
        assertEquals("[[a]]\nx = 1\n\n[[a]] # two\nx = 2\n", expand(text, "a"))
    }

    @Test
    fun `an array of inline tables expanded in a document with CRLF line breaks prints CRLF lines`() {
        val text = "a = [\r\n  { x = 1 }, # one\r\n  # above\r\n  { x = 2 },\r\n]\r\n"
        assertEquals("[[a]] # one\r\nx = 1\r\n\r\n# above\r\n[[a]]\r\nx = 2\r\n", expand(text, "a"))
    }

    @Test
    fun `an inline table written as dotted keys becomes a table keeping each value's comment`() {
        val doc = parse("t = {\n  a = 1, # a\n  b = 2 # b\n}\n")
        doc["t"].getOrFail().inlineTableOrNull!!.setDotted(true).getOrFail()
        doc["t"].getOrFail().setInline(false).getOrFail()
        assertEquals("[t]\na = 1 # a\nb = 2 # b\n", doc.toString())
    }

    @Test
    fun `an array of tables made inline in TOML 1_1 and expanded again prints as before`() {
        val text = "# first\n[[s]] # ac\nn = 1 # one\n\n[[s]]\nn = 2\n"
        val doc = parse(text)
        doc["s"].getOrFail().setInline(true).getOrFail()
        doc["s"].getOrFail().setInline(false).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an array of tables made inline in TOML 1_0 and expanded again prints as before`() {
        val text = "# first\n[[s]] # ac\nn = 1\n\n# second\n[[s]]\nn = 2\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc["s"].getOrFail().setInline(true).getOrFail()
        doc["s"].getOrFail().setInline(false).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table expanded in a document with CRLF line breaks prints CRLF lines`() {
        assertEquals("a = 1\r\n\r\n[t] # c\r\nx = 1\r\n", expand("a = 1\r\nt = { x = 1 } # c\r\n", "t"))
    }

    @Test
    fun `the slot holds the table after it expands`() {
        val doc = parse("t = { x = 1 }\n")
        val slot = doc["t"].getOrFail()
        slot.setInline(false).getOrFail()
        slot["y"] = 2L
        assertEquals("[t]\nx = 1\ny = 2\n", doc.toString())
        assertNotNull(doc["t"].getOrFail().standardTableOrNull)
    }

    @Test
    fun `an inline table taken before the slot expands no longer belongs to the document`() {
        val doc = parse("t = { x = 1 }\n")
        val old = doc["t"].getOrFail().inlineTableOrNull!!
        doc["t"].getOrFail().setInline(false).getOrFail()
        old["y"] = 2L
        assertEquals("[t]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table or an array of tables stays as it is`() {
        for (text in listOf("[t]\nx = 1\n", "t.x = 1\n", "[[t]]\nx = 1\n")) {
            assertEquals(text, expand(text, "t"), text)
        }
    }

    @Test
    fun `a table in an array of tables stays as it is`() {
        val doc = parse("[[t]]\nx = 1\n")
        doc["t"][0].getOrFail().setInline(false).getOrFail()
        assertEquals("[[t]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a scalar is NotExpandable and changes nothing`() {
        val doc = parse("t = 1 # c\n")
        assertEquals(TomlEditError.NotExpandable(TomlType.Integer), doc["t"].getOrFail().setInline(false).errorOrFail())
        assertEquals("t = 1 # c\n", doc.toString())
    }

    @Test
    fun `an array that does not hold only inline tables is NotExpandable and changes nothing`() {
        for (text in listOf("t = [1, 2]\n", "t = [{ x = 1 }, 2]\n", "t = []\n")) {
            val doc = parse(text)
            val error = doc["t"].getOrFail().setInline(false).errorOrFail()
            assertEquals(TomlEditError.NotExpandable(TomlType.Array), error)
            assertEquals(text, doc.toString())
        }
    }

    @Test
    fun `an inline table inside an inline table or an array is NotAValue and changes nothing`() {
        val text = "t = { u = { x = 1 }, a = [{ x = 1 }] }\nb = [{ x = 1 }]\n"
        val doc = parse(text)
        assertEquals(TomlEditError.NotAValue(TomlType.Table), doc["t"]["u"].getOrFail().setInline(false).errorOrFail())
        val array = doc["t"]["a"].getOrFail().setInline(false).errorOrFail()
        assertEquals(TomlEditError.NotAValue(TomlType.ArrayOfTables), array)
        assertEquals(TomlEditError.NotAValue(TomlType.Table), doc["b"][0].getOrFail().setInline(false).errorOrFail())
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table in an item stored nowhere becomes a table`() {
        val inline = InlineTable()
        inline["x"] = 1L
        val item = Item(Value.InlineTable(inline))
        item.setInline(false).getOrFail()
        assertEquals("x = 1\n", item.standardTableOrNull.toString())
    }
}
