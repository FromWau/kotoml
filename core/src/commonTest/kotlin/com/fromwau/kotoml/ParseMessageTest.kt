package com.fromwau.kotoml

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A parse error names the mistake a person made, at the place they made it. */
class ParseMessageTest {

    private fun errorOf(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlParseError = TomlDocument.parse(text, version).errorOrFail()

    private fun syntax(reason: ParseErrorReason) = TomlParseError.Reason.Syntax(reason)

    @Ignore
    @Test
    fun `a line break inside a basic string is an unclosed string on its own line`() {
        val error = errorOf("desc = \"hello\nworld\"\n")
        assertEquals(syntax(ParseErrorReason.InvalidString(Encoding.BasicString)), error.reason)
        assertEquals(TextPosition(1, 14), error.position)
    }

    @Ignore
    @Test
    fun `a second value after a value is unexpected content`() {
        val numbers = errorOf("port = 80 80\n")
        assertEquals(syntax(ParseErrorReason.UnexpectedKeyOrValue), numbers.reason)
        assertEquals(TextPosition(1, 11), numbers.position)
        val booleans = errorOf("a = true false\n")
        assertEquals(syntax(ParseErrorReason.UnexpectedKeyOrValue), booleans.reason)
        assertEquals(TextPosition(1, 10), booleans.position)
    }

    @Ignore
    @Test
    fun `numbers in an array without a comma between them miss a comma`() {
        val two = errorOf("a = [1 2]\n")
        assertEquals(syntax(ParseErrorReason.MissingArrayComma), two.reason)
        assertEquals(TextPosition(1, 8), two.position)
        val three = errorOf("a = [1, 2 3]\n")
        assertEquals(syntax(ParseErrorReason.MissingArrayComma), three.reason)
        assertEquals(TextPosition(1, 11), three.position)
    }

    @Ignore
    @Test
    fun `a key with nothing after its equals sign misses a value`() {
        val error = errorOf("x = \n")
        assertEquals(syntax(ParseErrorReason.MissingValue), error.reason)
        assertEquals(TextPosition(1, 5), error.position)
    }

    @Ignore
    @Test
    fun `the escapes TOML 1_1 added are refused under 1_0 as 1_1 features`() {
        assertIs<TomlParseError.Reason.NotInToml10>(errorOf("a = \"\\x41\"\n", TomlVersion.V1_0).reason)
        assertIs<TomlParseError.Reason.NotInToml10>(errorOf("a = \"\\e\"\n", TomlVersion.V1_0).reason)
    }

    @Ignore
    @Test
    fun `an escape for a surrogate points at the whole escape`() {
        for (escape in listOf("\\uD800", "\\U00110000")) {
            val error = errorOf("a = \"$escape\"\n")
            assertEquals(TextPosition(1, 6), error.position, escape)
            assertEquals(escape.length, error.span!!.end - error.span.start, escape)
        }
    }

    @Ignore
    @Test
    fun `an error on a long line shows only the part around it`() {
        val text = "a = \"" + "x".repeat(1_000_000) + "\" junk\n"
        assertTrue(errorOf(text).render(text).length < 1000)
    }

    @Ignore
    @Test
    fun `an error shows a carriage return as an escape`() {
        val text = "a = \"x\ry\"\n"
        assertFalse('\r' in errorOf(text).render(text))
    }

    @Ignore
    @Test
    fun `the caret under a character outside ASCII is one wide`() {
        val text = "é = 1\n"
        val caretLine = errorOf(text).render(text).lines().first { '^' in it }
        assertEquals(1, caretLine.count { it == '^' })
    }

    @Ignore
    @Test
    fun `a malformed time names the time as the part refused`() {
        for (text in listOf("a = 07:32.5\n", "a = 07:32:00Z\n")) {
            val reason = errorOf(text).reason
            assertIs<TomlParseError.Reason.InvalidDatetime>(reason, text)
            assertEquals(DatetimePart.Time, reason.error.part, text)
        }
    }
}
