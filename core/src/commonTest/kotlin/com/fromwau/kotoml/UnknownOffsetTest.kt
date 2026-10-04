package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC 3339's `-00:00`, a UTC time whose local offset is unknown, is valid TOML and stays as written. */
class UnknownOffsetTest {

    @Test
    fun `a -00 00 offset parses as unknown and prints back as written`() {
        val datetime = Datetime.parse("1979-05-27T07:32:00-00:00").getOrFail()
        assertEquals(Offset.Unknown, datetime.offset)
        assertEquals("1979-05-27T07:32:00-00:00", datetime.toString())
    }

    @Test
    fun `a +00 00 offset stays a numeric offset of zero`() {
        val datetime = Datetime.parse("1979-05-27T07:32:00+00:00").getOrFail()
        assertEquals(Offset.Custom.of(0).getOrFail(), datetime.offset)
        assertEquals("1979-05-27T07:32:00+00:00", datetime.toString())
    }

    @Test
    fun `an unknown offset survives a rebuilt value`() {
        val value = Value.parse("1979-05-27T07:32:00-00:00").getOrFail()
        assertEquals("1979-05-27T07:32:00-00:00", Value.Datetime((value as Value.Datetime).value).toString())
    }

    @Test
    fun `offsets order Z then unknown then numeric offsets by minutes`() {
        val sorted = listOf(Offset.Custom.of(60).getOrFail(), Offset.Unknown, Offset.Custom.of(-60).getOrFail(), Offset.Z).sorted()
        assertEquals(listOf(Offset.Z, Offset.Unknown, Offset.Custom.of(-60).getOrFail(), Offset.Custom.of(60).getOrFail()), sorted)
        assertTrue(Offset.Unknown > Offset.Z)
    }
}
