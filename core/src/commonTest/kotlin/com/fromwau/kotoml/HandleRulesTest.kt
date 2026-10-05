package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** The rules for items handed out by indexing: live views, copies on store, moves on removal, detachment. */
class HandleRulesTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).getOrFail()

    private val banneredSource = "# Generated, do not edit\n\n[db] # db hdr\nurl = 1 # url c\n"

    @Test
    fun `an item read from a document writes through to it`() {
        val doc = parse("a = 1 # c\n")
        doc["a"].getOrFail().valueOrNull!!.decor = Decor(" ", " # mine")
        doc["a"].getOrFail().assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals("a = 2 # mine\n", doc.toString())
    }

    @Test
    fun `a copy of a table from another document stores as the table itself does`() {
        val stored = parse("[app]\nname = 1\n")
        stored.set("db", parse(banneredSource)["db"].getOrFail()).getOrFail()
        val copied = parse("[app]\nname = 1\n")
        copied.set("db", parse(banneredSource)["db"].getOrFail().copy()).getOrFail()
        assertEquals("[app]\nname = 1\n\n[db]\nurl = 1 # url c\n", copied.toString())
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a table stored in a new document keeps only the comments inside it`() {
        val src = parse("# Generated\n[db] # primary\nurl = \"x\" # local\n")
        val dst = TomlDocument()
        dst["db"] = src["db"].getOrFail()
        assertEquals("[db]\nurl = \"x\" # local\n", dst.toString())
    }

    @Test
    fun `a copy of a value from another document leaves its same-line comment`() {
        val source = parse("a = 1 # from source\n")
        val doc = parse("x = 1\n")
        doc.set("a", source["a"].getOrFail().copy()).getOrFail()
        assertEquals("x = 1\na = 1\n", doc.toString())
    }

    @Test
    fun `a copy of a table from the same document stores as the table itself does`() {
        val text = "# banner\n[a] # a hdr\nk = 1 # k c\n\n[z]\n"
        val stored = parse(text)
        stored.set("b", stored["a"].getOrFail()).getOrFail()
        val copied = parse(text)
        copied.set("b", copied["a"].getOrFail().copy()).getOrFail()
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a copy of a value from the same document stores as the value itself does`() {
        val text = "a = [1, 2] # a c\n[t]\n"
        val stored = parse(text)
        stored.set("b", stored["a"].getOrFail()).getOrFail()
        val copied = parse(text)
        copied.set("b", copied["a"].getOrFail().copy()).getOrFail()
        assertEquals("a = [1, 2] # a c\nb = [1, 2]\n[t]\n", copied.toString())
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a copy of an array of tables from the same document stores as the array itself does`() {
        val text = "# about arr\n[[arr]] # first\nx = 1\n\n[[arr]]\nx = 2\n\n[z]\n"
        val stored = parse(text)
        stored["z"].getOrFail().set("arr", stored["arr"].getOrFail()).getOrFail()
        val copied = parse(text)
        copied["z"].getOrFail().set("arr", copied["arr"].getOrFail().copy()).getOrFail()
        assertEquals(stored.toString(), copied.toString())
    }

    @Test
    fun `a table removed from a document keeps its header comment when stored in another`() {
        val source = parse(banneredSource)
        val table = source.remove("db")!!
        val doc = parse("[app]\nname = 1\n")
        doc.set("db", table).getOrFail()
        assertEquals("[app]\nname = 1\n\n[db] # db hdr\nurl = 1 # url c\n", doc.toString())
        assertEquals("# Generated, do not edit\n", source.toString())
    }

    @Test
    fun `a value removed from a document keeps its same-line comment when stored in another`() {
        val value = parse("a = 1 # mine\n").remove("a")!!
        val doc = parse("x = 1\n")
        doc.set("a", value).getOrFail()
        assertEquals("x = 1\na = 1 # mine\n", doc.toString())
    }

    @Test
    fun `tables moved with removeAt and add keep their header comments`() {
        val doc = parse("[[arr]] # one\nx = 1\n[[arr]] # two\nx = 2\n[[arr]] # three\nx = 3\n")
        val tables = doc["arr"].getOrFail().arrayOfTablesOrNull!!
        for (index in 1 until tables.size) tables.add(0, tables.removeAt(index).getOrFail()).getOrFail()
        assertEquals("[[arr]] # three\nx = 3\n[[arr]] # two\nx = 2\n[[arr]] # one\nx = 1\n", doc.toString())
    }

    @Test
    fun `a comment a caller set on a value travels with a store into another document`() {
        val source = parse("a = 1 # read\n")
        source["a"].getOrFail().valueOrNull!!.decor = Decor(" ", " # mine")
        val doc = parse("x = 1\n")
        doc.set("a", source["a"].getOrFail()).getOrFail()
        doc.set("b", source["a"].getOrFail().copy()).getOrFail()
        assertEquals("x = 1\na = 1 # mine\nb = 1 # mine\n", doc.toString())
    }

    @Test
    fun `a header comment a caller set on a table travels with a copy into another document`() {
        val source = parse("[db] # read\nurl = 1\n")
        source["db"].getOrFail().standardTableOrNull!!.decor = Decor("# mine above\n", " # mine")
        val doc = parse("x = 1\n")
        doc.set("db", source["db"].getOrFail().copy()).getOrFail()
        assertEquals("x = 1\n# mine above\n[db] # mine\nurl = 1\n", doc.toString())
    }

    @Test
    fun `an item replaced in a document no longer writes through to it`() {
        val doc = parse("a = 1\n")
        val old = doc["a"].getOrFail()
        doc["a"] = 2L
        old.assign(Item(Value.Integer(3L))).getOrFail()
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
