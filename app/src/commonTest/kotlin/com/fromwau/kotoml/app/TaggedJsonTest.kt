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
    fun scalarsAreTagged() {
        assertEquals(
            """{"a":{"type":"integer","value":"1"},"b":{"type":"float","value":"inf"},""" +
                """"c":{"type":"time-local","value":"07:32:00"}}""",
            decode("a = 1\nb = inf\nc = 07:32\n"),
        )
    }

    @Test
    fun tablesAndArraysOfTablesNest() {
        assertEquals(
            """{"t":{"x":[{"type":"bool","value":"true"}]},"aot":[{},{"k":{"type":"string","value":"v"}}]}""",
            decode("[t]\nx = [true]\n[[aot]]\n[[aot]]\nk = \"v\"\n"),
        )
    }

    @Test
    fun encodingReadsWhatDecodingWrites() {
        val json = decode("a = 1\n[t]\nd = 1979-05-27T07:32:00Z\nl = [1.5, 'x']\n")
        val document = when (val parsed = parseTaggedJson(json)) {
            is Result.Success -> parsed.value
            is Result.Error -> fail("$json: ${parsed.error}")
        }
        assertEquals(json, document.toTaggedJson().toString())
    }

    @Test
    fun untaggedJsonIsRefusedWithWhere() {
        assertEquals(Result.Error(AppError.NotTaggedJson("$.a")), parseTaggedJson("""{"a": 1}"""))
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a[1]")),
            parseTaggedJson("""{"a": [{"type": "integer", "value": "1"}, {"type": "integer", "value": "x"}]}"""),
        )
    }
}
