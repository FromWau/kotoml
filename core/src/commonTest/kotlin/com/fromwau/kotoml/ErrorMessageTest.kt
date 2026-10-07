package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            TomlEditError.NotExpandable(TomlType.Array) to
                "an array cannot take that table layout",
            TomlEditError.TooDeep(listOf("a", "b.c")) to
                "a.\"b.c\": nests deeper than 80 levels, so it would not parse back",
            TomlEditError.TooDeep(emptyList()) to "nests deeper than 80 levels, so it would not parse back",
            TomlEditError.CommentsWouldBeLost(listOf("t", "sub"), 2) to
                "t.sub: the new layout would drop 2 comments; pass force = true to drop them",
            TomlEditError.CommentsWouldBeLost(emptyList(), 1) to
                "the new layout would drop 1 comment; pass force = true to drop it",
            TomlEditError.NotInToml10(listOf("point"), Toml11Feature.InlineTableTrailingComma) to
                "point: inline tables cannot end with a comma in TOML 1.0",
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
            TomlIoError("disk full") to "reading or writing failed: disk full",
            TomlIoError(null) to "reading or writing failed",
        )
        for ((error, message) in cases) assertEquals(message, error.message, "$error")
    }

    @Test
    fun `a date that does not exist names the day and the range of days its month has`() {
        assertEquals("invalid date: day 29 is out of range 01 to 28", Date.of(2021, 2, 29).errorOrFail().message)
    }

    @Test
    fun `a file with a date that does not exist names the day and the range of days its month has`() {
        val reason = TomlDocument.parse("d = 2021-02-29\n").errorOrFail().reason
        assertIs<TomlParseError.Reason.InvalidDatetime>(reason)
        assertEquals("invalid date: day 29 is out of range 01 to 28", reason.error.message)
    }

    @Test
    fun `every date-time error reads as the parser words it`() {
        val cases = listOf(
            Time.of(24, 0).errorOrFail() to "invalid time: hour 24 is out of range 00 to 23",
            Time.of(7, 32, 0, 1_000_000_000).errorOrFail() to
                "invalid time: nanosecond 1000000000 is out of range 00 to 999999999",
            Time.of(7, 32, nanosecond = 5).errorOrFail() to "invalid time, expected a second before the fraction",
            Offset.Custom.of(1440).errorOrFail() to "invalid offset: minute 1440 is out of range -1439 to 1439",
            Datetime.parse("1979-05-27T07:32:00+24:00").errorOrFail() to
                "invalid offset: hour 24 is out of range 00 to 23",
            Datetime.parse("1979-05").errorOrFail() to "invalid date, expected `-` (MM-DD)",
            DatetimeParseError(null, null) to "invalid date-time",
            DatetimeParseError(DatetimePart.Date, DatetimeExpected.Range(DatetimeField.Day, 1, 28)) to
                "invalid date, expected day between 01 and 28",
        )
        for ((error, message) in cases) assertEquals(message, error.message, "$error")
    }

    @Test
    fun `an edit adding what TOML 1_0 lacks names the key`() {
        val doc = TomlDocument.parse("point = { x = 1 }\n", TomlVersion.V1_0).getOrFail()
        val refused = doc["point"].getOrFail().valueOrNull!!.inlineTableOrNull!!.setTrailingComma(true).errorOrFail()
        assertEquals("point: inline tables cannot end with a comma in TOML 1.0", refused.message)
    }

    @Test
    fun `indexing a missing key names it`() {
        val doc = TomlDocument.parse("a = 1\n").getOrFail()
        assertEquals("no key b", doc["b"].errorOrFail().message)
    }
}
