package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** What this port keeps beyond upstream so that unchanged text prints back byte for byte. */
class PreservationTest {

    private fun parse(text: String): DocumentMut = DocumentMut.parse(text).unwrap()

    @Test
    fun crlfLinesKeepTheirEndings() {
        val input = "a = 1\r\n\r\n[t]\r\nb = 2\r\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun anInsertedLineEndsLikeTheLineBeforeIt() {
        val doc = parse("[t]\r\nb = 2\r\n")
        doc["t"]["c"] = value(3L)
        assertEquals("[t]\r\nb = 2\r\nc = 3\r\n", doc.toString())
    }

    @Test
    fun mixedLineEndingsAreKeptPerLine() {
        val input = "a = 1\nb = 2\r\nc = 3\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun aMissingFinalNewlineIsKeptWhileUnchanged() {
        assertEquals("a = 1\n[t]", parse("a = 1\n[t]").toString())
        assertEquals("[t]\r\nb = 2", parse("[t]\r\nb = 2").toString())
    }

    @Test
    fun aChangedDocumentEndsWithANewline() {
        val doc = parse("a = 1\nb = 2")
        doc["a"] = value(5L)
        assertEquals("a = 5\nb = 2\n", doc.toString())
    }

    @Test
    fun eachDottedKeyKeepsItsOwnSpelling() {
        val input = "name.first = \"Arthur\"\n\"name\" . 'last' = \"Dent\"\n"
        val doc = parse(input)
        assertEquals(input, doc.toString())

        doc["other"] = value(1L)
        assertEquals("$input" + "other = 1\n", doc.toString())
    }

    @Test
    fun formattingASharedKeyAppliesToEveryLine() {
        val doc = parse("name.first = \"Arthur\"\n\"name\" . 'last' = \"Dent\"\n")
        doc.asTable().keyMut("name")!!.fmt()
        // The leaf key `'last'` is the line's own and keeps its spelling and the space before it.
        assertEquals("name.first = \"Arthur\"\nname. 'last' = \"Dent\"\n", doc.toString())
    }

    @Test
    fun eachHeaderKeepsItsOwnSpelling() {
        val input = "['a']\n[a.'\"b\"']\n[\"a\".'\"b\"'.c]\nanswer = 42\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun interleavedDottedKeysKeepTheirOrder() {
        val input = "apple.type = \"fruit\"\norange.type = \"fruit\"\napple.skin = \"thin\"\norange.skin = \"thick\"\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun aValueAddedToADottedTableFollowsItsSibling() {
        val doc = parse("apple.type = \"fruit\"\norange.type = \"fruit\"\napple.skin = \"thin\"\n")
        doc["apple"]["color"] = value("red")
        assertEquals(
            "apple.type = \"fruit\"\norange.type = \"fruit\"\napple.skin = \"thin\"\napple.color = \"red\"\n",
            doc.toString(),
        )
    }

    @Test
    fun sortingReplacesTheReadOrder() {
        val doc = parse("b.x = 1\na.y = 2\nb.z = 3\n")
        doc.asTable().sortValues()
        assertEquals("a.y = 2\nb.x = 1\nb.z = 3\n", doc.toString())
    }

    @Test
    fun interleavedDottedKeysInAnInlineTableKeepTheirOrder() {
        val input = "t = { a.x = 1, b.y = 2, a.z = 3 }\n"
        assertEquals(input, parse(input).toString())
    }
}
