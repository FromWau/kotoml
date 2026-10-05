package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every edit, write and date-time error reads as a sentence, led by its key path when it has one. */
class ErrorMessageTest {

    @Test
    fun `every edit error reads as a sentence`() {
        val cases = listOf(
            TomlEditError.NotFound("name") to "no key name",
            TomlEditError.NotFound("a b") to "no key \"a b\"",
            TomlEditError.TypeMismatch("server", TomlType.Table, TomlType.Integer) to
                "server: expected a table, found an integer",
            TomlEditError.IndexOutOfBounds(5, 3) to "index 5 is out of bounds for a length of 3",
            TomlEditError.NotATable("port", TomlType.String) to "a string has no key port",
            TomlEditError.NotAnArray(0, TomlType.InlineTable) to "an inline table has no index 0",
            TomlEditError.NotAValue(TomlType.ArrayOfTables) to
                "an inline table or array holds only values, not an array of tables",
            TomlEditError.NotATableElement(TomlType.Boolean) to "an array of tables holds only tables, not a boolean",
            TomlEditError.TooDeep(listOf("a", "b.c")) to
                "a.\"b.c\": nests deeper than 80 levels, so it would not parse back",
            TomlEditError.TooDeep(emptyList()) to "nests deeper than 80 levels, so it would not parse back",
        )
        for ((error, message) in cases) assertEquals(message, error.message, "$error")
    }

    @Test
    fun `every write error reads as a sentence`() {
        val cases = listOf(
            TomlWriteError.NotUnicode(listOf("server", "name")) to
                "server.name: half of a surrogate pair, which is not Unicode text",
            TomlWriteError.NotUnicode(null) to "half of a surrogate pair, which is not Unicode text",
            TomlWriteError.TooDeep(listOf("a")) to "a: nests deeper than 80 levels, so it would not parse back",
            TomlWriteError.NotInToml10(listOf("point"), Toml11Feature.InlineTableTrailingComma) to
                "point: inline tables cannot end with a comma in TOML 1.0",
            TomlIoError("disk full") to "reading or writing failed: disk full",
            TomlIoError(null) to "reading or writing failed",
        )
        for ((error, message) in cases) assertEquals(message, error.message, "$error")
    }

    @Test
    fun `a date that does not exist names the range of days its month has`() {
        assertEquals("invalid date, expected day between 01 and 28", Date.of(2021, 2, 29).errorOrFail().message)
    }

    @Test
    fun `every date-time error reads as the parser words it`() {
        val cases = listOf(
            Time.of(24, 0).errorOrFail() to "invalid time, expected hour between 00 and 23",
            Time.of(7, 32, nanosecond = 5).errorOrFail() to "invalid time, expected a second before the fraction",
            Offset.Custom.of(1440).errorOrFail() to "invalid offset, expected minutes between -1439 and 1439",
            Datetime.parse("1979-05").errorOrFail() to "invalid date, expected `-` (MM-DD)",
            DatetimeParseError(null, null) to "invalid datetime",
        )
        for ((error, message) in cases) assertEquals(message, error.message, "$error")
    }

    @Test
    fun `a document that holds what TOML 1_0 lacks names the key when written`() {
        val doc = TomlDocument.parse("point = { x = 1, }\n").getOrFail()
        doc.version = TomlVersion.V1_0
        val written = doc.writeTo(Buffer()).errorOrFail()
        assertEquals("point: inline tables cannot end with a comma in TOML 1.0", written.message)
    }

    @Test
    fun `indexing a missing key names it`() {
        val doc = TomlDocument.parse("a = 1\n").getOrFail()
        assertEquals("no key b", doc["b"].errorOrFail().message)
    }
}
