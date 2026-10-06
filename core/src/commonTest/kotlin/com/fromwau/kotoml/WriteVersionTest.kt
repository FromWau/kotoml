package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/** A document prints and writes the TOML version [TomlDocument.version] names. */
class WriteVersionTest {

    private fun written(doc: TomlDocument): String {
        val buffer = Buffer()
        doc.writeTo(buffer).getOrFail()
        return buffer.readString()
    }

    @Test
    fun `a document writes the version it was parsed as`() {
        assertEquals(TomlVersion.V1_0, TomlDocument.parse("a = 1\n", TomlVersion.V1_0).getOrFail().version)
        assertEquals(TomlVersion.V1_1, TomlDocument.parse("a = 1\n").getOrFail().version)
    }

    @Test
    fun `a new document writes TOML 1_1`() {
        assertEquals(TomlVersion.V1_1, TomlDocument().version)
    }

    @Test
    fun `a copy writes the version of its source`() {
        val doc = TomlDocument.parse("a = 1\n", TomlVersion.V1_0).getOrFail()
        assertEquals(TomlVersion.V1_0, doc.copy().version)
    }

    @Test
    fun `times read as TOML 1_1 print their seconds once the document is TOML 1_0`() {
        val doc = TomlDocument.parse("t = 07:32\nd = 1979-05-27 07:32Z\nl = 1979-05-27T07:32\n").getOrFail()
        doc.version = TomlVersion.V1_0
        val expected = "t = 07:32:00\nd = 1979-05-27 07:32:00Z\nl = 1979-05-27T07:32:00\n"
        assertEquals(expected, doc.toString())
        assertEquals(expected, written(doc))
        TomlDocument.parse(expected, TomlVersion.V1_0).getOrFail()
    }

