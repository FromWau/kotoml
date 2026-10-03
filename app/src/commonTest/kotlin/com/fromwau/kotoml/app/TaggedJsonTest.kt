package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.Untagged)),
            parseTaggedJson("""{"a": 1}"""),
        )
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a[1]", TaggedJsonProblem.BadValue("integer", "x"))),
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
                Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.BadValue(type, value))),
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

    private fun nestedArrays(depth: Int): String = "a = " + "[".repeat(depth) + "1" + "]".repeat(depth) + "\n"

    @Test
    fun `the deepest arrays TOML reads encode back`() {
        val json = decode(nestedArrays(TomlDocument.NESTING_LIMIT))
        assertTrue(parseTaggedJson(json) is Result.Success)
    }

    @Test
    fun `JSON nested one level deeper than TOML reads is refused`() {
        val json = "{\"a\":" + "[".repeat(TomlDocument.NESTING_LIMIT + 1) +
            "{\"type\":\"integer\",\"value\":\"1\"}" + "]".repeat(TomlDocument.NESTING_LIMIT + 1) + "}"
        assertEquals(Result.Error(AppError.JsonTooDeep(TomlDocument.NESTING_LIMIT + 2)), parseTaggedJson(json))
    }

    @Test
    fun `JSON nested far deeper is refused instead of crashing`() {
        val json = "{\"a\":" + "[".repeat(50_000) + "]".repeat(50_000) + "}"
        assertEquals(Result.Error(AppError.JsonTooDeep(TomlDocument.NESTING_LIMIT + 2)), parseTaggedJson(json))
    }

    @Test
    fun `brackets inside a string do not count as nesting`() {
        val text = "[".repeat(200) + "\\\""
        val json = """{"a":{"type":"string","value":"${text.replace("\\", "\\\\").replace("\"", "\\\"")}"}}"""
        assertTrue(parseTaggedJson(json) is Result.Success)
    }

    private fun badValue(type: String, value: String): Result<TomlDocument, AppError> =
        Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.BadValue(type, value)))

    private fun scalar(type: String, value: String): Result<TomlDocument, AppError> =
        parseTaggedJson("""{"a": {"type": "$type", "value": "$value"}}""")

    @Test
    fun `integers TOML does not spell or that overflow 64 bits are refused`() {
        val refused = listOf("007", "1__0", "_1", " 1", "1 ", "", "-", "1.5", "\u0661\u0662", "9223372036854775808")
        for (value in refused) {
            assertEquals(badValue("integer", value), scalar("integer", value), "integer $value")
        }
    }

    @Test
    fun `integers in any TOML spelling are read`() {
        val read = listOf("0", "42", "-17", "+5", "1_000", "0x10", "0o17", "0b101", "9223372036854775807")
        for (value in read) {
            assertTrue(scalar("integer", value) is Result.Success, "integer $value")
        }
    }

    @Test
    fun `floats TOML does not spell or that overflow are refused`() {
        val refused = listOf(
            "0x1p3", "1f", "1d", " 1.0", "Infinity", "NaN", "1e400", "01.5", ".5", "1.", "1e", "inf ", "",
        )
        for (value in refused) {
            assertEquals(badValue("float", value), scalar("float", value), "float $value")
        }
    }

    @Test
    fun `floats in any TOML spelling and integer spellings are read`() {
        val read = listOf(
            "0", "100", "-0.0", "3.14", "+1.0", "1_000.5", "1e10", "1.0E10", "4.9E-324", "6.02e+23",
            "inf", "+inf", "-inf", "nan", "+nan", "-nan",
        )
        for (value in read) {
            assertTrue(scalar("float", value) is Result.Success, "float $value")
        }
    }

    private fun problem(json: String): Result<TomlDocument, AppError> = parseTaggedJson(json)

    @Test
    fun `a root that is not an object is refused at the root`() {
        assertEquals(Result.Error(AppError.NotTaggedJson("$", TaggedJsonProblem.RootNotObject)), problem("[1]"))
    }

    @Test
    fun `an unknown type is named`() {
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.UnknownType("bogus"))),
            problem("""{"a":{"type":"bogus","value":"x"}}"""),
        )
    }

    @Test
    fun `a tagged value without a value is refused at its own key`() {
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.MissingValue)),
            problem("""{"a":{"type":"string"}}"""),
        )
    }

    @Test
    fun `a value that is not a JSON string is refused at its own key`() {
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.ValueNotString)),
            problem("""{"a":{"type":"integer","value":5}}"""),
        )
    }

    @Test
    fun `an extra key beside type and value is named`() {
        assertEquals(
            Result.Error(AppError.NotTaggedJson("$.a", TaggedJsonProblem.ExtraKey("extra"))),
            problem("""{"a":{"type":"string","value":"x","extra":1}}"""),
        )
    }

    @Test
    fun `a table with a key named type still reads as a table`() {
        val json = """{"t":{"type":{"type":"string","value":"x"},"value":{"type":"integer","value":"1"}}}"""
        val document = when (val parsed = problem(json)) {
            is Result.Success -> parsed.value
            is Result.Error -> fail("${parsed.error}")
        }
        assertEquals(json, document.toTaggedJson().toString())
    }

    @Test
    fun `the human text says where and what`() {
        val error = AppError.NotTaggedJson("$.a", TaggedJsonProblem.BadValue("integer", "x"))
        assertEquals("not toml-test's tagged JSON at $.a: \"x\" is not a valid integer", error.describe())
    }

    @Test
    fun `invalid JSON is refused with the line and column where it broke`() {
        assertEquals(Result.Error(AppError.InvalidJson(TextPosition(0, 1))), parseTaggedJson("{bad"))
        assertEquals(Result.Error(AppError.InvalidJson(TextPosition(1, 6))), parseTaggedJson("{\n  \"a\" 1}"))
    }

    @Test
    fun `invalid JSON reads as where it broke and nothing more`() {
        assertEquals("invalid JSON at line 2, column 6", AppError.InvalidJson(TextPosition(1, 5)).describe())
    }
}
