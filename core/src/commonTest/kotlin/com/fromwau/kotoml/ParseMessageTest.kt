package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
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
    ): TomlParseError = TomlDocument.parse(text, version).assertError<TomlParseError>()

    private fun syntax(reason: ParseErrorReason) = TomlParseError.Reason.Syntax(reason)

    @Test
    fun `a line break inside a basic string is an unclosed string on its own line`() {
        val error = errorOf("desc = \"hello\nworld\"\n")
        assertEquals(syntax(ParseErrorReason.InvalidString(Encoding.BasicString)), error.reason)
        assertEquals(TextPosition(1, 14), error.position)
    }

    @Test
    fun `a second value after a value is unexpected content`() {
        val numbers = errorOf("port = 80 80\n")
        assertEquals(syntax(ParseErrorReason.UnexpectedKeyOrValue), numbers.reason)
        assertEquals(TextPosition(1, 11), numbers.position)
        val booleans = errorOf("a = true false\n")
        assertEquals(syntax(ParseErrorReason.UnexpectedKeyOrValue), booleans.reason)
        assertEquals(TextPosition(1, 10), booleans.position)
    }

    @Test
    fun `numbers in an array without a comma between them miss a comma`() {
        val two = errorOf("a = [1 2]\n")
        assertEquals(syntax(ParseErrorReason.MissingArrayComma), two.reason)
        assertEquals(TextPosition(1, 8), two.position)
        val three = errorOf("a = [1, 2 3]\n")
        assertEquals(syntax(ParseErrorReason.MissingArrayComma), three.reason)
        assertEquals(TextPosition(1, 11), three.position)
    }

    @Test
    fun `a key with nothing after its equals sign misses a value`() {
        val error = errorOf("x = \n")
        assertEquals(syntax(ParseErrorReason.MissingValue), error.reason)
        assertEquals(TextPosition(1, 5), error.position)
    }

    @Test
    fun `the escapes TOML 1_1 added are refused under 1_0 as 1_1 features`() {
        assertIs<TomlParseError.Reason.NotInToml10>(errorOf("a = \"\\x41\"\n", TomlVersion.V1_0).reason)
        assertIs<TomlParseError.Reason.NotInToml10>(errorOf("a = \"\\e\"\n", TomlVersion.V1_0).reason)
    }

    @Test
    fun `an escape for a surrogate points at the whole escape`() {
        for (escape in listOf("\\uD800", "\\U00110000")) {
            val error = errorOf("a = \"$escape\"\n")
            assertEquals(TextPosition(1, 6), error.position, escape)
            assertEquals(escape.length, error.span!!.end - error.span.start, escape)
        }
    }

    @Test
    fun `an error on a long line shows only the part around it`() {
        val text = "a = \"" + "x".repeat(1_000_000) + "\" junk\n"
        assertTrue(errorOf(text).render(text).length < 1000)
    }

    @Test
    fun `an error shows a carriage return as an escape`() {
        val text = "a = \"x\ry\"\n"
        assertFalse('\r' in errorOf(text).render(text))
    }

    @Test
    fun `the caret under a character outside ASCII is one wide`() {
        val text = "é = 1\n"
        val caretLine = errorOf(text).render(text).lines().first { '^' in it }
        assertEquals(1, caretLine.count { it == '^' })
    }

    @Test
    fun `a malformed time names the time as the part refused`() {
        for (text in listOf("a = 07:32.5\n", "a = 07:32:00Z\n")) {
            val reason = errorOf(text).reason
            assertIs<TomlParseError.Reason.InvalidDatetime>(reason, text)
            assertEquals(DatetimePart.Time, reason.error.part, text)
        }
    }

    @Test
    fun `the caret after a tab lines up under the tab`() {
        val text = "a\tb = 1\n"
        val caretLine = errorOf(text).render(text).lines().first { '^' in it }
        assertEquals("  |  \t^", caretLine)
    }

    @Test
    fun `a duplicate key names its path from the document root`() {
        val cases = mapOf(
            "[a.b]\nx = 1\n[a.b]\n" to listOf("a", "b"),
            "[t]\nx.y = 1\nx.y = 2\n" to listOf("t", "x", "y"),
            "[[t]]\nx = 1\nx = 2\n" to listOf("t", "x"),
            "a = { b = { c = 1, c = 2 } }\n" to listOf("a", "b", "c"),
            "a = [{ b = { c = 1 } }, { d = 1, d = 2 }]\n" to listOf("a", "d"),
        )
        for ((text, keyPath) in cases) {
            assertEquals(TomlParseError.Reason.DuplicateKey(keyPath), errorOf(text).reason, text)
        }
    }

    @Test
    fun `a word near inf or nan is a misspelt float`() {
        val cases = mapOf(
            "a = in\n" to "inf",
            "a = -in\n" to "inf",
            "a = Inf\n" to "inf",
            "a = INF\n" to "inf",
            "a = in_f\n" to "inf",
            "a = ifn\n" to "inf",
            "a = na\n" to "nan",
            "a = +NaN\n" to "nan",
            "a = -nAn\n" to "nan",
            "a = nna\n" to "nan",
        )
        for ((text, symbol) in cases) {
            val error = errorOf(text)
            assertEquals(syntax(ParseErrorReason.InvalidScalar(ScalarKind.Float)), error.reason, text)
            assertEquals(listOf(Expected.Literal(symbol)), error.expected, text)
        }
    }

    @Test
    fun `a word near true or false is a misspelt boolean`() {
        val cases = mapOf(
            "a = True\n" to "true",
            "a = TRUE\n" to "true",
            "a = tru\n" to "true",
            "a = ture\n" to "true",
            "a = trye\n" to "true",
            "a = tdue\n" to "true",
            "a = fals\n" to "false",
            "a = False\n" to "false",
            "a = fasle\n" to "false",
            "a = faalse\n" to "false",
        )
        for ((text, keyword) in cases) {
            val error = errorOf(text)
            val value = keyword.toBooleanStrict()
            assertEquals(syntax(ParseErrorReason.InvalidScalar(ScalarKind.Boolean(value))), error.reason, text)
            assertEquals(listOf(Expected.Literal(keyword)), error.expected, text)
        }
    }

    @Test
    fun `a word with a typo in its first letter is the nearest misspelt keyword`() {
        val cases = mapOf(
            "a = rue\n" to "true",
            "a = yrue\n" to "true",
            "a = Rue\n" to "true",
            "a = alse\n" to "false",
            "a = nf\n" to "inf",
            "a = +nf\n" to "inf",
            "a = an\n" to "nan",
            "a = tr\n" to "true",
            "a = fa\n" to "false",
        )
        for ((text, keyword) in cases) {
            val error = errorOf(text)
            val kind = keyword.toBooleanStrictOrNull()?.let { ScalarKind.Boolean(it) } ?: ScalarKind.Float
            assertEquals(syntax(ParseErrorReason.InvalidScalar(kind)), error.reason, text)
            assertEquals(listOf(Expected.Literal(keyword)), error.expected, text)
        }
    }

    @Test
    fun `a sign before a word near true or false is a string missing its quotes`() {
        for (word in listOf("+true", "-rue", "+fals")) {
            assertEquals(syntax(ParseErrorReason.UnquotedString), errorOf("a = $word\n").reason, word)
        }
    }

    @Test
    fun `a keyword followed by a control character points at the control character`() {
        val cases = mapOf(
            "a = +inf\u000B\n" to ScalarKind.Float,
            "a = nan\u007F\n" to ScalarKind.Float,
            "a = true\u000B\n" to ScalarKind.Boolean(true),
            "a = FALSE\u0001\n" to ScalarKind.Boolean(false),
        )
        for ((text, kind) in cases) {
            val error = errorOf(text)
            assertEquals(syntax(ParseErrorReason.InvalidScalar(kind)), error.reason, text)
            assertEquals(TextPosition(1, text.indexOfFirst { it.isISOControl() } + 1), error.position, text)
        }
    }

    @Test
    fun `a longer word starting with a keyword is a string missing its quotes`() {
        val words = listOf("info", "infinity", "+infinity", "nana", "nann", "-nans", "truest", "trueish", "falsey")
        for (word in words) {
            assertEquals(syntax(ParseErrorReason.UnquotedString), errorOf("a = $word\n").reason, word)
        }
    }

    @Test
    fun `a word far from every keyword is a string missing its quotes`() {
        val words = listOf("tomato", "foo", "t", "f", "trash", "fail", "index", "-nil", "n", "in_fo", "value")
        for (word in words) {
            assertEquals(syntax(ParseErrorReason.UnquotedString), errorOf("a = $word\n").reason, word)
        }
    }

    @Test
    fun `a replaced letter in a word of three letters is a string missing its quotes`() {
        for (word in listOf("imf", "inn", "nam", "-man", "can", "man", "tan")) {
            assertEquals(syntax(ParseErrorReason.UnquotedString), errorOf("a = $word\n").reason, word)
        }
    }

    @Test
    fun `any other unquoted word is a string missing its quotes`() {
        for (text in listOf("a = [I don't belong]\n", "a = [\"x\", No]\n", "a = index\n", "a = -nil\n", "a = n\n")) {
            assertEquals(syntax(ParseErrorReason.UnquotedString), errorOf(text).reason, text)
        }
    }
}
