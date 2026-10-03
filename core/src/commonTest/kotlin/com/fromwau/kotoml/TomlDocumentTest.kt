package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TomlDocumentTest {

    private class FailingSource : RawSource {
        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = throw IOException("disk gone")

        override fun close() {}
    }

    private class FailingSink : RawSink {
        override fun write(
            source: Buffer,
            byteCount: Long,
        ): Unit = throw IOException("disk full")

        override fun flush() {}

        override fun close() {}
    }

    @Test
    fun `iterating a document visits the root table's pairs in order`() {
        val document = TomlDocument.parse("b = 1\na = 2\n[t]\n").unwrap()

        assertEquals(listOf("b", "a", "t"), document.map { it.first })
        assertEquals(document.root.items(), document.toList())
    }

    @Test
    fun `the keyed calls act on the root table`() {
        val document = TomlDocument()

        document["a"] = 1L
        document.getOrPut("b") { Item(Value.Integer(2L)) }

        assertTrue("a" in document)
        assertEquals(2, document.size)
        assertEquals(1L, document.root["a"].longOrNull)
        assertEquals(2L, document.remove("b")?.longOrNull)
        assertEquals("a = 1\n", document.toString())
    }

    @Test
    fun `trailing text prints after the last item`() {
        val document = TomlDocument.parse("a = 1\n# end\n").unwrap()
        assertEquals("# end\n", document.trailing)

        document.trailing = "\n# footer\n"

        assertEquals("a = 1\n\n# footer\n", document.toString())
    }

    @Test
    fun `a copy is edited apart from its original`() {
        val document = TomlDocument.parse("a = 1 # one\n").unwrap()
        val copy = document.copy()

        copy["a"] = 2L

        assertEquals("a = 1 # one\n", document.toString())
        assertEquals("a = 2\n", copy.toString())
    }

    @Test
    fun `read parses what the source holds and prints it back unchanged`() {
        val text = "# config\n[server]\nhost = 'alpha' # primary\n"
        val source = Buffer().apply { writeString(text) }

        val document = TomlDocument.read(source).unwrap()

        assertEquals("alpha", document["server"]["host"].stringOrNull)
        assertEquals(text, document.toString())
    }

    @Test
    fun `read reports a failing source as TomlIoError`() {
        val error = TomlDocument.read(FailingSource().buffered()).unwrapErr()

        assertEquals(TomlIoError("disk gone"), error)
    }

    @Test
    fun `read reports text that is not TOML as a parse error carrying the text`() {
        val text = "a = 1\na = 2\n"

        val error = TomlDocument.read(Buffer().apply { writeString(text) }).unwrapErr()

        assertTrue(error is TomlReadError.Parse)
        assertEquals(TomlParseError.Reason.DuplicateKey("a"), error.error.reason)
        assertEquals(text, error.text)
    }

    @Test
    fun `read reports bytes that are not UTF-8 as a parse error`() {
        val source = Buffer().apply { write(byteArrayOf('a'.code.toByte(), 0xFF.toByte())) }

        val error = TomlDocument.read(source).unwrapErr()

        assertTrue(error is TomlReadError.Parse)
        assertEquals(TomlParseError.Reason.InvalidUtf8(validUpTo = 1, errorLength = 1), error.error.reason)
    }

    @Test
    fun `read accepts only what the given TOML version accepts`() {
        val source = Buffer().apply { writeString("t = 07:32\n") }

        val error = TomlDocument.read(source, TomlVersion.V1_0).unwrapErr()

        assertTrue(error is TomlReadError.Parse)
        assertEquals(TomlParseError.Reason.NotInToml10(Toml11Feature.TimeWithoutSeconds), error.error.reason)
    }

    @Test
    fun `writeTo writes the document as it prints`() {
        val document = TomlDocument.parse("name = 'kotoml' # the name\n").unwrap()
        document["version"] = "0.1.0"
        val sink = Buffer()

        document.writeTo(sink).unwrap()

        assertEquals(document.toString(), sink.readString())
    }

    @Test
    fun `writeTo flushes so a failing sink is reported as TomlIoError`() {
        val document = TomlDocument.parse("a = 1\n").unwrap()

        val error = document.writeTo(FailingSink().buffered()).unwrapErr()

        assertEquals(TomlIoError("disk full"), error)
    }

    @Test
    fun `a read error serializes with its type and leaves the text out`() {
        val io: TomlReadError = TomlIoError("disk gone")
        val parse: TomlReadError = TomlDocument.read(Buffer().apply { writeString("a = 1\na = 2\n") }).unwrapErr()

        assertEquals(
            """{"type":"Io","detail":"disk gone"}""",
            Json.encodeToString(TomlReadError.serializer(), io),
        )
        assertEquals(
            """{"type":"Parse","error":{"reason":{"type":"DuplicateKey","key":"a"},""" +
                """"span":{"start":6,"end":7},"position":{"line":2,"column":1},"context":{"start":0,"end":1}}}""",
            Json.encodeToString(TomlReadError.serializer(), parse),
        )
    }
}
