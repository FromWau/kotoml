package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** The text a value prints as, and what a value's text reads as. */
class ValueTextTest {

    private fun printed(value: Value): String {
        val doc = TomlDocument()
        doc.set("a", value).getOrFail()
        return doc.toString().removePrefix("a = ").removeSuffix("\n")
    }

    @Test
    fun `a float far from one prints in exponent form`() {
        assertEquals("1e300", printed(Value.Float(1e300)))
        assertEquals("1e21", printed(Value.Float(1e21)))
        assertEquals("1e16", printed(Value.Float(1e16)))
        assertEquals("5e-324", printed(Value.Float(Double.MIN_VALUE)))
        assertEquals("1.7976931348623157e308", printed(Value.Float(Double.MAX_VALUE)))
    }

    @Test
    fun `a negative float far from one prints in exponent form`() {
        assertEquals("-1.5e-7", printed(Value.Float(-1.5e-7)))
        assertEquals("-2.5e20", printed(Value.Float(-2.5e20)))
    }

    @Test
    fun `a float near one prints in plain form`() {
        assertEquals("12345.5", printed(Value.Float(12345.5)))
        assertEquals("0.0001", printed(Value.Float(0.0001)))
    }

    @Test
    fun `a value's text leaves out the spacing and comment around it`() {
        val doc = TomlDocument.parse("a =   1   # one\nb = [\n    \"macros\",\n]\n").getOrFail()
        assertEquals("1", doc["a"].getOrFail().valueOrNull.toString())
        assertEquals("\"macros\"", doc["b"][0].getOrFail().valueOrNull.toString())
    }

    @Test
    fun `parsing a value ignores the whitespace around it`() {
        assertEquals(1L, Value.parse("  1  ").getOrFail().longOrNull)
        assertEquals("x", Value.parse("\"x\"\n").getOrFail().stringOrNull)
    }

    @Test
    fun `a time stored in a TOML 1_0 document prints its seconds`() {
        val doc = TomlDocument.parse("a = 1\n", TomlVersion.V1_0).getOrFail()
        doc.set("t", Value.Datetime(Datetime.LocalTime(Time.of(7, 32, null, null).getOrFail()))).getOrFail()
        assertEquals("a = 1\nt = 07:32:00\n", doc.toString())
    }

    @Test
    fun `a trailing comma in a TOML 1_0 document is refused`() {
        val doc = TomlDocument.parse("t = { x = 1 }\n", TomlVersion.V1_0).getOrFail()
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.setTrailingComma(true).errorOrFail()
        assertEquals("t = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a handle to a replaced table keeps the old table and no longer reaches the document`() {
        val doc = TomlDocument.parse("[a]\nx = 1\n").getOrFail()
        val old = doc["a"].getOrFail()
        doc.set("a", standardTableOf("y" to Item(Value.Integer(0L))).getOrFail()).getOrFail()
        assertNotNull(old.standardTableOrNull!!.getOrNull("x"))
        old.set("z", Item(Value.Integer(3L))).getOrFail()
        assertEquals("[a]\ny = 0\n", doc.toString())
    }

    @Test
    fun `a value that does not parse is an error`() {
        assertIs<TomlParseError.Reason.Syntax>(Value.parse("1 2").errorOrFail().reason)
    }
}
