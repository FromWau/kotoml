package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class GetTest {

    private val sample = """
        |# Package metadata
        |[package]
        |name = "kotoml"   # the name
        |version = "0.1.0"
        |
        |[[servers]]
        |host = "alpha"
        |port = 8080
        |
        |[[servers]]
        |host = "beta"
        |port = 8081
        |""".trimMargin()

    private fun select(filter: String): Result<Selection, AppError> {
        val document = (TomlDocument.parse(sample) as? Result.Success)?.value ?: fail("sample does not parse")
        val parsed = when (val parsed = parseFilter(filter)) {
            is Result.Success -> parsed.value
            is Result.Error -> return parsed
        }
        return when (val selected = parsed.evaluate(Item(document.root))) {
            is Result.Success -> Result.Success(Selection(selected.value, parsed.streams))
            is Result.Error -> selected
        }
    }

    private fun selection(filter: String): Selection = when (val result = select(filter)) {
        is Result.Success -> result.value
        is Result.Error -> fail("$filter: ${result.error}")
    }

    private fun get(filter: String, raw: Boolean = false): String = selection(filter).print(raw)

    private fun json(filter: String): String = Json.encodeToString(SelectionSerializer, selection(filter))

    @Test
    fun `the root prints as written`() {
        assertEquals(sample.trimEnd(), get("."))
    }

    @Test
    fun `a table prints its lines without the header comment`() {
        assertEquals("name = \"kotoml\"   # the name\nversion = \"0.1.0\"", get(".package"))
    }

    @Test
    fun `a value prints without its decor`() {
        assertEquals("\"kotoml\"", get(".package.name"))
        assertEquals("kotoml", get(".package.name", raw = true))
    }

    @Test
    fun `iteration and pipes stream each result`() {
        assertEquals("\"alpha\"\n\"beta\"", get(".servers[] | .host"))
        assertEquals("8081", get(".servers[-1].port"))
    }

    @Test
    fun `an array of tables prints as an array of inline tables`() {
        assertEquals("[{ host = \"alpha\", port = 8080 }, { host = \"beta\", port = 8081 }]", get(".servers"))
    }

    @Test
    fun `a missing key or index is null`() {
        assertEquals("null", get(".nope.deeper"))
        assertEquals("null", get(".servers[5]"))
    }

    @Test
    fun `JSON prints one value or a stream as an array`() {
        assertEquals("""{"host":"alpha","port":8080}""", json(".servers[0]"))
        assertEquals("""["alpha","beta"]""", json(".servers[] | .host"))
        assertEquals("null", json(".nope"))
    }

    @Test
    fun `a selection reads back from plain JSON`() {
        val read = Json.decodeFromString(SelectionSerializer, """{"a":[1,2.5,"x",true],"n":null}""")
        assertEquals("{ a = [1, 2.5, \"x\", true] }", read.print(raw = false))
    }

    @Test
    fun `a step a scalar cannot take is an error`() {
        assertEquals(
            Result.Error(AppError.CannotApply(Step.Key("x"), TomlType.String)),
            select(".package.name.x"),
        )
    }
}
