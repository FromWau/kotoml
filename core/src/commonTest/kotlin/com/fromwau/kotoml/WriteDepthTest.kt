package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/** writeTo writes a document built to the limit, and refuses one that later edits made deeper. */
class WriteDepthTest {

    private val limit = TomlDocument.NESTING_LIMIT

    private fun nestedArrays(depth: Int): TomlDocument {
        var value = Value.Integer(1L) as Value
        repeat(depth) { value = Value.Array(tomlArrayOf(value).getOrFail()) }
        return TomlDocument().also { it.set("a", value).getOrFail() }
    }

    private fun nestedTables(depth: Int): TomlDocument {
        val doc = TomlDocument()
        var table = doc.root
        repeat(depth) {
            table = table.getOrPut("t") { Item(StandardTable()) }.getOrFail().standardTableOrNull!!
        }
        table.set("x", 1L).getOrFail()
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
    fun `tables nested to the limit are written and read back`() {
        val text = written(nestedTables(limit))
        TomlDocument.parse(text).getOrFail()
    }

    @Test
    fun `tables made dotted past the limit are TooDeep at the key that gets too deep`() {
        val doc = nestedTables(limit)
        var table = doc.root
        repeat(limit) {
            table = table.getOrNull("t")!!.standardTableOrNull!!
            table.isDotted = true
        }
        val error = doc.writeTo(Buffer()).errorOrFail()
        assertEquals(TomlWriteError.TooDeep(List(limit) { "t" } + "x"), error)
    }
}
