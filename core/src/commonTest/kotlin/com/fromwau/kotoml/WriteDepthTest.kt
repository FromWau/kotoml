package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/** writeTo refuses a document built deeper than the parser reads, and writes one built to the limit. */
class WriteDepthTest {

    private val limit = TomlDocument.NESTING_LIMIT

    private fun nestedArrays(depth: Int): TomlDocument {
        var value = Value.Integer(1L) as Value
        repeat(depth) { value = Value.Array(tomlArrayOf(value)) }
        return TomlDocument().also { it["a"] = value }
    }

    private fun nestedTables(depth: Int): TomlDocument {
        val doc = TomlDocument()
        var table = doc.root
        repeat(depth) {
            table = table.getOrPut("t") { Item(StandardTable()) }.standardTableOrNull!!
        }
        table["x"] = 1L
        return doc
    }

    private fun written(doc: TomlDocument): String {
        val buffer = Buffer()
        doc.writeTo(buffer).getOrFail()
        return buffer.readString()
    }

    @Test
    fun `arrays nested to the limit are written and read back`() {
        val text = written(nestedArrays(limit))
        TomlDocument.parse(text).getOrFail()
    }

    @Test
    fun `arrays nested past the limit are TooDeep at their key`() {
        assertEquals(TomlWriteError.TooDeep(listOf("a")), nestedArrays(limit + 1).writeTo(Buffer()).errorOrFail())
    }

    @Test
    fun `tables nested to the limit are written and read back`() {
        val text = written(nestedTables(limit))
        TomlDocument.parse(text).getOrFail()
    }

    @Test
    fun `tables nested past the limit are TooDeep at the deepest header`() {
        val error = nestedTables(limit + 1).writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.TooDeep(List(limit + 1) { "t" }), error)
    }

    @Test
    fun `a key in an inline table as deep as the limit is TooDeep`() {
        var value = Value.InlineTable(inlineTableOf("k" to Value.Integer(1L))) as Value
        repeat(limit - 1) { value = Value.Array(tomlArrayOf(value)) }
        val doc = TomlDocument().also { it["a"] = value }
        assertEquals(TomlWriteError.TooDeep(listOf("a", "k")), doc.writeTo(Buffer()).errorOrFail())
    }
}
