package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** What this port keeps beyond upstream so that unchanged text prints back byte for byte. */
class PreservationTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

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
    fun `a multi-line string read with CRLF lines holds LF and prints back byte for byte`() {
        val input = "a = \"\"\"\r\nx\r\ny\"\"\"\r\nb = '''\r\nx\r\ny'''\r\n"
        val doc = parse(input)
        assertEquals("x\ny", doc["a"].assertSuccess().stringOrNull)
        assertEquals("x\ny", doc["b"].assertSuccess().stringOrNull)
        assertEquals(input, doc.toString())
    }

    @Test
    fun `the toml-test files with CRLF lines print back byte for byte`() {
        for (file in listOf("newline-crlf.toml", "empty-crlf.toml", "string/multiline-escaped-crlf.toml")) {
            val bytes = readBytes("../toml-test/tests/valid/$file")
            val printed = TomlDocument.parse(bytes).assertSuccess().toString()
            assertContentEquals(bytes, printed.encodeToByteArray(), file)
        }
    }

    @Test
    fun `a multi-line string stored in a CRLF document breaks its lines with CRLF`() {
        val doc = parse("a = []\r\n")
        doc["b"] = Value.String("x\ny")
        doc["a"].assertSuccess().arrayOrNull!!.add("p\nq")
        val expected = "a = [\"\"\"\r\np\r\nq\"\"\"]\r\nb = \"\"\"\r\nx\r\ny\"\"\"\r\n"
        assertEquals(expected, doc.toString())
        assertEquals("\"\"\"\r\nx\r\ny\"\"\"", doc["b"].assertSuccess().valueOrNull!!.toString())
        val reread = parse(expected)
        assertEquals("x\ny", reread["b"].assertSuccess().stringOrNull)
        assertEquals("p\nq", reread["a"].assertSuccess()[0].assertSuccess().stringOrNull)
    }

    @Test
    fun `a multi-line string built in code breaks its lines with LF until it is stored`() {
        assertEquals("\"\"\"\nx\ny\"\"\"", Value.String("x\ny").displaySpelling)
        val doc = parse("a = 1\n")
        doc["b"] = Value.String("x\ny")
        assertEquals("a = 1\nb = \"\"\"\nx\ny\"\"\"\n", doc.toString())
    }

    @Test
    fun `each array of tables header keeps its own spelling`() {
        for (input in listOf("[[x]]\n[[\"x\"]]\n", "[[arr ]]\n[[arr]]\n", "[[a.b]]\n[[a . b]]\n")) {
            assertEquals(input, parse(input).toString())
        }
    }

    @Test
    fun `a header keeps its spelling when a later header names a key on its path`() {
        val input = "[[a .b]]\nx = 1\n[a]\ny = 2\n"
        assertEquals(input, parse(input).toString())
    }

    @Test
    fun `an array of tables element added later prints its header as the key is spelled`() {
        val doc = parse("[[\"x\"]]\nn = 1\n")
        doc["x"].assertSuccess().arrayOfTablesOrNull!!.add(standardTableOf("n" to Item(Value.Integer(2L))).assertSuccess())
        assertEquals("[[\"x\"]]\nn = 1\n\n[[\"x\"]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `formatting the key of an array of tables formats every element's header`() {
        val doc = parse("[[ 'x' ]]\nn = 1\n[[x]]\nn = 2\n")
        doc.root.keyOrNull("x")!!.format()
        assertEquals("[[x]]\nn = 1\n[[x]]\nn = 2\n", doc.toString())
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
    fun `each dotted key keeps its own spelling`() {
        val input = "name.first = \"Arthur\"\n\"name\" . 'last' = \"Dent\"\n"
        val doc = parse(input)
        assertEquals(input, doc.toString())

        doc["other"] = 1L
        assertEquals("$input" + "other = 1\n", doc.toString())
    }

    @Test
    fun `formatting a shared key applies to every line`() {
        val doc = parse("name.first = \"Arthur\"\n\"name\" . 'last' = \"Dent\"\n")
        doc.root.keyOrNull("name")!!.format()
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

    @Test
    fun `an inline table made dotted keeps the comment above its key`() {
        val doc = parse("x = 1\n# about t\nt = { c = 3 }\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals("x = 1\n# about t\nt.c = 3\n", doc.toString())
    }

    @Test
    fun `nested inline tables made dotted keep the comment above and take new keys on lines of their own`() {
        val doc = parse("x = 1\r\n# about t\r\nt = { c = { c = 3 } }\r\n")
        val inner = doc["t"]["c"].assertSuccess().inlineTableOrNull!!
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        doc["t"]["c"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        inner.set("d", 1L).assertSuccess()
        assertEquals("x = 1\r\n# about t\r\nt.c.c = 3\r\nt.c.d = 1\r\n", doc.toString())
    }

    @Test
    fun `removing the last key of an inline table made dotted keeps the comment above it`() {
        val doc = parse("x = 1\n# about t\nt = { c = { c = 3 } }\n")
        val inner = doc["t"]["c"].assertSuccess().inlineTableOrNull!!
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        doc["t"]["c"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        inner.remove("c")
        assertEquals("x = 1\n# about t\nt.c = {}\n", doc.toString())
    }
}
