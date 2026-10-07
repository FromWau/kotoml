package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `respell()` writes every value under its receiver in its default spelling and leaves keys, comments and layout. */
class RespellTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    /** Checks that [doc] prints [expected], which reads back as the data [doc] holds. */
    private fun assertPrints(
        expected: String,
        doc: TomlDocument,
    ) {
        assertEquals(expected, doc.toString())
        val reread = TomlDocument.parse(expected, doc.version).assertSuccess()
        assertTrue(reread.toTagged().sameAs(doc.toTagged()), "reads back as other data")
    }

    private fun respelled(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = parse(text, version).apply { respell() }

    @Test
    fun `the owner's examples print in their default spelling`() {
        val doc = respelled("port = 0x1F90\nname = 'kotoml'\nratio = 1e0\nat = 07:32\n")
        assertPrints("port = 8080\nname = \"kotoml\"\nratio = 1.0\nat = 07:32:00\n", doc)
        assertEquals(0, doc["at"].assertSuccess().datetimeOrNull?.time?.second)
        assertNull(doc["port"].assertSuccess().valueOrNull?.let { (it as Value.Integer).spelling })
    }

    @Test
    fun `integers print in decimal without underscores or a plus sign`() {
        val doc = respelled("a = 0xdead_beef\nb = 0o17\nc = 0b101\nd = 1_000\ne = +5\nf = -0\n")
        assertPrints("a = 3735928559\nb = 15\nc = 5\nd = 1000\ne = 5\nf = 0\n", doc)
    }

    @Test
    fun `floats print with a fraction and specials without a sign they do not need`() {
        val doc = respelled("a = 1e0\nb = 3.14e2\nc = 1_000.5\nd = +inf\ne = -0.0\nf = +1.5\ng = 5E-1\n")
        assertPrints("a = 1.0\nb = 314.0\nc = 1000.5\nd = inf\ne = -0.0\nf = 1.5\ng = 0.5\n", doc)
    }

    @Test
    fun `strings print in double quotes unless a literal or multi-line string reads better`() {
        val text = "a = 'plain'\nb = \"\"\"one line\"\"\"\nc = '''it's'''\nd = \"C:\\\\path\"\ne = \"\\u0041\"\n"
        val doc = respelled(text)
        assertPrints("a = \"plain\"\nb = \"one line\"\nc = \"it's\"\nd = 'C:\\path'\ne = \"A\"\n", doc)
    }

    @Test
    fun `date-times print with a T an upper-case Z and seconds`() {
        val text = "a = 1979-05-27 07:32:00z\nb = 1979-05-27T07:32:00.500+01:00\nc = 1979-05-27T07:32\nd = 07:32\n" +
            "e = 1979-05-27\n"
        val doc = respelled(text)
        val expected = "a = 1979-05-27T07:32:00Z\nb = 1979-05-27T07:32:00.5+01:00\nc = 1979-05-27T07:32:00\n" +
            "d = 07:32:00\ne = 1979-05-27\n"
        assertPrints(expected, doc)
        assertEquals(0, doc["c"].assertSuccess().datetimeOrNull?.time?.second)
    }

    @Test
    fun `keys keep their spelling`() {
        val doc = respelled("'q' = 0o17\n\"r\".'s' = 'x'\n[\"t\".'u']\n'k' = 0b11\n")
        assertPrints("'q' = 15\n\"r\".'s' = \"x\"\n[\"t\".'u']\n'k' = 3\n", doc)
    }

    @Test
    fun `comments layout and spacing stay`() {
        val text = "# top\na  =  0x10   # hex\nb = [\n  0o7, # seven\n  'x' ,\n] # b\nt = { c = 1_000 , d.e = +5 }\n" +
            "\n[u] # u\n  f.g = 'h'\n"
        val expected = "# top\na  =  16   # hex\nb = [\n  7, # seven\n  \"x\" ,\n] # b\nt = { c = 1000 , d.e = 5 }\n" +
            "\n[u] # u\n  f.g = \"h\"\n"
        assertPrints(expected, respelled(text))
    }

    @Test
    fun `nested arrays and tables are respelled at every depth`() {
        val text = "[[x]]\nn = 0x1\n[x.y]\nm = [[0o1, { a = 'b', c = [1e1] }]]\n[[x]]\nn = 0x2\n"
        val expected = "[[x]]\nn = 1\n[x.y]\nm = [[1, { a = \"b\", c = [10.0] }]]\n[[x]]\nn = 2\n"
        assertPrints(expected, respelled(text))
    }

    @Test
    fun `line endings stay`() {
        val doc = respelled("a = 0x10\r\nb = [\r\n  'x',\r\n]\r\n[t]\r\nc = 1e0")
        assertPrints("a = 16\r\nb = [\r\n  \"x\",\r\n]\r\n[t]\r\nc = 1.0", doc)
    }

    @Test
    fun `a TOML 1_0 document stays TOML 1_0`() {
        val doc = respelled("a = 0x10\nb = 'x'\nc = 07:32:00\nd = { e = 1e0 }\n", TomlVersion.V1_0)
        assertPrints("a = 16\nb = \"x\"\nc = 07:32:00\nd = { e = 1.0 }\n", doc)
    }

    @Test
    fun `an escape only TOML 1_1 reads prints as one TOML 1_0 reads`() {
        val doc = respelled("a = \"\\e\"\nb = \"\\x41\"\n")
        assertPrints("a = \"\\u001B\"\nb = \"A\"\n", doc)
        TomlDocument.parse(doc.toString(), TomlVersion.V1_0).assertSuccess()
    }

    @Test
    fun `respelling twice gives the same text`() {
        val doc = respelled("a = 0x10\nb = [07:32, '''x''']\n[t]\nc = { d = 1e0 }\n")
        val once = doc.toString()
        doc.respell()
        assertEquals(once, doc.toString())
    }

    @Test
    fun `a value made in code prints as it did`() {
        val doc = TomlDocument()
        doc["a"] = Value.String("kotoml")
        doc["b"] = Value.Float(1.0)
        val before = doc.toString()
        doc.respell()
        assertEquals(before, doc.toString())
    }

    @Test
    fun `each receiver respells only what is under it`() {
        val text = "a = 0x1\nb = [0x2, [0x3]]\nc = { d = 0x4, e = [0x5] }\n[t]\nf = 0x6\n[t.u]\ng = 0x7\n" +
            "[[x]]\nh = 0x8\n[[x]]\ni = 0x9\n"
        val doc = parse(text)
        doc["a"].assertSuccess().respell()
        assertEquals("a = 1\nb = [0x2, [0x3]]", doc.toString().lines().take(2).joinToString("\n"))
        doc["b"].assertSuccess().arrayOrNull!!.getOrNull(1)!!.respell()
        assertTrue("b = [0x2, [3]]\n" in doc.toString())
        doc["b"].assertSuccess().arrayOrNull!!.respell()
        assertTrue("b = [2, [3]]\n" in doc.toString())
        doc["c"].assertSuccess().inlineTableOrNull!!.respell()
        assertTrue("c = { d = 4, e = [5] }\n" in doc.toString())
        doc["t"]["u"].assertSuccess().standardTableOrNull!!.respell()
        assertTrue("f = 0x6\n" in doc.toString())
        assertTrue("g = 7\n" in doc.toString())
        doc["t"].assertSuccess().tableOrNull!!.respell()
        assertTrue("f = 6\n" in doc.toString())
        assertTrue("h = 0x8\n" in doc.toString())
        doc["x"].assertSuccess().arrayOfTablesOrNull!!.respell()
        val expected = "a = 1\nb = [2, [3]]\nc = { d = 4, e = [5] }\n[t]\nf = 6\n[t.u]\ng = 7\n[[x]]\nh = 8\n[[x]]\n" +
            "i = 9\n"
        assertPrints(expected, doc)
    }

    @Test
    fun `an item holding a table respells everything under it`() {
        val doc = parse("[t]\na = 0x1\n[t.u]\nb = 'c'\n[v]\nd = 0x2\n")
        doc["t"].assertSuccess().respell()
        assertPrints("[t]\na = 1\n[t.u]\nb = \"c\"\n[v]\nd = 0x2\n", doc)
    }

    @Test
    fun `the KDoc example of respell prints default spellings and keeps the comment`() {
        val doc = TomlDocument.parse("port = 0x1F90 # http\nname = 'kotoml'\nat = 07:32\n").assertSuccess()
        doc.respell()
        assertEquals("port = 8080 # http\nname = \"kotoml\"\nat = 07:32:00\n", doc.toString())
    }
}
