package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** What this port keeps beyond upstream so that unchanged text prints back byte for byte. */
class PreservationTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).unwrap()

    @Test
    fun `CRLF lines keep their endings`() {
        val input = "a = 1\r\n\r\n[t]\r\nb = 2\r\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun `an inserted line ends like the line before it`() {
        val doc = parse("[t]\r\nb = 2\r\n")
        doc["t"]["c"] = 3L
        assertEquals("[t]\r\nb = 2\r\nc = 3\r\n", doc.toString())
    }

    @Test
    fun `mixed line endings are kept per line`() {
        val input = "a = 1\nb = 2\r\nc = 3\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun `a missing final newline is kept while the document is unchanged`() {
        assertEquals("a = 1\n[t]", parse("a = 1\n[t]").toString())
        assertEquals("[t]\r\nb = 2", parse("[t]\r\nb = 2").toString())
    }

    @Test
    fun `a changed document ends with a newline`() {
        val doc = parse("a = 1\nb = 2")
        doc["a"] = 5L
        assertEquals("a = 5\nb = 2\n", doc.toString())
    }

    @Test
    fun `each dotted key keeps its own spelling`() {
        val input = "name.first = \"Arthur\"\n\"name\" . 'last' = \"Dent\"\n"
        val doc = parse(input)
        assertEquals(input, doc.toString())

        doc["other"] = 1L
        assertEquals("$input" + "other = 1\n", doc.toString())
    }

    @Test
    fun `resetting a shared key's formatting applies to every line`() {
        val doc = parse("name.first = \"Arthur\"\n\"name\" . 'last' = \"Dent\"\n")
        doc.root.key("name")!!.resetFormatting()
        // The leaf key `'last'` is the line's own and keeps its spelling and the space before it.
        assertEquals("name.first = \"Arthur\"\nname. 'last' = \"Dent\"\n", doc.toString())
    }

    @Test
    fun `each header keeps its own spelling`() {
        val input = "['a']\n[a.'\"b\"']\n[\"a\".'\"b\"'.c]\nanswer = 42\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun `interleaved dotted keys keep their order`() {
        val input = "apple.type = \"fruit\"\norange.type = \"fruit\"\napple.skin = \"thin\"\norange.skin = \"thick\"\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun `a value added to a dotted table follows its last sibling`() {
        val doc = parse("apple.type = \"fruit\"\norange.type = \"fruit\"\napple.skin = \"thin\"\n")
        doc["apple"]["color"] = "red"
        assertEquals(
            "apple.type = \"fruit\"\norange.type = \"fruit\"\napple.skin = \"thin\"\napple.color = \"red\"\n",
            doc.toString(),
        )
    }

    @Test
    fun `sorting replaces the read order`() {
        val doc = parse("b.x = 1\na.y = 2\nb.z = 3\n")
        doc.root.sortBy { (key, _) -> key }
        assertEquals("a.y = 2\nb.x = 1\nb.z = 3\n", doc.toString())
    }

    @Test
    fun `interleaved dotted keys in an inline table keep their order`() {
        val input = "t = { a.x = 1, b.y = 2, a.z = 3 }\n"
        assertEquals(input, parse(input).toString())
    }
}
