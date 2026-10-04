package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The value constructors, properties and decor. */
class ValueTest {

    @Test
    fun `a null decor side prints the default and an empty one prints nothing`() {
        val table = StandardTable()
        table["a"] = 1L
        table["b"] = Value.Integer(2L, Decor(prefix = "", suffix = ""))
        assertEquals("a = 1\nb =2\n", table.toString())
    }

    @Test
    fun `a decor read back keeps null apart from empty`() {
        val value = Value.String("x", Decor(prefix = ""))
        assertEquals(Decor(prefix = "", suffix = null), value.decor)
    }

    @Test
    fun `wrapping an array keeps its decor unless one is given`() {
        val array = TomlArray()
        array.decor = Decor(prefix = "  ")
        assertEquals(Decor(prefix = "  "), Value.Array(array).decor)
        assertEquals(Decor(suffix = " "), Value.Array(array, Decor(suffix = " ")).decor)
        assertEquals(Decor(suffix = " "), array.decor)
    }

    @Test
    fun `a parsed scalar keeps its spelling until its formatting is reset`() {
        val value = Value.parse("'literal'").getOrFail() as Value.String
        assertEquals("literal", value.value)
        assertEquals("'literal'", value.spelling)
        value.resetFormatting()
        assertNull(value.spelling)
        assertEquals("\"literal\"", value.toString())
    }

    @Test
    fun `a copy keeps spelling and decor and changes apart from the original`() {
        val original = Value.parse("0x10").getOrFail()
        original.decor = Decor(" ", " ")
        val copy = original.copy()
        original.decor = Decor()
        assertEquals(" 0x10 ", copy.toString())
    }

    @Test
    fun `each OrNull property reads only its own kind`() {
        val integer = Value.Integer(1L)
        assertEquals(1L, integer.longOrNull)
        assertNull(integer.stringOrNull)
        assertNull(integer.doubleOrNull)
        assertNull(integer.booleanOrNull)
        assertNull(integer.datetimeOrNull)
        assertNull(integer.arrayOrNull)
        assertNull(integer.inlineTableOrNull)
        assertEquals(true, Item(Value.Boolean(true)).booleanOrNull)
        assertEquals(1.5, Item(Value.Float(1.5)).doubleOrNull)
    }

    @Test
    fun `a date-time made from a time alone is a local time`() {
        val time = Datetime(time = Time(hour = 7, minute = 32, second = 0, nanosecond = null))
        assertEquals("07:32:00", Value.Datetime(time).toString())
    }

    @Test
    fun `a key decor set through its property prints`() {
        val key = Key("a")
        key.leafDecor = Decor(prefix = "", suffix = "  ")
        val table = StandardTable()
        table.put(key, Item(Value.Integer(1L)))
        assertEquals("a  = 1\n", table.toString())
    }
}
