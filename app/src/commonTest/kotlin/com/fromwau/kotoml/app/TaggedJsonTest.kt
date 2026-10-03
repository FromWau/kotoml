package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.TomlDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class TaggedJsonTest {

    private fun decode(toml: String): String = when (val parsed = TomlDocument.parse(toml)) {
        is Result.Success -> parsed.value.toTaggedJson().toString()
        is Result.Error -> fail("$toml: ${parsed.error}")
    }

    @Test
    fun `scalars are tagged with their type`() {
        assertEquals(
            """{"a":{"type":"integer","value":"1"},"b":{"type":"float","value":"inf"},""" +
                """"c":{"type":"time-local","value":"07:32:00"}}""",
            decode("a = 1\nb = inf\nc = 07:32\n"),
        )
    }

    @Test
    fun `tables and arrays of tables nest`() {
        assertEquals(
            """{"t":{"x":[{"type":"bool","value":"true"}]},"aot":[{},{"k":{"type":"string","value":"v"}}]}""",
            decode("[t]\nx = [true]\n[[aot]]\n[[aot]]\nk = \"v\"\n"),
        )
    }

    @Test
    fun `encoding reads what decoding writes`() {
        val json = decode("a = 1\n[t]\nd = 1979-05-27T07:32:00Z\nl = [1.5, 'x']\n")
        val document = when (val parsed = parseTaggedJson(json)) {
            is Result.Success -> parsed.value
            is Result.Error -> fail("$json: ${parsed.error}")
        }
        assertEquals(json, document.toTaggedJson().toString())
    }

    @Test
    fun `untagged JSON is refused with where it was found`() {
        assertEquals(Result.Error(AppError.NotTaggedJson("$.a")), parseTaggedJson("""{"a": 1}"""))
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a[1]")),
            parseTaggedJson("""{"a": [{"type": "integer", "value": "1"}, {"type": "integer", "value": "x"}]}"""),
        )
    }

    @Test
    fun `a date-time whose kind does not match its tag is refused`() {
        val mismatches = listOf(
            "datetime" to "1979-05-27",
            "datetime" to "1979-05-27T07:32:00",
            "datetime-local" to "1979-05-27T07:32:00Z",
            "date-local" to "1979-05-27T07:32:00",
            "time-local" to "1979-05-27",
        )
        for ((type, value) in mismatches) {
            assertEquals(
                Result.Error(AppError.NotTaggedJson("$.a")),
                parseTaggedJson("""{"a": {"type": "$type", "value": "$value"}}"""),
                "$type with $value",
            )
        }
    }

    @Test
    fun `each date-time tag accepts its own kind`() {
        val matches = listOf(
            "datetime" to "1979-05-27T07:32:00Z",
            "datetime-local" to "1979-05-27T07:32:00",
            "date-local" to "1979-05-27",
            "time-local" to "07:32:00",
        )
        for ((type, value) in matches) {
            val json = """{"a":{"type":"$type","value":"$value"}}"""
            val document = when (val parsed = parseTaggedJson(json)) {
                is Result.Success -> parsed.value
                is Result.Error -> fail("$type: ${parsed.error}")
            }
            assertEquals(json, document.toTaggedJson().toString(), type)
        }
    }
}
