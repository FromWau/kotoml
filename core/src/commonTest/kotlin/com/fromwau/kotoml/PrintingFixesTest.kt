package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.writeString

/** How a document prints and writes after edits that move, re-home or re-sort its tables, and around its text. */
class PrintingFixesTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

    private fun table(vararg pairs: Pair<String, Long>): StandardTable =
        standardTableOf(*pairs.map { (key, value) -> key to Item(Value.Integer(value)) }.toTypedArray()).assertSuccess()

    private fun TomlDocument.xs(): List<Long?> =
        this["arr"].assertSuccess().arrayOfTablesOrNull!!.map { it["x"].assertSuccess().longOrNull }

    @Test
    fun `a key added to a one-line inline table takes over the space before the closing brace`() {
        val doc = TomlDocument.parse("t = { x = 1 }\n").assertSuccess()
        doc["t"].set("y", 2L).assertSuccess()
        assertEquals("t = { x = 1, y = 2 }\n", doc.toString())
    }

    @Test
    fun `a key added after an entry spaced before its comma keeps that spacing on the entry`() {
        val doc = TomlDocument.parse("t = { a = [1, 2] , b = [1] }\n").assertSuccess()
        doc["t"].set("c", 3L).assertSuccess()
        assertEquals("t = { a = [1, 2] , b = [1], c = 3 }\n", doc.toString())
    }

    @Test
    fun `a value added to a multi-line array starts its own line below the last one and keeps the trailing comma`() {
        val doc = TomlDocument.parse("a = [\n  1, # one\n]\n").assertSuccess()
        doc["a"].assertSuccess().arrayOrNull!!.add(3L)
        assertEquals("a = [\n  1, # one\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `a value added by the builder to a multi-line array starts its own line`() {
        val doc = TomlDocument.parse("a = [\n  1,\n]\n").assertSuccess()
        doc.edit { array("a") { add(2) } }.assertSuccess()
        assertEquals("a = [\n  1,\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a value added to a one-line array stays on its line`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").assertSuccess()
        doc["a"].assertSuccess().arrayOrNull!!.add(3L)
        assertEquals("a = [1, 2, 3]\n", doc.toString())
    }

    @Test
    fun `a reversed array of tables prints in its new order`() {
        val doc = parse("[[arr]]\nx = 1\n\n[[arr]]\nx = 2\n")

        doc.set("arr", doc["arr"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("[[arr]]\nx = 2\n\n[[arr]]\nx = 1\n", doc.toString())
        assertEquals(listOf(2L, 1L), parse(doc.toString()).xs())
    }

    @Test
    fun `reordered tables move with the comment lines above their headers and their header comments`() {
        val doc = parse("# first\n[[a]] # h1\nn = 1 #one\n\n# second\n[[a]] # h2\nn = 2 #two\n")

        doc.set("a", doc["a"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("# second\n[[a]] # h2\nn = 2 #two\n\n# first\n[[a]] # h1\nn = 1 #one\n", doc.toString())
    }

    @Test
    fun `reordered tables of a CRLF document move with the comment lines above their headers`() {
        val doc = parse("# first\r\n[[a]] # h1\r\nn = 1\r\n\r\n# second\r\n[[a]] # h2\r\nn = 2\r\n")

        doc.set("a", doc["a"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("# second\r\n[[a]] # h2\r\nn = 2\r\n\r\n# first\r\n[[a]] # h1\r\nn = 1\r\n", doc.toString())
    }

    @Test
    fun `three rotated tables each move with their comments while the blank lines stay`() {
        val doc = parse("# one\n[[arr]]\nx = 1 # c1\n\n# two\n[[arr]] # h2\nx = 2\n\n\n# three\n[[arr]]\nx = 3\n")
        val tables = doc["arr"].assertSuccess().arrayOfTablesOrNull!!.toList()

        doc.set("arr", listOf(tables[1], tables[2], tables[0]).toArrayOfTables().assertSuccess()).assertSuccess()

        assertEquals(
            "# two\n[[arr]] # h2\nx = 2\n\n# three\n[[arr]]\nx = 3\n\n\n# one\n[[arr]]\nx = 1 # c1\n",
            doc.toString(),
        )
    }

    @Test
    fun `a table between reordered elements keeps the lines above it`() {
        val doc = parse("# one\n[[arr]]\nx = 1\n\n# b\n[b]\n\n# two\n[[arr]]\nx = 2\n")

        doc.set("arr", doc["arr"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("# two\n[[arr]]\nx = 2\n\n# b\n[b]\n\n# one\n[[arr]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table under a reordered element moves with it and its comments while the blank lines stay`() {
        val doc = parse("# one\n[[arr]]\nx = 1\n# sub\n[arr.sub]\ny = 1\n\n# two\n[[arr]]\nx = 2\n")

        doc.set("arr", doc["arr"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("# two\n[[arr]]\nx = 2\n# one\n[[arr]]\nx = 1\n\n# sub\n[arr.sub]\ny = 1\n", doc.toString())
    }

    @Test
    fun `reordered elements share their places while a table between them stays put`() {
        val doc = parse("[[arr]]\nx = 1\n[b]\n[[arr]]\nx = 2\n")

        doc.set("arr", doc["arr"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("[[arr]]\nx = 2\n[b]\n[[arr]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `the tables under an element move with it`() {
        val doc = parse("[[arr]]\nx = 1\n[arr.sub]\ny = 1\n[[arr]]\nx = 2\n")

        doc.set("arr", doc["arr"].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess())
            .assertSuccess()

        assertEquals("[[arr]]\nx = 2\n[[arr]]\nx = 1\n[arr.sub]\ny = 1\n", doc.toString())
    }

    @Test
    fun `a table replacing the first element prints in its place`() {
        val doc = parse("[a]\n[[arr]]\nx = 1\n[[arr]]\nx = 2\n[a.late]\n")

        doc["arr"].assertSuccess().arrayOfTablesOrNull!!.replace(0, table("x" to 0L)).assertSuccess()

        assertEquals("[a]\n[[arr]]\nx = 0\n[[arr]]\nx = 2\n[a.late]\n", doc.toString())
    }

    @Test
    fun `a table replacing the only element prints in its place`() {
        val doc = parse("[a]\n[[arr]]\nx = 1\n[a.late]\n")

        doc["arr"].assertSuccess().arrayOfTablesOrNull!!.replace(0, table("x" to 0L)).assertSuccess()

        assertEquals("[a]\n[[arr]]\nx = 0\n[a.late]\n", doc.toString())
    }

    @Test
    fun `a table added first to an array of tables prints before its elements`() {
        val doc = parse("[a]\n[[arr]]\nx = 1\n[[arr]]\nx = 2\n[a.late]\n")

        doc["arr"].assertSuccess().arrayOfTablesOrNull!!.add(0, table("x" to 0L)).assertSuccess()

        assertEquals("[a]\n\n[[arr]]\nx = 0\n[[arr]]\nx = 1\n[[arr]]\nx = 2\n[a.late]\n", doc.toString())
    }

    @Test
    fun `a dotted table stored in an array of tables prints under its header`() {
        val doc = parse("a.b = 1\n")
        val dotted = doc["a"].assertSuccess().standardTableOrNull!!

        doc.set("arr", arrayOfTablesOf(dotted.copy()).assertSuccess()).assertSuccess()

        assertEquals("a.b = 1\n\n[[arr]]\nb = 1\n", doc.toString())
    }

    @Test
    fun `a dotted table inserted into an array of tables prints under its header`() {
        val doc = parse("a.b = 1\n\n[[arr]]\nx = 1\n")
        val dotted = doc["a"].assertSuccess().standardTableOrNull!!.copy()

        doc["arr"].assertSuccess().arrayOfTablesOrNull!!.add(1, dotted).assertSuccess()

        assertEquals("a.b = 1\n\n[[arr]]\nx = 1\n\n[[arr]]\nb = 1\n", doc.toString())
    }

    @Test
    fun `a dotted table made a document's root prints its values`() {
        val dotted = parse("a.b = 1\n")["a"].assertSuccess().standardTableOrNull!!.copy()

        val doc = TomlDocument(dotted)

        assertEquals("b = 1\n", doc.toString())
    }

    @Test
    fun `sorting orders tables made by hand`() {
        val doc = TomlDocument()
        doc.set("z", table("y" to 1L)).assertSuccess()
        doc.set("a", table("x" to 1L)).assertSuccess()

        doc.root.sortBy { (key, _) -> key }

        assertEquals("[a]\nx = 1\n\n[z]\ny = 1\n", doc.toString())
    }

    @Test
    fun `sorting moves a table made by hand ahead of a parsed one`() {
        val doc = parse("[z]\ny = 1\n")
        doc.set("a", table("x" to 1L)).assertSuccess()

        doc.root.sortBy { (key, _) -> key }

        assertEquals("[a]\nx = 1\n\n[z]\ny = 1\n", doc.toString())
    }

    @Test
    fun `sorting orders values and tables made by hand`() {
        val doc = TomlDocument()
        doc.set("b", 2L).assertSuccess()
        doc.set("z", table("y" to 1L)).assertSuccess()
        doc.set("a", 1L).assertSuccess()

        doc.root.sortBy { (key, _) -> key }

        assertEquals(listOf("a", "b", "z"), doc.keys.toList())
        assertEquals("a = 1\nb = 2\n\n[z]\ny = 1\n", doc.toString())
    }

    @Test
    fun `a document with a byte order mark prints it back`() {
        assertEquals("﻿a = 1\n", parse("﻿a = 1\n").toString())
    }

    @Test
    fun `a document parsed from bytes with a byte order mark prints them back`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a = 1\n".encodeToByteArray()

        val doc = TomlDocument.parse(bytes).assertSuccess()

        assertContentEquals(bytes, doc.toString().encodeToByteArray())
    }

    @Test
    fun `a document read from a source with a byte order mark prints it back`() {
        val source = Buffer().apply { writeString("﻿a = 1\n") }

        val doc = TomlDocument.read(source).assertSuccess()

        assertEquals("﻿a = 1\n", doc.toString())
    }

    @Test
    fun `an edited document and its copy keep the byte order mark`() {
        val doc = parse("﻿a = 1\n")

        doc.set("b", 2L).assertSuccess()

        assertEquals("﻿a = 1\nb = 2\n", doc.toString())
        assertEquals("﻿a = 1\nb = 2\n", doc.copy().toString())
    }

    @Test
    fun `a new table in a CRLF document has a CRLF blank line above its header`() {
        val doc = parse("a = 1\r\n")

        doc.set("t", table("x" to 1L)).assertSuccess()

        assertEquals("a = 1\r\n\r\n[t]\r\nx = 1\r\n", doc.toString())
    }

    @Test
    fun `a final newline added after a closing comment prints`() {
        val doc = parse("a = 1\n# end")

        doc.trailing = "# end\n"

        assertEquals("a = 1\n# end\n", doc.toString())
    }

    @Test
    fun `a document without a final newline prints without one after a change`() {
        val doc = parse("a = 1")

        assertEquals("a = 1", doc.toString())
        assertEquals("a = 1", doc.copy().toString())
        doc.set("b", 2L).assertSuccess()
        assertEquals("a = 1\nb = 2", doc.toString())
        assertEquals("a = 1\nb = 2", doc.copy().toString())
    }

    @Test
    fun `read reports a closed source as an IO error`() {
        val raw: RawSource = Buffer().apply { writeString("a = 1\n") }
        val source = raw.buffered()
        source.close()

        TomlDocument.read(source).assertError<TomlIoError>()
    }

    @Test
    fun `writeTo reports a closed sink as an IO error`() {
        val raw: RawSink = Buffer()
        val sink = raw.buffered()
        sink.close()

        parse("a = 1\n").writeTo(sink).assertError<TomlIoError>()
    }

    @Test
    fun `a lone surrogate in a string is kept but not written and names its key`() {
        val doc = TomlDocument()
        doc.set("name", "half \uD800 pair").assertSuccess()
        val sink = Buffer()

        val error = doc.writeTo(sink).assertError<TomlWriteError.NotUnicode>()

        assertEquals(TomlWriteError.NotUnicode(listOf("name")), error)
        assertEquals(0L, sink.size)
        assertEquals("half \uD800 pair", doc["name"].assertSuccess().stringOrNull)
        assertTrue('\uD800' in doc.toString())
    }

    @Test
    fun `a lone surrogate in a nested key names the path to it`() {
        val doc = TomlDocument()
        doc.set("server", table("na\uDC00me" to 1L)).assertSuccess()

        val error = doc.writeTo(Buffer()).assertError<TomlWriteError.NotUnicode>()

        assertEquals(TomlWriteError.NotUnicode(listOf("server", "na\uDC00me")), error)
    }

    @Test
    fun `a lone surrogate in a comment is not written and names no key`() {
        val doc = parse("a = 1\n")
        doc.trailing = "# \uD800\n"

        val error = doc.writeTo(Buffer()).assertError<TomlWriteError.NotUnicode>()

        assertNull(error.keyPath)
    }

    @Test
    fun `a surrogate pair is written`() {
        val doc = TomlDocument()
        doc.set("emoji", "😀").assertSuccess()
        val sink = Buffer()

        doc.writeTo(sink).assertSuccess()

        assertEquals(doc.toString().encodeToByteArray().size.toLong(), sink.size)
    }
}
