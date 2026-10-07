package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rules for items handed out by indexing: live views, copies on store, moves on removal, detachment. */
class HandleRulesTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

    private val banneredSource = "# Generated, do not edit\n\n[db] # db hdr\nurl = 1 # url c\n"

    @Test
    fun `an item read from a document writes through to it`() {
        val doc = parse("a = 1 # c\n")
        doc["a"].assertSuccess().valueOrNull!!.decor = Decor(" ", " # mine")
        doc["a"].assertSuccess().assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals("a = 2 # mine\n", doc.toString())
    }

    @Test
    fun `a copy of a table from another document stores as the table itself does`() {
        val stored = parse("[app]\nname = 1\n")
        stored.set("db", parse(banneredSource)["db"].assertSuccess()).assertSuccess()
        val copied = parse("[app]\nname = 1\n")
        copied.set("db", parse(banneredSource)["db"].assertSuccess().copy()).assertSuccess()
        assertEquals("[app]\nname = 1\n\n[db]\nurl = 1 # url c\n", copied.toString())
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a table stored in a new document keeps only the comments inside it`() {
        val src = parse("# Generated\n[db] # primary\nurl = \"x\" # local\n")
        val dst = TomlDocument()
        dst["db"] = src["db"].assertSuccess()
        assertEquals("[db]\nurl = \"x\" # local\n", dst.toString())
    }

    @Test
    fun `a copy of a value from another document leaves its same-line comment`() {
        val source = parse("a = 1 # from source\n")
        val doc = parse("x = 1\n")
        doc.set("a", source["a"].assertSuccess().copy()).assertSuccess()
        assertEquals("x = 1\na = 1\n", doc.toString())
    }

    @Test
    fun `a copy of a table from the same document stores as the table itself does`() {
        val text = "# banner\n[a] # a hdr\nk = 1 # k c\n\n[z]\n"
        val stored = parse(text)
        stored.set("b", stored["a"].assertSuccess()).assertSuccess()
        val copied = parse(text)
        copied.set("b", copied["a"].assertSuccess().copy()).assertSuccess()
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a copy of a value from the same document stores as the value itself does`() {
        val text = "a = [1, 2] # a c\n[t]\n"
        val stored = parse(text)
        stored.set("b", stored["a"].assertSuccess()).assertSuccess()
        val copied = parse(text)
        copied.set("b", copied["a"].assertSuccess().copy()).assertSuccess()
        assertEquals("a = [1, 2] # a c\nb = [1, 2]\n[t]\n", copied.toString())
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a copy of an array of tables from the same document stores as the array itself does`() {
        val text = "# about arr\n[[arr]] # first\nx = 1\n\n[[arr]]\nx = 2\n\n[z]\n"
        val stored = parse(text)
        stored["z"].assertSuccess().set("arr", stored["arr"].assertSuccess()).assertSuccess()
        val copied = parse(text)
        copied["z"].assertSuccess().set("arr", copied["arr"].assertSuccess().copy()).assertSuccess()
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a copy of a standard table from another document stores as the item's copy does`() {
        val viaItem = parse("[app]\nname = 1\n")
        viaItem.set("db", parse(banneredSource)["db"].assertSuccess().copy()).assertSuccess()
        val viaTable = parse("[app]\nname = 1\n")
        viaTable.set("db", parse(banneredSource)["db"].assertSuccess().standardTableOrNull!!.copy()).assertSuccess()
        assertEquals("[app]\nname = 1\n\n[db]\nurl = 1 # url c\n", viaTable.toString())
        assertEquals(viaItem.toString(), viaTable.toString())
    }

    @Test
    fun `a copy of a standard table from the same document stores as the item's copy does`() {
        val text = "# banner\n[a] # a hdr\nk = 1 # k c\n\n[z]\n"
        val viaItem = parse(text)
        viaItem.set("b", viaItem["a"].assertSuccess().copy()).assertSuccess()
        val viaTable = parse(text)
        viaTable.set("b", viaTable["a"].assertSuccess().standardTableOrNull!!.copy()).assertSuccess()
        assertEquals(viaItem.toString(), viaTable.toString())
    }

    @Test
    fun `a copy of an inline table from another document stores as the item's copy does`() {
        val source = "t = { x = 1 } # t c\n"
        val viaItem = parse("y = 1\n")
        viaItem.set("t", parse(source)["t"].assertSuccess().copy()).assertSuccess()
        val viaTable = parse("y = 1\n")
        viaTable.set("t", Value.InlineTable(parse(source)["t"].assertSuccess().inlineTableOrNull!!.copy())).assertSuccess()
        assertEquals("y = 1\nt = { x = 1 }\n", viaTable.toString())
        assertEquals(viaItem.toString(), viaTable.toString())
    }

    @Test
    fun `a copy of an array from another document stores as the item's copy does`() {
        val source = "a = [\n  1, # one\n] # a c\n"
        val viaItem = parse("y = 1\n")
        viaItem.set("a", parse(source)["a"].assertSuccess().copy()).assertSuccess()
        val viaArray = parse("y = 1\n")
        viaArray.set("a", Value.Array(parse(source)["a"].assertSuccess().arrayOrNull!!.copy())).assertSuccess()
        assertEquals("y = 1\na = [\n  1, # one\n]\n", viaArray.toString())
        assertEquals(viaItem.toString(), viaArray.toString())
    }

    @Test
    fun `a copy of an array value from another document stores as the item's copy does`() {
        val source = "a = [1, 2] # a c\n"
        val viaItem = parse("y = 1\n")
        viaItem.set("a", parse(source)["a"].assertSuccess().copy()).assertSuccess()
        val viaValue = parse("y = 1\n")
        viaValue.set("a", parse(source)["a"].assertSuccess().valueOrNull!!.copy()).assertSuccess()
        assertEquals("y = 1\na = [1, 2]\n", viaValue.toString())
        assertEquals(viaItem.toString(), viaValue.toString())
    }

    @Test
    fun `a copy of an array of tables from another document stores as the item's copy does`() {
        val source = "# about arr\n[[arr]] # first\nx = 1\n\n[[arr]] # second\nx = 2\n"
        val viaItem = parse("[app]\n")
        viaItem.set("arr", parse(source)["arr"].assertSuccess().copy()).assertSuccess()
        val viaArray = parse("[app]\n")
        viaArray.set("arr", parse(source)["arr"].assertSuccess().arrayOfTablesOrNull!!.copy()).assertSuccess()
        assertEquals("[app]\n\n[[arr]]\nx = 1\n\n[[arr]] # second\nx = 2\n", viaArray.toString())
        assertEquals(viaItem.toString(), viaArray.toString())
    }

    @Test
    fun `a copy of an array of tables from the same document stores as the item's copy does`() {
        val text = "# about arr\n[[arr]] # first\nx = 1\n\n[[arr]]\nx = 2\n\n[z]\n"
        val viaItem = parse(text)
        viaItem["z"].assertSuccess().set("arr", viaItem["arr"].assertSuccess().copy()).assertSuccess()
        val viaArray = parse(text)
        viaArray["z"].assertSuccess().set("arr", viaArray["arr"].assertSuccess().arrayOfTablesOrNull!!.copy()).assertSuccess()
        assertEquals(viaItem.toString(), viaArray.toString())
    }

    @Test
    fun `a copy of a removed table keeps its header comment`() {
        val table = parse(banneredSource).remove("db")!!.standardTableOrNull!!.copy()
        val doc = parse("[app]\nname = 1\n")
        doc.set("db", table).assertSuccess()
        assertEquals("[app]\nname = 1\n\n[db] # db hdr\nurl = 1 # url c\n", doc.toString())
    }

    @Test
    fun `a copy of a document keeps every comment`() {
        val text = "# Generated, do not edit\n\n[db] # db hdr\nurl = 1 # url c\n"
        assertEquals(text, parse(text).copy().toString())
    }

    @Test
    fun `a copy of a document's root keeps every comment and the version`() {
        val text = "# Generated, do not edit\n\n[db] # db hdr\nurl = 1 # url c\n"
        val source = TomlDocument.parse(text, TomlVersion.V1_0).assertSuccess()
        val copied = TomlDocument(source.root.copy())
        assertEquals(text, copied.toString())
        assertEquals(TomlVersion.V1_0, copied.version)
    }

    @Test
    fun `a table removed from a document keeps its header comment when stored in another`() {
        val source = parse(banneredSource)
        val table = source.remove("db")!!
        val doc = parse("[app]\nname = 1\n")
        doc.set("db", table).assertSuccess()
        assertEquals("[app]\nname = 1\n\n[db] # db hdr\nurl = 1 # url c\n", doc.toString())
        assertEquals("# Generated, do not edit\n", source.toString())
    }

    @Test
    fun `a value removed from a document keeps its same-line comment when stored in another`() {
        val value = parse("a = 1 # mine\n").remove("a")!!
        val doc = parse("x = 1\n")
        doc.set("a", value).assertSuccess()
        assertEquals("x = 1\na = 1 # mine\n", doc.toString())
    }

    @Test
    fun `tables moved with removeAt and add keep their header comments`() {
        val doc = parse("[[arr]] # one\nx = 1\n[[arr]] # two\nx = 2\n[[arr]] # three\nx = 3\n")
        val tables = doc["arr"].assertSuccess().arrayOfTablesOrNull!!
        for (index in 1 until tables.size) tables.add(0, tables.removeAt(index).assertSuccess()).assertSuccess()
        assertEquals("[[arr]] # three\nx = 3\n[[arr]] # two\nx = 2\n[[arr]] # one\nx = 1\n", doc.toString())
    }

    @Test
    fun `a comment a caller set on a value travels with a store into another document`() {
        val source = parse("a = 1 # read\n")
        source["a"].assertSuccess().valueOrNull!!.decor = Decor(" ", " # mine")
        val doc = parse("x = 1\n")
        doc.set("a", source["a"].assertSuccess()).assertSuccess()
        doc.set("b", source["a"].assertSuccess().copy()).assertSuccess()
        assertEquals("x = 1\na = 1 # mine\nb = 1 # mine\n", doc.toString())
    }

    @Test
    fun `a header comment a caller set on a table travels with a copy into another document`() {
        val source = parse("[db] # read\nurl = 1\n")
        source["db"].assertSuccess().standardTableOrNull!!.decor = Decor("# mine above\n", " # mine")
        val doc = parse("x = 1\n")
        doc.set("db", source["db"].assertSuccess().copy()).assertSuccess()
        assertEquals("x = 1\n# mine above\n[db] # mine\nurl = 1\n", doc.toString())
    }

    @Test
    fun `an item replaced in a document no longer writes through to it`() {
        val doc = parse("a = 1\n")
        val old = doc["a"].assertSuccess()
        doc["a"] = 2L
        old.assign(Item(Value.Integer(3L))).assertSuccess()
        assertEquals("a = 2\n", doc.toString())
    }

    @Test
    fun `an item removed from a document no longer writes through to it`() {
        val doc = parse("[t]\nk = 1\n")
        val removed = doc.remove("t")!!
        removed["k"] = 2L
        assertEquals("", doc.toString())
    }
}
