package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
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
        dst.set("db", parse(db)["db"].assertSuccess()).assertSuccess()
        assertEquals("# about db\n[db] # primary\nurl = \"x\" # local\n", dst.toString())
    }

    @Test
    fun `a table stored below other lines prints its comments below the blank line before its header`() {
        val dst = parse("[app]\nname = 1\n")
        dst.set("db", parse(db)["db"].assertSuccess()).assertSuccess()
        assertEquals("[app]\nname = 1\n\n# about db\n[db] # primary\nurl = \"x\" # local\n", dst.toString())
    }

    @Test
    fun `a copy of a table stores as the table does`() {
        val viaItem = parse("a = 0\n")
        viaItem.set("db", parse(db)["db"].assertSuccess().copy()).assertSuccess()
        val viaTable = parse("a = 0\n")
        viaTable.set("db", parse(db)["db"].assertSuccess().standardTableOrNull!!.copy()).assertSuccess()
        val stored = parse("a = 0\n")
        stored.set("db", parse(db)["db"].assertSuccess()).assertSuccess()
        assertEquals("a = 0\n\n# about db\n[db] # primary\nurl = \"x\" # local\n", stored.toString())
        assertEquals(stored.toString(), viaItem.toString())
        assertEquals(stored.toString(), viaTable.toString())
    }

    @Test
    fun `a value stored under a new key brings the comment lines above it and its same-line comment`() {
        val doc = parse("a = 1\n")
        doc.set("y", parse("# free\n\n# about y\ny = 5 # five\n")["y"].assertSuccess()).assertSuccess()
        assertEquals("a = 1\n# about y\ny = 5 # five\n", doc.toString())
    }

    @Test
    fun `a copy of a value stored twice brings its comments to both keys`() {
        val copy = parse("# about x\nx = 1 # one\n")["x"].assertSuccess().copy()
        val doc = parse("a = 0\n")
        doc.set("x", copy).assertSuccess()
        doc.set("y", copy).assertSuccess()
        assertEquals("a = 0\n# about x\nx = 1 # one\n# about x\ny = 1 # one\n", doc.toString())
    }

    @Test
    fun `a value with no comments of its own keeps the comments of the slot it replaces`() {
        val doc = parse("# about x\nx = 1 # one\n")
        doc.set("x", 2L).assertSuccess()
        assertEquals("# about x\nx = 2 # one\n", doc.toString())
    }

    @Test
    fun `a value read without comments keeps the comments of the slot it replaces`() {
        val doc = parse("# about x\nx = 1 # one\n")
        doc.set("x", parse("y = 5\n")["y"].assertSuccess()).assertSuccess()
        assertEquals("# about x\nx = 5 # one\n", doc.toString())
    }

    @Test
    fun `a value that brings comments replaces the comments of the slot`() {
        val doc = parse("# free\n\n# about x\nx = 1 # one\n")
        doc.set("x", parse("# about y\ny = 5 # five\n")["y"].assertSuccess()).assertSuccess()
        assertEquals("# free\n\n# about y\nx = 5 # five\n", doc.toString())
    }

    @Test
    fun `a table that brings comments replaces the header comments of the slot and keeps its free lines`() {
        val doc = parse("# keep\n\n# about a\n[a] # ah\nx = 1\n")
        doc.set("a", parse("# free\n\n# about b\n[b] # bh\nk = 2\n")["b"].assertSuccess()).assertSuccess()
        assertEquals("# keep\n\n# about b\n[a] # bh\nk = 2\n", doc.toString())
    }

    @Test
    fun `a table with no comments of its own keeps the header comments of the slot`() {
        val doc = parse("# keep\n\n# about a\n[a] # ah\nx = 1\n")
        doc.set("a", parse("[b]\nk = 2\n")["b"].assertSuccess()).assertSuccess()
        assertEquals("# keep\n\n# about a\n[a] # ah\nk = 2\n", doc.toString())
    }

    @Test
    fun `a value stored in an indented table indents the comment lines it brings like the line above`() {
        val doc = parse("[t]\n  a = 0\n")
        doc["t"].set("x", parse("# about x\nx = 1 # one\n")["x"].assertSuccess()).assertSuccess()
        assertEquals("[t]\n  a = 0\n  # about x\n  x = 1 # one\n", doc.toString())
    }

    @Test
    fun `a value stored in a document with CRLF line endings brings its comment lines ending in CRLF`() {
        val doc = parse("a = 0\r\n")
        doc.set("x", parse("# about x\nx = 1 # one\n")["x"].assertSuccess()).assertSuccess()
        assertEquals("a = 0\r\n# about x\r\nx = 1 # one\r\n", doc.toString())
    }

    @Test
    fun `a removed value stored again brings the comment lines above it`() {
        val source = parse("y = 0\n# about x\nx = 1 # one\n")
        val doc = parse("a = 0\n")
        doc.set("x", source.remove("x", force = true).assertSuccess()).assertSuccess()
        assertEquals("a = 0\n# about x\nx = 1 # one\n", doc.toString())
    }

    @Test
    fun `a removed table stored again prints its comments below the blank line before its header`() {
        val source = parse("# about db\n[db] # primary\nurl = 1\n")
        val doc = parse("a = 0\n")
        doc.set("db", source.remove("db", force = true).assertSuccess()).assertSuccess()
        assertEquals("a = 0\n\n# about db\n[db] # primary\nurl = 1\n", doc.toString())
    }

    @Test
    fun `a table built from pairs brings the comments of an item read from a document`() {
        val table = standardTableOf("k" to parse("# about x\nx = 1 # one\n")["x"].assertSuccess()).assertSuccess()
        val doc = parse("a = 0\n")
        doc.set("t", table).assertSuccess()
        assertEquals("a = 0\n\n[t]\n# about x\nk = 1 # one\n", doc.toString())
    }

    @Test
    fun `an array of tables stored under a new key brings the comments above its first header`() {
        val source = parse("# free\n\n# about arr\n[[arr]] # first\nx = 1\n\n# second\n[[arr]] # two\nx = 2\n")
        val doc = parse("[app]\n")
        doc.set("arr", source["arr"].assertSuccess()).assertSuccess()
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
        doc.set("b", source["a"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()
        assertEquals("z = 0\n\n# second\n[[b]] # h2\nn = 2\n\n# first\n[[b]] # h1\nn = 1\n", doc.toString())
    }

    @Test
    fun `an element assigned by index brings its comments in place of the element's`() {
        val source = parse("a = [\n  # about one\n  1, # one\n]\n")
        val doc = parse("b = [\n  # about two\n  2, # two\n  3,\n]\n")
        doc["b"].set(0, source["a"][0].assertSuccess()).assertSuccess()
        assertEquals("b = [\n  # about one\n  1, # one\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `an element assigned by index into an array on one line brings its comments onto lines of their own`() {
        val doc = parse("x = [1, 2]\n")
        doc["x"].set(0, parse("# about a\na = 5 # five\n")["a"].assertSuccess()).assertSuccess()
        assertEquals("x = [\n  # about a\n  5, # five\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `an element assigned by index with no comments keeps the place and comments of the slot`() {
        val doc = parse("b = [\n  # about two\n  2, # two\n  3,\n]\n")
        doc["b"].set(0, parse("a = 5\n")["a"].assertSuccess()).assertSuccess()
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
        doc["t"].set("x", parse("# about x\nx = 1 # one\n")["x"].assertSuccess()).assertSuccess()
        assertEquals("t = {\n  a = 0,\n  # about x\n  x = 1, # one\n}\n", doc.toString())
    }

    @Test
    fun `a value stored in an inline table of TOML 1 0 is refused and forced goes without its comments`() {
        val doc = parse("t = { a = 0 }\n", TomlVersion.V1_0)
        val x = parse("# about x\nx = 1 # one\n")["x"].assertSuccess()
        val table = doc["t"].assertSuccess().inlineTableOrNull!!
        val error = doc["t"].assertSuccess().set("x", x).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "x"), 2), error)
        assertEquals("t = { a = 0 }\n", doc.toString())
        table.putItem("x", x, force = true).assertSuccess()
        assertEquals("t = { a = 0, x = 1 }\n", doc.toString())
    }

    @Test
    fun `a value with a same-line comment put in an inline table of TOML 1 0 is refused and forced goes without it`() {
        val doc = parse("v = 1 # one\nt = { a = 1 }\n", TomlVersion.V1_0)
        val table = doc["t"].assertSuccess().inlineTableOrNull!!
        val v = doc["v"].assertSuccess().valueOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "v"), 1), table.put("v", v).assertError())
        assertEquals("v = 1 # one\nt = { a = 1 }\n", doc.toString())
        table.put("v", v, force = true).assertSuccess()
        assertEquals("v = 1 # one\nt = { a = 1, v = 1 }\n", doc.toString())
    }

    @Test
    fun `a value assigned to a slot of an inline table of TOML 1 0 is refused and forced goes without its comments`() {
        val doc = parse("t = { a = 0 }\n", TomlVersion.V1_0)
        val x = parse("# about x\nx = 1 # one\n")["x"].assertSuccess()
        val slot = doc["t"]["a"].assertSuccess()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "a"), 2), slot.assign(x).assertError())
        assertEquals("t = { a = 0 }\n", doc.toString())
        slot.assign(x, force = true).assertSuccess()
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a refused store under a parent read by indexing returns the error and changes nothing`() {
        val doc = parse("v = 1 # one\nt = { a = 1 }\n", TomlVersion.V1_0)
        val underIndex = doc["t"].set("v", doc["v"].assertSuccess()).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "v"), 1), underIndex)
        val table = doc["t"].assertSuccess().inlineTableOrNull!!
        val onTable = table.set("v", doc["v"].assertSuccess().valueOrNull!!)
            .assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "v"), 1), onTable)
        assertEquals("v = 1 # one\nt = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a value with a comment stored in a dotted table in braces across lines keeps the comment`() {
        val doc = parse("v = 1 # one\ns = {\n  b.c = 1,\n}\n")
        doc["s"]["b"].assertSuccess().set("d", doc["v"].assertSuccess()).assertSuccess()
        assertEquals("v = 1 # one\ns = {\n  b.c = 1,\n  b.d = 1, # one\n}\n", doc.toString())
    }

    @Test
    fun `a value with a comment stored in a dotted table in braces on one line spreads the braces across lines`() {
        val doc = parse("v = 1 # one\ns = { b.c = 1 }\n")
        doc["s"]["b"].assertSuccess().set("d", doc["v"].assertSuccess()).assertSuccess()
        assertEquals("v = 1 # one\ns = {\n  b.c = 1,\n  b.d = 1, # one\n}\n", doc.toString())
    }

    @Test
    fun `a value with a comment stored in a dotted table in braces of TOML 1 0 is refused`() {
        val doc = parse("v = 1 # one\ns = { b.c = 1 }\n", TomlVersion.V1_0)
        val error = doc["s"]["b"].assertSuccess().set("d", doc["v"].assertSuccess())
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("s", "b", "d"), 1), error.assertError())
        assertEquals("v = 1 # one\ns = { b.c = 1 }\n", doc.toString())
    }

    @Test
    fun `a value with a comment stored in dotted keys on lines of a table keeps the comment`() {
        val doc = parse("v = 1 # one\n[t]\nb.c = 1\n")
        doc["t"]["b"].assertSuccess().set("d", doc["v"].assertSuccess()).assertSuccess()
        assertEquals("v = 1 # one\n[t]\nb.c = 1\nb.d = 1 # one\n", doc.toString())
    }

    @Test
    fun `a value with a comment stored in a dotted table taken out of braces onto lines of a table keeps it`() {
        val doc = parse("v = 1 # one\ns = {\n  b.c = 1,\n}\n[t]\nx = 0\n")
        doc["t"].assertSuccess().set("b", doc["s"]["b"].assertSuccess()).assertSuccess()
        doc["t"]["b"].assertSuccess().set("d", doc["v"].assertSuccess()).assertSuccess()
        assertEquals("v = 1 # one\ns = {\n  b.c = 1,\n}\n[t]\nx = 0\nb.c = 1\nb.d = 1 # one\n", doc.toString())
    }

    @Test
    fun `putAll on an inline table of TOML 1 0 stores none of the pairs when one brings a comment`() {
        val doc = parse("v = 1 # one\nt = { a = 1 }\n", TomlVersion.V1_0)
        val table = doc["t"].assertSuccess().inlineTableOrNull!!
        val pairs = listOf("w" to Value.Integer(2L), "v" to doc["v"].assertSuccess().valueOrNull!!)
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "v"), 1), table.putAll(pairs).assertError())
        assertEquals("v = 1 # one\nt = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a dotted table stored in braces on one line keeps the comment above it`() {
        val doc = parse("t = {\n  # about b\n  b.c = 1,\n}\nu = { x = 1 }\n")
        doc["u"].assertSuccess().set("b", doc["t"]["b"].assertSuccess()).assertSuccess()
        assertEquals("t = {\n  # about b\n  b.c = 1,\n}\nu = {\n  x = 1,\n  # about b\n  b.c = 1,\n}\n", doc.toString())
    }

    private val linesBelow = "a = [\n  1,\n  2 # on 2\n  # more\n  , 3,\n]\n"

    @Test
    fun `an element stored on a line of a table puts the comment lines below it above its key`() {
        val doc = parse(linesBelow)
        doc.set("b", doc["a"][1].assertSuccess()).assertSuccess()
        assertEquals("$linesBelow# more\nb = 2 # on 2\n", doc.toString())
    }

    @Test
    fun `an element value stored on a line of a table puts the comment lines below it above its key`() {
        val doc = parse(linesBelow)
        doc.set("b", doc["a"].assertSuccess().arrayOrNull!![1].assertSuccess()).assertSuccess()
        assertEquals("$linesBelow# more\nb = 2 # on 2\n", doc.toString())
    }

    @Test
    fun `an element added to another array keeps the comment lines between it and its comma`() {
        val doc = parse("${linesBelow}c = [9]\n")
        val element = doc["a"].assertSuccess().arrayOrNull!![1].assertSuccess()
        doc["c"].assertSuccess().arrayOrNull!!.add(element).assertSuccess()
        assertEquals("${linesBelow}c = [\n  9,\n  2 # on 2\n  # more\n  ,\n]\n", doc.toString())
    }

    @Test
    fun `an element assigned by index into another array keeps the comment lines between it and its comma`() {
        val doc = parse("${linesBelow}c = [8, 9]\n")
        doc["c"].set(0, doc["a"][1].assertSuccess()).assertSuccess()
        assertEquals("${linesBelow}c = [\n  2 # on 2\n  # more\n  ,\n  9,\n]\n", doc.toString())
    }

    @Test
    fun `an element stored in an inline table keeps the comment lines between it and its comma`() {
        val doc = parse("${linesBelow}t = { z = 0 }\n")
        doc["t"].assertSuccess().set("x", doc["a"][1].assertSuccess()).assertSuccess()
        assertEquals("${linesBelow}t = {\n  z = 0,\n  x = 2 # on 2\n  # more\n  ,\n}\n", doc.toString())
    }

    private fun dottedOnTableLines(): TomlDocument {
        val doc = parse("${linesBelow}t = { d.x = 1 }\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        return doc
    }

    @Test
    fun `an element stored in a dotted table on a table's lines puts the comment lines below it above its key`() {
        val doc = dottedOnTableLines()
        doc["t"]["d"].set("b", doc["a"][1].assertSuccess()).assertSuccess()
        assertEquals("$linesBelow\n[t]\nd.x = 1\n# more\nd.b = 2 # on 2\n", doc.toString())
    }

    @Test
    fun `an element value put in a dotted table on a table's lines puts the comment lines below it above its key`() {
        val doc = dottedOnTableLines()
        val element = doc["a"].assertSuccess().arrayOrNull!![1].assertSuccess()
        doc["t"]["d"].assertSuccess().inlineTableOrNull!!.put("b", element).assertSuccess()
        assertEquals("$linesBelow\n[t]\nd.x = 1\n# more\nd.b = 2 # on 2\n", doc.toString())
    }

    @Test
    fun `an element replacing a dotted key on a table's lines puts the comment lines below it above the line`() {
        val doc = dottedOnTableLines()
        doc["t"]["d"].set("x", doc["a"][1].assertSuccess()).assertSuccess()
        assertEquals("$linesBelow\n[t]\n# more\nd.x = 2 # on 2\n", doc.toString())
    }

    @Test
    fun `the comment on a line holding only the comma after an element goes along with it`() {
        val doc = parse("a = [\n  1,\n  2\n  , # c\n  3,\n]\n")
        doc.set("b", doc["a"][1].assertSuccess()).assertSuccess()
        assertEquals("a = [\n  1,\n  2\n  , # c\n  3,\n]\nb = 2 # c\n", doc.toString())
    }

    @Test
    fun `a copy of a dotted table in braces keeps the comment after the comma of its last line`() {
        val doc = parse("s = {\n  b.c = 1, # on c\n  y = 5,\n}\n[t]\nx = 0\n")
        doc["t"].set("b", doc["s"]["b"].assertSuccess()).assertSuccess()
        assertEquals("s = {\n  b.c = 1, # on c\n  y = 5,\n}\n[t]\nx = 0\nb.c = 1 # on c\n", doc.toString())
    }

    @Test
    fun `the comment after an opening brace stays with its table when a dotted table inside is copied`() {
        val doc = parse("s = { # on s\n  b.c = 1,\n  y = 5,\n}\n[t]\nx = 0\n")
        doc["t"].set("b", doc["s"]["b"].assertSuccess()).assertSuccess()
        assertEquals("s = { # on s\n  b.c = 1,\n  y = 5,\n}\n[t]\nx = 0\nb.c = 1\n", doc.toString())
    }

    @Test
    fun `two copies of one dotted table stored in one table print their lines together`() {
        val doc = parse("s = {\n  b.c = 1,\n  b.d = 2,\n}\n[t]\nx = 0\n")
        doc["t"].set("b", doc["s"]["b"].assertSuccess()).assertSuccess()
        doc["t"].set("e", doc["s"]["b"].assertSuccess()).assertSuccess()
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
        doc.set("a", removed).assertSuccess()
        assertEquals("x = 0\n\n# free\n", source.toString())
        assertEquals("y = 1\n# about a\na.b = 1\na.c = 2\n", doc.toString())
    }

    @Test
    fun `blank lines inside a copied table stay`() {
        val doc = parse("a = 0\n")
        doc.set("t", parse("[t]\nx = 1\n\n# about y\ny = 2\n")["t"].assertSuccess()).assertSuccess()
        assertEquals("a = 0\n\n[t]\nx = 1\n\n# about y\ny = 2\n", doc.toString())
    }

    @Test
    fun `a copy of a stored table brings the comment lines the store put above its header`() {
        val doc = parse("# about a\n[a]\nn = 1\n\n[b]\nm = 2\n")
        doc["b"].set("c", doc["a"].assertSuccess()).assertSuccess()
        doc.set("d", doc["b"]["c"].assertSuccess()).assertSuccess()
        assertEquals("# about a\n[d]\nn = 1\n", doc.toString().substringAfter("[b.c]\nn = 1\n\n"))
    }

    @Test
    fun `a copy of a stored table of an array of tables brings the comment lines above its header`() {
        val doc = parse("# first\n[[x]]\nn = 1\n\n# second\n[[x]]\nn = 2\n\n[y]\n")
        doc["y"].set("q", doc["x"].assertSuccess().arrayOfTablesOrNull!![1].assertSuccess()).assertSuccess()
        doc.set("z", doc["y"]["q"].assertSuccess()).assertSuccess()
        assertEquals("# second\n[z]\nn = 2\n", doc.toString().substringAfter("[y.q]\nn = 2\n\n"))
    }

    private val afterComma = "a = [\n  1, # on 1\n  2,\n]\n"

    private fun TomlDocument.arrayA(): TomlArray = this["a"].assertSuccess().arrayOrNull!!

    @Test
    fun `an element value stored on a line of a table carries the comment after its comma`() {
        val doc = parse(afterComma)
        doc.set("b", doc.arrayA()[0].assertSuccess()).assertSuccess()
        assertEquals("${afterComma}b = 1 # on 1\n", doc.toString())
    }

    @Test
    fun `an element value taken by iterating stored on a line of a table carries the comment after its comma`() {
        val doc = parse(afterComma)
        doc.set("b", doc.arrayA().first()).assertSuccess()
        assertEquals("${afterComma}b = 1 # on 1\n", doc.toString())
    }

    @Test
    fun `an element removed by force carries the comment after its comma`() {
        val doc = parse(afterComma)
        val removed = doc.arrayA().removeAt(0, force = true).assertSuccess()
        doc.set("b", removed).assertSuccess()
        assertEquals("a = [\n  2,\n]\nb = 1 # on 1\n", doc.toString())
    }

    @Test
    fun `an element value added to another array carries the comment after its comma`() {
        val doc = parse("${afterComma}c = [9]\n")
        doc["c"].assertSuccess().arrayOrNull!!.add(doc.arrayA()[0].assertSuccess()).assertSuccess()
        assertEquals("${afterComma}c = [\n  9,\n  1, # on 1\n]\n", doc.toString())
    }

    @Test
    fun `an element value replacing an element brings the comment after its comma in place of the slot's`() {
        val doc = parse("${afterComma}c = [\n  7, # seven\n  8,\n]\n")
        doc["c"].assertSuccess().arrayOrNull!!.replace(0, doc.arrayA()[0].assertSuccess()).assertSuccess()
        assertEquals("${afterComma}c = [\n  1, # on 1\n  8,\n]\n", doc.toString())
    }

    @Test
    fun `an element value without a comment replacing an element keeps the slot's comment`() {
        val doc = parse("${afterComma}c = [\n  7, # seven\n  8,\n]\n")
        doc["c"].assertSuccess().arrayOrNull!!.replace(0, doc.arrayA()[1].assertSuccess()).assertSuccess()
        assertEquals("${afterComma}c = [\n  2, # seven\n  8,\n]\n", doc.toString())
    }

    private val entryAfterComma = "t = {\n  x = 1, # on x\n  y = 2,\n}\n"

    private fun TomlDocument.inlineT(): InlineTable = this["t"].assertSuccess().inlineTableOrNull!!

    @Test
    fun `an inline table entry value stored on a line of a table carries the comment after its comma`() {
        val doc = parse(entryAfterComma)
        doc.set("c", doc.inlineT()["x"].assertSuccess()).assertSuccess()
        assertEquals("${entryAfterComma}c = 1 # on x\n", doc.toString())
    }

    @Test
    fun `an inline table entry value put in another inline table carries the comment after its comma`() {
        val doc = parse("${entryAfterComma}u = { z = 0 }\n")
        doc["u"].assertSuccess().inlineTableOrNull!!.put("x", doc.inlineT()["x"].assertSuccess()).assertSuccess()
        assertEquals("${entryAfterComma}u = {\n  z = 0,\n  x = 1, # on x\n}\n", doc.toString())
    }

    @Test
    fun `an inline table entry removed by force carries the comment after its comma`() {
        val doc = parse(entryAfterComma)
        val removed = doc.inlineT().remove("x", force = true).assertSuccess()
        doc.set("c", removed).assertSuccess()
        assertEquals("t = {\n  y = 2,\n}\nc = 1 # on x\n", doc.toString())
    }

    @Test
    fun `an element value with the comment after its comma put in a TOML 1_0 inline table is refused`() {
        val doc = parse("${afterComma}u = { z = 0 }\n", TomlVersion.V1_0)
        val error = doc["u"].assertSuccess().inlineTableOrNull!!.put("x", doc.arrayA()[0].assertSuccess())
            .assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("u", "x"), 1), error)
        assertEquals("${afterComma}u = { z = 0 }\n", doc.toString())
    }
}
