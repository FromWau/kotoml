package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edits whose output once failed to read back, each cut down from what `EditPropertyTest` found. */
class EditOutputTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).getOrFail()

    private fun assertReadsBack(doc: TomlDocument) {
        val printed = doc.toString()
        val reparsed = TomlDocument.parse(printed).getOrFail()
        assertTrue(reparsed.toTagged().sameAs(doc.toTagged()), "reads back other data:\n$printed")
    }

    @Test
    fun `an array of tables left with no tables prints as an empty array and its tables print again once added`() {
        val doc = parse("[[x]]\nn = 1\n")
        val tables = doc["x"].getOrFail().arrayOfTablesOrNull!!
        tables.clear()
        assertEquals("x = []\n", doc.toString())
        tables.add(standardTableOf("n" to Item(Value.Integer(2L))).getOrFail()).getOrFail()
        assertEquals("[[x]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `an element of a multi-line array stored at a key prints on the key's line`() {
        val doc = parse("a = [\n  1,\n  2,\n]\n")
        doc.set("b", doc["a"][1].getOrFail()).getOrFail()
        assertEquals("a = [\n  1,\n  2,\n]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a value with a comment after it stored in an array leaves the comment behind`() {
        val doc = parse("a = 1 # note\nb = []\n")
        doc["b"].getOrFail().arrayOrNull!!.add(doc["a"].getOrFail().valueOrNull!!).getOrFail()
        assertEquals("a = 1 # note\nb = [ 1]\n", doc.toString())
    }

    @Test
    fun `an array of tables copied into one of its own elements prints under that element`() {
        val doc = parse("[[x]]\nn = 1\n\n[[x]]\nn = 2\n")
        val x = doc["x"].getOrFail()
        x.arrayOfTablesOrNull!![1].getOrFail().set("n", x).getOrFail()
        assertReadsBack(doc)
    }

    @Test
    fun `a table added to a new element of a new array of tables prints under the element`() {
        val doc = parse("# first\n[a]\n# between\n[b]\n")
        doc.remove("a")
        doc.remove("b")
        doc.set("x", arrayOfTablesOf(StandardTable()).getOrFail()).getOrFail()
        doc["x"][0].getOrFail().standardTableOrNull!!.getOrPut("y") { Item(StandardTable()) }.getOrFail()
        assertReadsBack(doc)
    }
}
