package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Storing or copying an item takes the comments it owns: the comment lines directly above it and its same-line
 * comment, a table's those of its header, and everything inside it; comment lines set apart by a blank line stay. A
 * replacement keeps the slot's comments unless the new item brings its own, which take their place.
 */
class StoreCarriesCommentsTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    private val db = "# loose note\n\n# about db\n[db] # primary\nurl = \"x\" # local\n"

    @Test
    fun `a stored table brings the comment lines above its header and its header comment but not the free ones`() {
        val dst = TomlDocument()
        dst["db"] = parse(db)["db"].assertSuccess()
        assertEquals("# about db\n[db] # primary\nurl = \"x\" # local\n", dst.toString())
    }

    @Test
    fun `a table stored below other lines prints its comments below the blank line before its header`() {
        val dst = parse("[app]\nname = 1\n")
        dst["db"] = parse(db)["db"].assertSuccess()
        assertEquals("[app]\nname = 1\n\n# about db\n[db] # primary\nurl = \"x\" # local\n", dst.toString())
    }

    @Test
    fun `a copy of a table stores as the table does`() {
        val viaItem = parse("a = 0\n")
        viaItem["db"] = parse(db)["db"].assertSuccess().copy()
        val viaTable = parse("a = 0\n")
        viaTable["db"] = parse(db)["db"].assertSuccess().standardTableOrNull!!.copy()
        val stored = parse("a = 0\n")
        stored["db"] = parse(db)["db"].assertSuccess()
        assertEquals("a = 0\n\n# about db\n[db] # primary\nurl = \"x\" # local\n", stored.toString())
        assertEquals(stored.toString(), viaItem.toString())
        assertEquals(stored.toString(), viaTable.toString())
    }

    @Test
    fun `a value stored under a new key brings the comment lines above it and its same-line comment`() {
        val doc = parse("a = 1\n")
        doc["y"] = parse("# free\n\n# about y\ny = 5 # five\n")["y"].assertSuccess()
        assertEquals("a = 1\n# about y\ny = 5 # five\n", doc.toString())
    }

    @Test
    fun `a copy of a value stored twice brings its comments to both keys`() {
        val copy = parse("# about x\nx = 1 # one\n")["x"].assertSuccess().copy()
        val doc = parse("a = 0\n")
        doc["x"] = copy
        doc["y"] = copy
        assertEquals("a = 0\n# about x\nx = 1 # one\n# about x\ny = 1 # one\n", doc.toString())
    }

    @Test
    fun `a value with no comments of its own keeps the comments of the slot it replaces`() {
        val doc = parse("# about x\nx = 1 # one\n")
        doc["x"] = 2L
        assertEquals("# about x\nx = 2 # one\n", doc.toString())
    }

    @Test
    fun `a value read without comments keeps the comments of the slot it replaces`() {
        val doc = parse("# about x\nx = 1 # one\n")
        doc["x"] = parse("y = 5\n")["y"].assertSuccess()
        assertEquals("# about x\nx = 5 # one\n", doc.toString())
    }

    @Test
    fun `a value that brings comments replaces the comments of the slot`() {
        val doc = parse("# free\n\n# about x\nx = 1 # one\n")
        doc["x"] = parse("# about y\ny = 5 # five\n")["y"].assertSuccess()
        assertEquals("# free\n\n# about y\nx = 5 # five\n", doc.toString())
    }

    @Test
    fun `a table that brings comments replaces the header comments of the slot and keeps its free lines`() {
        val doc = parse("# keep\n\n# about a\n[a] # ah\nx = 1\n")
        doc["a"] = parse("# free\n\n# about b\n[b] # bh\nk = 2\n")["b"].assertSuccess()
        assertEquals("# keep\n\n# about b\n[a] # bh\nk = 2\n", doc.toString())
    }

    @Test
    fun `a table with no comments of its own keeps the header comments of the slot`() {
        val doc = parse("# keep\n\n# about a\n[a] # ah\nx = 1\n")
        doc["a"] = parse("[b]\nk = 2\n")["b"].assertSuccess()
        assertEquals("# keep\n\n# about a\n[a] # ah\nk = 2\n", doc.toString())
    }

    @Test
    fun `a value stored in an indented table indents the comment lines it brings like the line above`() {
        val doc = parse("[t]\n  a = 0\n")
        doc["t"]["x"] = parse("# about x\nx = 1 # one\n")["x"].assertSuccess()
        assertEquals("[t]\n  a = 0\n  # about x\n  x = 1 # one\n", doc.toString())
    }

    @Test
    fun `a value stored in a document with CRLF line endings brings its comment lines ending in CRLF`() {
        val doc = parse("a = 0\r\n")
        doc["x"] = parse("# about x\nx = 1 # one\n")["x"].assertSuccess()
        assertEquals("a = 0\r\n# about x\r\nx = 1 # one\r\n", doc.toString())
    }

    @Test
    fun `a removed value stored again brings the comment lines above it`() {
        val source = parse("y = 0\n# about x\nx = 1 # one\n")
        val doc = parse("a = 0\n")
        doc["x"] = source.remove("x", force = true).assertSuccess()
        assertEquals("a = 0\n# about x\nx = 1 # one\n", doc.toString())
    }

    @Test
    fun `a removed table stored again prints its comments below the blank line before its header`() {
        val source = parse("# about db\n[db] # primary\nurl = 1\n")
        val doc = parse("a = 0\n")
        doc["db"] = source.remove("db", force = true).assertSuccess()
        assertEquals("a = 0\n\n# about db\n[db] # primary\nurl = 1\n", doc.toString())
    }

    @Test
    fun `a table built from pairs brings the comments of an item read from a document`() {
        val table = standardTableOf("k" to parse("# about x\nx = 1 # one\n")["x"].assertSuccess()).assertSuccess()
        val doc = parse("a = 0\n")
        doc["t"] = table
        assertEquals("a = 0\n\n[t]\n# about x\nk = 1 # one\n", doc.toString())
    }

    @Test
    fun `an array of tables stored under a new key brings the comments above its first header`() {
        val source = parse("# free\n\n# about arr\n[[arr]] # first\nx = 1\n\n# second\n[[arr]] # two\nx = 2\n")
        val doc = parse("[app]\n")
        doc["arr"] = source["arr"].assertSuccess()
        val expected = "[app]\n\n# about arr\n[[arr]] # first\nx = 1\n\n# second\n[[arr]] # two\nx = 2\n"
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `a table added to an array of tables brings its comments below the blank line before its header`() {
        val table = parse("# free\n\n# above\n[[s]] # head\nx = 1\n")["s"][0].assertSuccess().standardTableOrNull!!
        val doc = parse("[[t]]\ny = 0\n")
        doc["t"].assertSuccess().arrayOfTablesOrNull!!.add(table).assertSuccess()
        assertEquals("[[t]]\ny = 0\n\n# above\n[[t]] # head\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table replacing an element of an array of tables brings its comments in place of the element's`() {
        val table = parse("# above\n[[s]] # head\nx = 1\n")["s"][0].assertSuccess().standardTableOrNull!!
        val doc = parse("# free\n\n# about t\n[[t]] # t0\ny = 0\n")
        doc["t"].assertSuccess().arrayOfTablesOrNull!!.replace(0, table).assertSuccess()
        assertEquals("# free\n\n# above\n[[t]] # head\nx = 1\n", doc.toString())
    }

    @Test
    fun `tables of a document gathered into an array of tables stored under a new key bring their comments`() {
        val source = parse("# first\n[[a]] # h1\nn = 1\n\n# second\n[[a]] # h2\nn = 2\n")
        val doc = parse("z = 0\n")
        doc["b"] = source["a"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess()
        assertEquals("z = 0\n\n# second\n[[b]] # h2\nn = 2\n\n# first\n[[b]] # h1\nn = 1\n", doc.toString())
    }

    @Test
    fun `an element assigned by index brings its comments in place of the element's`() {
        val source = parse("a = [\n  # about one\n  1, # one\n]\n")
        val doc = parse("b = [\n  # about two\n  2, # two\n  3,\n]\n")
        doc["b"][0] = source["a"][0].assertSuccess()
        assertEquals("b = [\n  # about one\n  1, # one\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `an element assigned by index into an array on one line brings its comments onto lines of their own`() {
        val doc = parse("x = [1, 2]\n")
        doc["x"][0] = parse("# about a\na = 5 # five\n")["a"].assertSuccess()
        assertEquals("x = [\n  # about a\n  5, # five\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `an element assigned by index with no comments keeps the place and comments of the slot`() {
        val doc = parse("b = [\n  # about two\n  2, # two\n  3,\n]\n")
        doc["b"][0] = parse("a = 5\n")["a"].assertSuccess()
        assertEquals("b = [\n  # about two\n  5, # two\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `a value assigned to a slot of an inline table brings its comments onto lines of their own`() {
        val doc = parse("t = { a = 0, b = 1 }\n")
        doc["t"]["a"].assertSuccess().assign(parse("# about x\nx = 1 # one\n")["x"].assertSuccess()).assertSuccess()
        assertEquals("t = {\n  # about x\n  a = 1, # one\n  b = 1,\n}\n", doc.toString())
    }

    @Test
    fun `a value stored in an inline table brings its comments onto lines of their own`() {
        val doc = parse("t = { a = 0 }\n")
        doc["t"]["x"] = parse("# about x\nx = 1 # one\n")["x"].assertSuccess()
        assertEquals("t = {\n  a = 0,\n  # about x\n  x = 1, # one\n}\n", doc.toString())
    }

    @Test
    fun `a value stored in an inline table of TOML 1 0 leaves its comments`() {
        val doc = parse("t = { a = 0 }\n", TomlVersion.V1_0)
        doc["t"]["x"] = parse("# about x\nx = 1 # one\n")["x"].assertSuccess()
        assertEquals("t = { a = 0, x = 1 }\n", doc.toString())
    }

    @Test
    fun `a copy of a dotted table in braces keeps the comment after the comma of its last line`() {
        val doc = parse("s = {\n  b.c = 1, # on c\n  y = 5,\n}\n[t]\nx = 0\n")
        doc["t"]["b"] = doc["s"]["b"].assertSuccess()
        assertEquals("s = {\n  b.c = 1, # on c\n  y = 5,\n}\n[t]\nx = 0\nb.c = 1 # on c\n", doc.toString())
    }

    @Test
    fun `the comment after an opening brace stays with its table when a dotted table inside is copied`() {
        val doc = parse("s = { # on s\n  b.c = 1,\n  y = 5,\n}\n[t]\nx = 0\n")
        doc["t"]["b"] = doc["s"]["b"].assertSuccess()
        assertEquals("s = { # on s\n  b.c = 1,\n  y = 5,\n}\n[t]\nx = 0\nb.c = 1\n", doc.toString())
    }

    @Test
    fun `two copies of one dotted table stored in one table print their lines together`() {
        val doc = parse("s = {\n  b.c = 1,\n  b.d = 2,\n}\n[t]\nx = 0\n")
        doc["t"]["b"] = doc["s"]["b"].assertSuccess()
        doc["t"]["e"] = doc["s"]["b"].assertSuccess()
        val stored = "[t]\nx = 0\nb.c = 1\nb.d = 2\ne.c = 1\ne.d = 2\n"
        assertEquals("s = {\n  b.c = 1,\n  b.d = 2,\n}\n$stored", doc.toString())
    }

    @Test
    fun `a copy of a dotted table across lines printed alone ends its last line before the brace`() {
        val doc = parse("s = {\n  b.c = 1, # on c\n  y = 5,\n}\n")
        assertEquals("{\n  c = 1 # on c\n}", doc["s"]["b"].assertSuccess().copy().toString())
    }

    @Test
    fun `a removed dotted table stored again prints the free lines above it once`() {
        val source = parse("x = 0\n\n# free\n\n# about a\na.b = 1\na.c = 2\n")
        val removed = source.remove("a", force = true).assertSuccess()
        val doc = parse("y = 1\n")
        doc["a"] = removed
        assertEquals("x = 0\n\n# free\n", source.toString())
        assertEquals("y = 1\n# about a\na.b = 1\na.c = 2\n", doc.toString())
    }

    @Test
    fun `blank lines inside a copied table stay`() {
        val doc = parse("a = 0\n")
        doc["t"] = parse("[t]\nx = 1\n\n# about y\ny = 2\n")["t"].assertSuccess()
        assertEquals("a = 0\n\n[t]\nx = 1\n\n# about y\ny = 2\n", doc.toString())
    }
}
