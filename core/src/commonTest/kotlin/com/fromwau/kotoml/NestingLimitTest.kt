package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Brackets and the dots of keys count toward one [TomlDocument.NESTING_LIMIT], as both deepen the tree. */
class NestingLimitTest {

    private val limit = TomlDocument.NESTING_LIMIT

    /** [depth] inline tables, the innermost holding a key with [dots] dots. */
    private fun keyInside(depth: Int, dots: Int): String =
        "x = " + "{ k = ".repeat(depth - 1) + "{ " + "a.".repeat(dots) + "b = 1" + " }".repeat(depth) + "\n"

    private fun inlineTables(depth: Int, dots: Int): String {
        val open = "{ " + "k.".repeat(dots) + "k = "
        return "x = " + open.repeat(depth) + "1" + " }".repeat(depth) + "\n"
    }

    @Test
    fun `dotted keys inside nested inline tables are refused once their levels add up past the limit`() {
        val error = TomlDocument.parse(inlineTables(depth = 78, dots = 77)).assertError<TomlParseError>()
        assertEquals(TomlParseError.Reason.KeyTooDeep, error.reason)
    }

    @Test
    fun `a dotted key inside inline tables may reach one level below the limit`() {
        val depth = 40
        val text = keyInside(depth, dots = limit - depth - 1)
        assertEquals(text, TomlDocument.parse(text).assertSuccess().toString())
    }

    @Test
    fun `a dotted key that brings inline tables to the limit is refused at the key`() {
        val depth = 40
        val error = TomlDocument.parse(keyInside(depth, dots = limit - depth)).assertError<TomlParseError>()
        assertEquals(TomlParseError.Reason.KeyTooDeep, error.reason)
        assertEquals(TextPosition(1, "x = ".length + "{ k = ".length * (depth - 1) + "{ ".length + 1), error.position)
    }

    @Test
    fun `arrays under a dotted key may nest to the limit counted with its dots`() {
        val dots = 70
        val text = "a.".repeat(dots) + "b = " + "[".repeat(limit - dots) + "1" + "]".repeat(limit - dots) + "\n"
        assertEquals(text, TomlDocument.parse(text).assertSuccess().toString())
    }

    @Test
    fun `an array past the limit counted with the dots above it is refused at its bracket`() {
        val dots = 70
        val brackets = limit - dots + 1
        val text = "a.".repeat(dots) + "b = " + "[".repeat(brackets) + "1" + "]".repeat(brackets) + "\n"
        val error = TomlDocument.parse(text).assertError<TomlParseError>()
        assertEquals(TomlParseError.Reason.Syntax(ParseErrorReason.RecursionLimit), error.reason)
        assertEquals(TextPosition(1, "a.".length * dots + "b = ".length + brackets), error.position)
    }
}
