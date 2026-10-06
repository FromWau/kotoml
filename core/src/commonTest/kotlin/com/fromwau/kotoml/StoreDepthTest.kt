package com.fromwau.kotoml

import com.fromwau.kern.result.getOrNull
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** A store that would nest deeper than the parser reads is TooDeep and changes nothing, wherever it stores. */
class StoreDepthTest {

    private val limit = TomlDocument.NESTING_LIMIT

    private fun tooDeep(vararg keys: String) = TomlEditError.TooDeep(keys.toList())

    private fun tooDeep(keys: List<String>) = TomlEditError.TooDeep(keys)

    private fun repeated(key: String, times: Int) = List(times) { key }

    /** The innermost of [depth] arrays nested at key `a` of [doc], each stored into the one around it. */
    private fun innermostArray(
        doc: TomlDocument,
        depth: Int,
    ): TomlArray {
        doc.set("a", Value.Array(TomlArray())).getOrFail()
        var array = doc["a"].getOrFail().arrayOrNull!!
        repeat(depth - 1) {
            array.add(Value.Array(TomlArray())).getOrFail()
            array = array[0].getOrFail().arrayOrNull!!
        }
        return array
    }

    /** The innermost of [depth] tables under each other at key `t`, whose headers have up to [depth] parts. */
    private fun innermostTable(
        doc: TomlDocument,
        depth: Int,
    ): StandardTable {
        var table = doc.root
        repeat(depth) { table = table.getOrPut("t") { Item(StandardTable()) }.getOrFail().standardTableOrNull!! }
        return table
    }

    private fun assertUnchanged(
        doc: TomlDocument,
        edit: () -> TomlEditError,
        expected: TomlEditError,
    ) {
        val before = doc.toString()
        assertEquals(expected, edit())
        assertEquals(before, doc.toString())
    }

    @Test
    fun `an array in the deepest array is TooDeep and a scalar is not`() {
        val doc = TomlDocument()
        val deepest = innermostArray(doc, limit)
        assertUnchanged(doc, { deepest.add(Value.Array(TomlArray())).errorOrFail() }, tooDeep("a"))
        deepest.add(1L)
        TomlDocument.parse(doc.toString()).getOrFail()
    }

    @Test
    fun `a key in an inline table as deep as the limit is TooDeep`() {
        val doc = TomlDocument()
        val holder = innermostArray(doc, limit - 1)
        holder.add(Value.InlineTable(InlineTable())).getOrFail()
        val table = holder[0].getOrFail().inlineTableOrNull!!
        assertUnchanged(doc, { table.set("k", 1L).errorOrFail() }, tooDeep("a", "k"))
    }

    @Test
    fun `a table under a header with as many parts as the limit is TooDeep`() {
        val doc = TomlDocument()
        val deepest = innermostTable(doc, limit)
        assertUnchanged(doc, { deepest.set("t", StandardTable()).errorOrFail() }, tooDeep(repeated("t", limit + 1)))
        deepest.set("x", 1L).getOrFail()
        TomlDocument.parse(doc.toString()).getOrFail()
    }

    @Test
    fun `a table in an array of tables whose header has as many parts as the limit is TooDeep`() {
        val doc = TomlDocument()
        val holder = innermostTable(doc, limit - 1)
        holder.set("aot", ArrayOfTables()).getOrFail()
        val tables = holder.getOrNull("aot")!!.arrayOfTablesOrNull!!
        tables.add(StandardTable()).getOrFail()
        val path = repeated("t", limit - 1) + "aot" + "t"
        assertUnchanged(doc, { tables[0].getOrFail().set("t", StandardTable()).errorOrFail() }, tooDeep(path))
    }

    @Test
    fun `an array of tables past the limit is TooDeep and stores none of its tables`() {
        val doc = TomlDocument()
        val deepest = innermostTable(doc, limit)
        val tables = arrayOfTablesOf(StandardTable()).getOrFail()
        assertUnchanged(doc, { deepest.set("aot", tables).errorOrFail() }, tooDeep(repeated("t", limit) + "aot"))
    }

    @Test
    fun `tables turned from dotted into headers past the limit take no more tables`() {
        val doc = TomlDocument()
        var table = innermostTable(doc, limit)
        val dotted = mutableListOf<StandardTable>()
        repeat(limit - 1) {
            table = table.getOrPut("d") { Item(StandardTable().also { it.isDotted = true }) }
                .getOrFail()
                .standardTableOrNull!!
            dotted += table
        }
        for (each in dotted) each.isDotted = false
        val child = StandardTable()
        child.isDotted = true
        child.set("x", 1L).getOrFail()
        val path = repeated("t", limit) + repeated("d", limit)
        assertUnchanged(doc, { table.set("d", child).errorOrFail() }, tooDeep(path))
    }