    @Test
    fun `a time in an array prints its seconds in a TOML 1_0 document`() {
        val doc = TomlDocument.parse("a = [07:32, { t = 08:15 }]\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals("a = [07:32:00, { t = 08:15:00 }]\n", written(doc))
    }

    @Test
    fun `a string spelled with a TOML 1_1 escape prints its default spelling in a TOML 1_0 document`() {
        val doc = TomlDocument.parse("\"k\\e\" = \"\\x41\\e\"\nplain = \"\\\\e\"\n").getOrFail()
        doc.version = TomlVersion.V1_0
        val text = doc.toString()
        assertEquals("\"k\\u001B\" = \"A\\u001B\"\nplain = \"\\\\e\"\n", text)
        TomlDocument.parse(text, TomlVersion.V1_0).getOrFail()
    }

    @Test
    fun `a key spelled with a TOML 1_1 escape is refused when writing TOML 1_0`() {
        val doc = TomlDocument.parse("\"k\\e\" = \"\\x41\"\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals(
            TomlWriteError.NotInToml10(listOf("k\u001B"), Toml11Feature.EscapeEscape),
            doc.writeTo(Buffer()).errorOrFail(),
        )
    }

    @Test
    fun `a string spelled with a TOML 1_1 escape is refused when writing TOML 1_0`() {
        val doc = TomlDocument.parse("plain = \"\\\\x\"\n[a]\nb.c = \"\\x41\\e\"\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals(
            TomlWriteError.NotInToml10(listOf("a", "b", "c"), Toml11Feature.HexEscape),
            doc.writeTo(Buffer()).errorOrFail(),
        )
    }

    @Test
    fun `a TOML 1_1 escape inside an array is refused under the array's key when writing TOML 1_0`() {
        val doc = TomlDocument.parse("a = [1, { s = \"\\e\" }]\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals(
            TomlWriteError.NotInToml10(listOf("a"), Toml11Feature.EscapeEscape),
            doc.writeTo(Buffer()).errorOrFail(),
        )
    }

    @Test
    fun `a header spelled with a TOML 1_1 escape is refused when writing TOML 1_0`() {
        val doc = TomlDocument.parse("[[t.\"\\x41\"]]\nk = 1\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals(
            TomlWriteError.NotInToml10(listOf("t", "A"), Toml11Feature.HexEscape),
            doc.writeTo(Buffer()).errorOrFail(),
        )
        assertEquals("[[t.A]]\nk = 1\n", doc.toString())
    }

    @Test
    fun `a document set back to TOML 1_1 writes a TOML 1_1 escape`() {
        val doc = TomlDocument.parse("s = \"\\e\"\n", TomlVersion.V1_1).getOrFail()
        doc.version = TomlVersion.V1_0
        doc.version = TomlVersion.V1_1
        assertEquals("s = \"\\e\"\n", written(doc))
    }

    @Test
    fun `an inline table across lines is refused when writing TOML 1_0`() {
        val doc = TomlDocument.parse("[a]\nt = {\n  x = 1, # one\n}\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals(
            TomlWriteError.NotInToml10(listOf("a", "t"), Toml11Feature.MultilineInlineTable),
            doc.writeTo(Buffer()).errorOrFail(),
        )
    }

    @Test
    fun `a trailing comma inside an array is refused when writing TOML 1_0`() {
        val doc = TomlDocument.parse("a = [{ x = 1 }]\n", TomlVersion.V1_0).getOrFail()
        doc["a"][0].getOrFail().valueOrNull!!.inlineTableOrNull!!.trailingComma = true
        assertEquals(
            TomlWriteError.NotInToml10(listOf("a"), Toml11Feature.InlineTableTrailingComma),
            doc.writeTo(Buffer()).errorOrFail(),
        )
    }

    @Test
    fun `a table prints in its document's version`() {
        val doc = TomlDocument.parse("[a]\nt = 07:32\ns = \"\\e\"\n", TomlVersion.V1_1).getOrFail()
        doc.version = TomlVersion.V1_0
        val table = doc["a"].getOrFail().standardTableOrNull!!
        assertEquals("t = 07:32:00\ns = \"\\u001B\"\n", table.toString())
    }

    @Test
    fun `an array and an inline table print in their document's version`() {
        val doc = TomlDocument.parse("a = [07:32, { s = \"\\x41\" }]\n").getOrFail()
        doc.version = TomlVersion.V1_0
        val array = doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!
        assertEquals("[07:32:00, { s = \"A\" }]", array.toString())
        assertEquals("{ s = \"A\" }", array[1].getOrFail().toString())
        assertEquals("{ s = \"A\" }", array[1].getOrFail().inlineTableOrNull!!.toString())
    }

    @Test
    fun `a value prints in its document's version`() {
        val doc = TomlDocument.parse("t = 1979-05-27 07:32\ns = \"\\e\"\na = [\"\\x41\"]\n").getOrFail()
        doc.version = TomlVersion.V1_0
        assertEquals("1979-05-27 07:32:00", doc["t"].getOrFail().valueOrNull!!.toString())
        assertEquals("\"\\u001B\"", doc["s"].getOrFail().valueOrNull!!.toString())
        assertEquals("\"A\"", doc["a"][0].getOrFail().valueOrNull!!.toString())
    }

    @Test
    fun `a value stored nowhere prints TOML 1_1`() {
        val doc = TomlDocument.parse("s = \"\\e\"\nt = { u = 07:32 }\n").getOrFail()
        doc.version = TomlVersion.V1_0
        val string = doc.remove("s")!!.valueOrNull!!
        val table = doc["t"].getOrFail().valueOrNull!!.copy()
        assertEquals("\"\\e\"", string.toString())
        assertEquals("{ u = 07:32 }", table.toString())
    }

    @Test
    fun `a document set to TOML 1_1 writes what TOML 1_0 refuses`() {
        val doc = TomlDocument.parse("t = { x = 1 }\n", TomlVersion.V1_0).getOrFail()
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.trailingComma = true
        doc.version = TomlVersion.V1_1
        assertEquals("t = { x = 1 ,}\n", written(doc))
    }
}
