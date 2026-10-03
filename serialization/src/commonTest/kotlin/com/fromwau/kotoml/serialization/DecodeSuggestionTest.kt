package com.fromwau.kotoml.serialization

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The suggestion a decode error carries for an unknown enum value or subclass, end to end. */
class DecodeSuggestionTest {

    private val modes = listOf("TCP", "udp")
    private val shapes = listOf("circle", "point", "square")

    private fun mode(value: String): TomlDecodeError =
        Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"$value\"\n").unwrapErr()

    private fun shape(name: String): TomlDecodeError =
        Toml.decodeFromString<Holder>("[shape]\ntype = \"$name\"\n").unwrapErr()

    @Test
    fun `an enum value that differs only in case suggests the name`() {
        assertEquals(TomlDecodeError.UnknownEnumValue("mode", "tcp", modes, "TCP"), mode("tcp"))
        assertEquals(TomlDecodeError.UnknownEnumValue("mode", "UDP", modes, "udp"), mode("UDP"))
    }

    @Test
    fun `an enum value that begins exactly one name suggests it`() {
        assertEquals(TomlDecodeError.UnknownEnumValue("mode", "u", modes, "udp"), mode("u"))
    }

    @Test
    fun `an enum value a few edits away suggests the nearest name`() {
        assertEquals(TomlDecodeError.UnknownEnumValue("mode", "sctp", modes, "TCP"), mode("sctp"))
        assertEquals(TomlDecodeError.UnknownEnumValue("mode", "udpp", modes, "udp"), mode("udpp"))
    }

    @Test
    fun `an enum value close to no name suggests nothing`() {
        assertEquals(TomlDecodeError.UnknownEnumValue("mode", "websocket", modes, null), mode("websocket"))
    }

    @Test
    fun `a subclass name that differs only in case suggests the name`() {
        assertEquals(TomlDecodeError.UnknownSubtype("shape", "Circle", shapes, "circle"), shape("Circle"))
    }

    @Test
    fun `a subclass name that begins exactly one name suggests it`() {
        assertEquals(TomlDecodeError.UnknownSubtype("shape", "sq", shapes, "square"), shape("sq"))
    }

    @Test
    fun `a misspelled subclass name suggests the nearest name`() {
        assertEquals(TomlDecodeError.UnknownSubtype("shape", "sqare", shapes, "square"), shape("sqare"))
        assertEquals(TomlDecodeError.UnknownSubtype("shape", "pont", shapes, "point"), shape("pont"))
    }

    @Test
    fun `a subclass name close to no name suggests nothing`() {
        assertEquals(TomlDecodeError.UnknownSubtype("shape", "triangle", shapes, null), shape("triangle"))
    }

    @Test
    fun `the suggestion travels in the error's JSON`() {
        assertEquals(
            """{"type":"UnknownSubtype","path":"shape","name":"sqare","names":["circle","point","square"],""" +
                """"suggestion":"square"}""",
            Json.encodeToString(TomlDecodeError.serializer(), shape("sqare")),
        )
        assertEquals(
            """{"type":"UnknownEnumValue","path":"mode","value":"websocket","names":["TCP","udp"],"suggestion":null}""",
            Json.encodeToString(TomlDecodeError.serializer(), mode("websocket")),
        )
    }
}
