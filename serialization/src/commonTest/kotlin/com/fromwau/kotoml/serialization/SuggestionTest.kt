package com.fromwau.kotoml.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The closest name offered for an unknown enum value or subclass. */
class SuggestionTest {

    private val modes = listOf("TCP", "udp", "unix")

    @Test
    fun `a name that differs only in case is suggested`() {
        assertEquals("TCP", closestName("tcp", modes))
    }

    @Test
    fun `a name one edit away is suggested`() {
        assertEquals("udp", closestName("udpp", modes))
        assertEquals("unix", closestName("unx", modes))
    }

    @Test
    fun `a prefix of exactly one name is suggested at any distance`() {
        assertEquals("unix", closestName("un", modes))
        assertEquals("square", closestName("SQ", listOf("circle", "point", "square")))
    }

    @Test
    fun `two near names two edits away are within reach`() {
        assertEquals("TCP", closestName("sctp", modes))
    }

    @Test
    fun `a name the written word shares nothing with is not suggested`() {
        assertNull(closestName("ab", listOf("xy")))
    }

    @Test
    fun `the written word itself is never suggested`() {
        assertNull(closestName("udp", listOf("udp")))
    }

    @Test
    fun `a longer name allows an edit for every three characters`() {
        assertEquals("square", closestName("sqare", listOf("circle", "point", "square")))
        assertEquals("circle", closestName("circel", listOf("circle", "point", "square")))
    }

    @Test
    fun `no name is suggested when none is close`() {
        assertNull(closestName("triangle", listOf("circle", "point", "square")))
        assertNull(closestName("x", emptyList()))
    }

    @Test
    fun `an unknown enum value carries the suggestion`() {
        assertEquals(
            TomlDecodeError.UnknownEnumValue("mode", "Tcp", listOf("TCP", "udp"), "TCP"),
            Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"Tcp\"\n").unwrapErr(),
        )
    }
}
