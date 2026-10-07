package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.readString

/** writeTo writes a document built to the limit, and refuses one that later edits made deeper. */
class WriteDepthTest {

    private val limit = TomlDocument.NESTING_LIMIT

    private fun nestedArrays(depth: Int): TomlDocument {
        var value = Value.Integer(1L) as Value
        repeat(depth) { value = Value.Array(tomlArrayOf(value).assertSuccess()) }
        return TomlDocument().also { it.set("a", value).assertSuccess() }
    }

    private fun nestedTables(depth: Int): TomlDocument {
        val doc = TomlDocument()
        var table = doc.root
        repeat(depth) {
            table = table.getOrPut("t") { Item(StandardTable()) }.assertSuccess().standardTableOrNull!!
        }
        table.set("x", 1L).assertSuccess()
        return doc
    }

    private fun written(doc: TomlDocument): String {
        val buffer = Buffer()
        doc.writeTo(buffer).assertSuccess()
        return buffer.readString()
    }

    @Test
    fun `arrays nested to the limit are written and read back`() {
        val text = written(nestedArrays(limit))
        TomlDocument.parse(text).assertSuccess()
    }

    @Test
    fun `tables nested to the limit are written and read back`() {
        val text = written(nestedTables(limit))
        TomlDocument.parse(text).assertSuccess()
    }

    @Test
    fun `tables made dotted past the limit print as headers where a dotted line would be too deep`() {
        val doc = nestedTables(limit)
        var table = doc.root
        repeat(limit) {
            val slot = table.getOrNull("t")!!
            slot.setLayout(TableLayout.Dotted).assertSuccess()
            table = slot.standardTableOrNull!!
        }
        val text = doc.toString()
        assertEquals(text, written(doc))
        assertTrue(TomlDocument.parse(text).assertSuccess().toTagged().sameAs(doc.toTagged()))
    }
}