    @Test
    fun `the dots of a line count toward the limit with the brackets of its value`() {
        val keys = List(limit) { "k$it" }
        val doc = TomlDocument.parse(keys.joinToString(".") + " = 1\n").getOrFail()
        var table = doc.root
        for (key in keys.dropLast(1)) table = table.getOrNull(key)!!.standardTableOrNull!!
        table.set("array", Value.Array(TomlArray())).getOrFail()
        val inline = Value.InlineTable(inlineTableOf("x" to Value.Integer(1L)).getOrFail())
        assertUnchanged(doc, { table.set("inline", inline).errorOrFail() }, tooDeep(keys.dropLast(1) + "inline" + "x"))
        TomlDocument.parse(doc.toString()).getOrFail()
    }

    @Test
    fun `assigning an array into a slot of the deepest array is TooDeep`() {
        val doc = TomlDocument()
        innermostArray(doc, limit).add(1L)
        var slot = doc["a"]
        repeat(limit) { slot = slot[0] }
        assertUnchanged(doc, { slot.getOrFail().assign(Item(Value.Array(TomlArray()))).errorOrFail() }, tooDeep("a"))
    }

    @Test
    fun `a table made a value too deep for its line is TooDeep`() {
        val doc = TomlDocument()
        innermostTable(doc, limit).set("x", 1L).getOrFail()
        val error = doc["t"].getOrFail().inline().errorOrFail()
        assertEquals(tooDeep(repeated("t", limit) + "x"), error)
        assertEquals(TomlType.Table, doc["t"].getOrFail().type)
    }

    @Test
    fun `converting tables nested to the limit into inline tables is TooDeep`() {
        val doc = TomlDocument()
        innermostTable(doc, limit).set("x", 1L).getOrFail()
        val error = doc.root.getOrNull("t")!!.standardTableOrNull!!.toInlineTable().errorOrFail()
        assertEquals(tooDeep(repeated("t", limit - 1) + "x"), error)
    }

    @Test
    fun `an array built bottom up is TooDeep at the level past the limit`() {
        var value: Value = Value.Integer(1L)
        repeat(limit) { value = Value.Array(tomlArrayOf(value).getOrFail()) }
        assertEquals(tooDeep(), tomlArrayOf(value).errorOrFail())
    }

    @Test
    fun `content built to the limit is TooDeep when stored where its line has dots`() {
        var value: Value = Value.Integer(1L)
        repeat(limit) { value = Value.Array(tomlArrayOf(value).getOrFail()) }
        val doc = TomlDocument.parse("a.b = 1\n").getOrFail()
        val dotted = doc["a"].getOrFail().standardTableOrNull!!
        assertUnchanged(doc, { dotted.set("c", value).errorOrFail() }, tooDeep("a", "c"))
        doc.set("c", value).getOrFail()
    }

    @Test
    fun `a document at the parser's limits still takes edits within them`() {
        val header = List(limit) { "h" }.joinToString(".")
        val brackets = "[".repeat(limit) + "]".repeat(limit)
        val doc = TomlDocument.parse("[$header]\nk = $brackets\n").getOrFail()
        var table = doc.root
        repeat(limit) { table = table.getOrNull("h")!!.standardTableOrNull!! }
        table.set("x", 1L).getOrFail()
        var array = table.getOrNull("k")!!.arrayOrNull!!
        repeat(limit - 1) { array = array[0].getOrFail().arrayOrNull!! }
        array.add(2L)
        TomlDocument.parse(doc.toString()).getOrFail()
    }

    @Test
    fun `nesting thousands of times stops with TooDeep`() {
        var value: Value = Value.Integer(1L)
        var levels = 0
        while (levels < 5000) {
            value = Value.Array(tomlArrayOf(value).getOrNull() ?: break)
            levels++
        }
        assertEquals(limit, levels)
        val doc = TomlDocument()
        var table = doc.root
        var tables = 0
        while (tables < 5000) {
            table = table.getOrPut("t") { Item(StandardTable()) }.getOrNull()?.standardTableOrNull ?: break
            tables++
        }
        assertEquals(limit, tables)
    }

    @Test
    fun `putAll past the limit stores none of the pairs`() {
        val doc = TomlDocument()
        val deepest = innermostTable(doc, limit)
        val pairs = listOf("x" to Item(Value.Integer(1L)), "t" to Item(StandardTable()))
        assertUnchanged(doc, { deepest.putAll(pairs).errorOrFail() }, tooDeep(repeated("t", limit + 1)))
    }

    @Test
    fun `a builder block that nests too deep fails and an edit leaves the document as it was`() {
        fun TableScope.nest(levels: Int) {
            if (levels == 0) "x" to 1 else table("t") { nest(levels - 1) }
        }
        assertEquals(tooDeep(repeated("t", limit + 1)), buildTomlDocument { nest(limit + 1) }.errorOrFail())
        val text = "a = 1\n"
        val doc = TomlDocument.parse(text).getOrFail()
        assertEquals(tooDeep(repeated("t", limit + 1)), doc.edit { nest(limit + 1) }.errorOrFail())
        assertEquals(text, doc.toString())
    }

    @Test
    fun `TooDeep encodes with its key path`() {
        assertEquals(
            """{"type":"TooDeep","keyPath":["a","b"]}""",
            Json.encodeToString(TomlEditError.serializer(), tooDeep("a", "b")),
        )
    }
}
