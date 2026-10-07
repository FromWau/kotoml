package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.flatMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

/** Indexing returns the stored item or a typed error, and writing never creates a missing parent. */
class IndexingTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

    @Test
    fun `a key that is not there is NotFound`() {
        val doc = parse("[server]\nport = 8080\n")
        assertEquals(TomlEditError.NotFound("database"), doc["database"].assertError<TomlEditError.NotFound>())
        assertEquals(TomlEditError.NotFound("timeout"), doc["server"]["timeout"].assertError<TomlEditError.NotFound>())
    }

    @Test
    fun `a chain stops at the first key it misses and passes that error on`() {
        val doc = parse("[server]\nport = 8080\n")
        assertEquals(TomlEditError.NotFound("database"), doc["database"]["url"][0]["x"].assertError<TomlEditError.NotFound>())
    }

    @Test
    fun `a chain through present keys reads the stored item`() {
        val doc = parse("[server]\nhosts = [\"a\", \"b\"]\n")
        assertEquals("b", doc["server"]["hosts"][1].assertSuccess().stringOrNull)
    }

    @Test
    fun `indexing by key into a scalar is NotATable`() {
        val doc = parse("port = 8080\n")
        assertEquals(TomlEditError.NotATable("x", TomlType.Integer), doc["port"]["x"].assertError<TomlEditError.NotATable>())
    }

    @Test
    fun `indexing by position past the end or into a scalar names the problem`() {
        val doc = parse("ports = [1, 2]\nport = 1\n")
        assertEquals(TomlEditError.IndexOutOfBounds(5, 2), doc["ports"][5].assertError<TomlEditError.IndexOutOfBounds>())
        assertEquals(TomlEditError.NotAnArray(0, TomlType.Integer), doc["port"][0].assertError<TomlEditError.NotAnArray>())
    }

    @Test
    fun `writing under a missing parent is NotFound and changes nothing`() {
        val text = "[server]\nport = 8080\n"
        val doc = parse(text)
        val error = doc["database"].flatMap { it.set("url", "postgres://") }.assertError<TomlEditError.NotFound>()
        assertEquals(TomlEditError.NotFound("database"), error)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `writing under a missing parent with the index form throws NotFound and changes nothing`() {
        val text = "[server]\nport = 8080\n"
        val doc = parse(text)
        val thrown = assertFailsWith<TomlEditException> { doc["database"]["url"] = "postgres://" }
        assertEquals(TomlEditError.NotFound("database"), thrown.error)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a missing parent written first takes the key written under it`() {
        val doc = parse("[server]\nport = 8080\n")
        doc["database"] = StandardTable()
        doc["database"]["url"] = "postgres://"
        assertEquals("[server]\nport = 8080\n\n[database]\nurl = \"postgres://\"\n", doc.toString())
    }

    @Test
    fun `a removed key is gone and written again it is added at the end`() {
        val doc = parse("# about port\nport = 1\nhost = \"x\"\n")
        doc.remove("port", force = true)
        assertEquals(TomlEditError.NotFound("port"), doc["port"].assertError<TomlEditError.NotFound>())
        assertEquals(setOf("host"), doc.keys)
        doc["port"] = 2L
        assertEquals("host = \"x\"\nport = 2\n", doc.toString())
    }

    @Test
    fun `an inline table indexes to its value or NotFound`() {
        val table = parse("t = { a = 1 }\n")["t"].assertSuccess().inlineTableOrNull!!
        assertEquals(1L, table["a"].assertSuccess().longOrNull)
        assertEquals(TomlEditError.NotFound("b"), table["b"].assertError<TomlEditError.NotFound>())
        assertEquals(1L, table.getOrNull("a")?.longOrNull)
        assertEquals(null, table.getOrNull("b"))
    }

    @Test
    fun `an array indexes to its value or IndexOutOfBounds`() {
        val array = parse("a = [1, 2]\n")["a"].assertSuccess().arrayOrNull!!
        assertEquals(2L, array[1].assertSuccess().longOrNull)
        assertEquals(TomlEditError.IndexOutOfBounds(2, 2), array[2].assertError<TomlEditError.IndexOutOfBounds>())
        assertEquals(TomlEditError.IndexOutOfBounds(-1, 2), array[-1].assertError<TomlEditError.IndexOutOfBounds>())
        assertEquals(1L, array.getOrNull(0)?.longOrNull)
        assertEquals(null, array.getOrNull(2))
    }

    @Test
    fun `an array of tables indexes to its table or IndexOutOfBounds`() {
        val tables = parse("[[t]]\na = 1\n")["t"].assertSuccess().arrayOfTablesOrNull!!
        assertEquals(1L, tables[0].assertSuccess()["a"].assertSuccess().longOrNull)
        assertEquals(TomlEditError.IndexOutOfBounds(1, 1), tables[1].assertError<TomlEditError.IndexOutOfBounds>())
        assertEquals(setOf("a"), tables.getOrNull(0)?.keys)
        assertEquals(null, tables.getOrNull(1))
    }

    @Test
    fun `a document reads a missing key as null through getOrNull`() {
        val doc = parse("a = 1\n")
        assertEquals(1L, doc.getOrNull("a")?.longOrNull)
        assertEquals(null, doc.getOrNull("b"))
    }

    @Test
    fun `NotFound encodes with the key it missed`() {
        assertEquals(
            """{"type":"NotFound","key":"database"}""",
            Json.encodeToString(TomlEditError.serializer(), TomlEditError.NotFound("database")),
        )
    }
}
