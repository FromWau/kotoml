package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Renaming a key keeps its entry where it prints, with its comments, spacing and value. */
class RenameTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    @Test
    fun `a renamed key keeps its place and comments and spacing`() {
        val doc = parse("# top\n\n# about a\na   =   1 # one\nb = 2\n")
        doc.rename("a", "c").getOrFail()
        assertEquals("# top\n\n# about a\nc   =   1 # one\nb = 2\n", doc.toString())
        assertEquals(listOf("c", "b"), doc.keys.toList())
    }

    @Test
    fun `a renamed key in a table keeps its indentation`() {
        val doc = parse("[t]\n  a = 1\n  # about b\n  b = 'x' # x\n  c = 3\n")
        doc["t"].rename("b", "z").getOrFail()
        assertEquals("[t]\n  a = 1\n  # about b\n  z = 'x' # x\n  c = 3\n", doc.toString())
    }

    @Test
    fun `a renamed table renames the headers of its sub-tables where they print`() {
        val doc = parse(
            "# about old\n[old] # header\na = 1\n\n[other]\nb = 2\n\n# about x\n[old.x] # x\nc = 3\n" +
                "[old.x.y]\nd = 4\n",
        )
        doc.rename("old", "new").getOrFail()
        assertEquals(
            "# about old\n[new] # header\na = 1\n\n[other]\nb = 2\n\n# about x\n[new.x] # x\nc = 3\n" +
                "[new.x.y]\nd = 4\n",
            doc.toString(),
        )
    }

    @Test
    fun `an implicit table renames the headers under it`() {
        val doc = parse("[old.x]\na = 1\n[old.y]\nb = 2\n")
        doc.rename("old", "new").getOrFail()
        assertEquals("[new.x]\na = 1\n[new.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a renamed sub-table keeps the spacing of each header`() {
        val doc = parse("[ a . old ]\nk = 1\n[ a.old .  x ]\nm = 2\n")
        doc["a"].rename("old", "new").getOrFail()
        assertEquals("[ a . new ]\nk = 1\n[ a.new .  x ]\nm = 2\n", doc.toString())
    }

    @Test
    fun `a renamed array of tables renames every header`() {
        val doc = parse("# first\n[[old]] # one\nn = 1\n\n[y]\n\n# second\n[[old]]\nn = 2\n[old.sub]\ns = 3\n")
        doc.rename("old", "new").getOrFail()
        assertEquals(
            "# first\n[[new]] # one\nn = 1\n\n[y]\n\n# second\n[[new]]\nn = 2\n[new.sub]\ns = 3\n",
            doc.toString(),
        )
    }

    @Test
    fun `a renamed dotted key renames every line of its table`() {
        val doc = parse("# about old.a\nold.a = 1 # a\nx = 0\nold . b = 2\n")
        doc.rename("old", "new").getOrFail()
        assertEquals("# about old.a\nnew.a = 1 # a\nx = 0\nnew . b = 2\n", doc.toString())
    }

    @Test
    fun `a key under a dotted key renames on its own line`() {
        val doc = parse("[t]\nold.a = 1\nold.b.c = 2\nold.b.d = 3\n")
        doc["t"]["old"].rename("b", "z").getOrFail()
        assertEquals("[t]\nold.a = 1\nold.z.c = 2\nold.z.d = 3\n", doc.toString())
    }

    @Test
    fun `a new key is quoted only when it must be`() {
        val doc = parse("'a' = 1\n\"b\" = 2\nc = 3\n")
        doc.rename("a", "x").getOrFail()
        doc.rename("b", "two words").getOrFail()
        doc.rename("c", "").getOrFail()
        assertEquals("x = 1\n\"two words\" = 2\n\"\" = 3\n", doc.toString())
    }

    @Test
    fun `a header renamed to a key needing quotes quotes it in every header`() {
        val doc = parse("[old]\na = 1\n[old.x]\nb = 2\n")
        doc.rename("old", "a.b").getOrFail()
        assertEquals("[\"a.b\"]\na = 1\n[\"a.b\".x]\nb = 2\n", doc.toString())
        assertEquals(2L, doc["a.b"]["x"]["b"].getOrFail().longOrNull)
    }

    @Test
    fun `a key in an inline table on one line renames in place`() {
        val doc = parse("t = { a = 1, b = 2 } # t\n")
        doc["t"].getOrFail().inlineTableOrNull!!.rename("a", "c").getOrFail()
        assertEquals("t = { c = 1, b = 2 } # t\n", doc.toString())
    }

    @Test
    fun `a key in an inline table across lines keeps its comments`() {
        val doc = parse("t = {\n  # about a\n  a = 1, # one\n  b = 2,\n}\n")
        doc["t"].rename("a", "c").getOrFail()
        assertEquals("t = {\n  # about a\n  c = 1, # one\n  b = 2,\n}\n", doc.toString())
    }

    @Test
    fun `a dotted key in an inline table renames every entry under it`() {
        val doc = parse("t = { old.x = 1, y = 0, old . z = 2 }\n")
        doc["t"].rename("old", "new").getOrFail()
        assertEquals("t = { new.x = 1, y = 0, new . z = 2 }\n", doc.toString())
    }

    @Test
    fun `a rename keeps each line ending`() {
        val doc = parse("a = 1\r\n# about old\r\n[old]\r\nb = 2\r\n[old.x]\r\nc.d = 3\r\n")
        doc.rename("old", "new").getOrFail()
        doc["new"]["x"].rename("c", "e").getOrFail()
        assertEquals("a = 1\r\n# about old\r\n[new]\r\nb = 2\r\n[new.x]\r\ne.d = 3\r\n", doc.toString())
    }

    @Test
    fun `a rename in a TOML 1_0 document writes TOML 1_0`() {
        val doc = parse("[old]\na = 1\n", TomlVersion.V1_0)
        doc.rename("old", "\u001B").getOrFail()
        doc["\u001B"].rename("a", "\u0001").getOrFail()
        assertEquals("[\"\\u001B\"]\n\"\\u0001\" = 1\n", doc.toString())
        parse(doc.toString(), TomlVersion.V1_0)
    }

    @Test
    fun `a missing key is NotFound and changes nothing`() {
        val text = "a = 1\n"
        val doc = parse(text)
        assertEquals(TomlEditError.NotFound("x"), doc.rename("x", "y").errorOrFail())
        assertEquals(TomlEditError.NotFound("x"), doc.rename("x", "x").errorOrFail())
        assertEquals(text, doc.toString())
    }

    @Test
    fun `renaming onto a key already there is KeyTaken and changes nothing`() {
        val text = "a = 1 # a\nb = 2 # b\nt = { c = 3, d = 4 }\n"
        val doc = parse(text)
        assertEquals(TomlEditError.KeyTaken("b"), doc.rename("a", "b").errorOrFail())
        assertEquals(TomlEditError.KeyTaken("d"), doc["t"].rename("c", "d").errorOrFail())
        assertEquals(text, doc.toString())
    }

    @Test
    fun `renaming a key to itself changes nothing`() {
        val text = "'a' = 1 # a\n[t]\n"
        val doc = parse(text)
        doc.rename("a", "a").getOrFail()
        doc.rename("t", "t").getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `renaming in an item that holds no table is NotATable`() {
        val doc = parse("a = 1\n")
        assertEquals(TomlEditError.NotATable("x", TomlType.Integer), doc["a"].rename("x", "y").errorOrFail())
        assertEquals(TomlEditError.NotFound("b"), doc["b"].rename("x", "y").errorOrFail())
    }

    @Test
    fun `an item taken before a rename stays live`() {
        val doc = parse("# about a\na = 1 # one\n[old]\nk = 1\n")
        val value = doc["a"].getOrFail()
        val table = doc["old"].getOrFail()
        doc.rename("a", "b").getOrFail()
        doc.rename("old", "new").getOrFail()
        assertSame(value, doc["b"].getOrFail())
        assertSame(table, doc["new"].getOrFail())
        value.assign(Item(Value.Integer(5L))).getOrFail()
        table["m"] = 2
        assertEquals("# about a\nb = 5 # one\n[new]\nk = 1\nm = 2\n", doc.toString())
    }

    @Test
    fun `an item taken before a rename changes its layout and detaches when removed`() {
        val doc = parse("a = 1 # one\nt = { x = 1 }\n")
        val value = doc["a"].getOrFail()
        val inline = doc["t"].getOrFail()
        doc.rename("a", "b").getOrFail()
        doc.rename("t", "u").getOrFail()
        inline.setLayout(TableLayout.Header).getOrFail()
        assertEquals("b = 1 # one\n\n[u]\nx = 1\n", doc.toString())
        assertSame(value, doc.remove("b"))
        value.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals("[u]\nx = 1\n", doc.toString())
    }
}
