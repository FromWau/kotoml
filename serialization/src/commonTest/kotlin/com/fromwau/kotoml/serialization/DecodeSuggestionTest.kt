package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TextPosition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The suggestion a decode error carries for an unknown enum value or subclass, end to end. */
class DecodeSuggestionTest {

    private val modes = listOf("TCP", "udp")
    private val shapes = listOf("circle", "point", "square")

    private fun mode(value: String): TomlDecodeError =
        Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"$value\"\n").errorOrFail()

    private fun shape(name: String): TomlDecodeError =
        Toml.decodeFromString<Holder>("[shape]\ntype = \"$name\"\n").errorOrFail()

    // Where the `mode` key and the `[shape]` header key stand in the texts above.
    private fun unknownMode(
        value: String,
        suggestion: String?,
    ) =
        TomlDecodeError.UnknownEnumValue("mode", value, modes, suggestion, TextPosition(3, 1))

    private fun unknownShape(
        name: String,
        suggestion: String?,
    ) =
        TomlDecodeError.UnknownSubtype("shape", name, shapes, suggestion, TextPosition(1, 2))

    @Test
    fun `an enum value that differs only in case suggests the name`() {
        assertEquals(unknownMode("tcp", "TCP"), mode("tcp"))
        assertEquals(unknownMode("UDP", "udp"), mode("UDP"))
    }

    @Test
    fun `an enum value that begins exactly one name suggests it`() {
        assertEquals(unknownMode("ud", "udp"), mode("ud"))
    }

    @Test
    fun `an enum value a few edits away suggests the nearest name`() {
        assertEquals(unknownMode("tcpp", "TCP"), mode("tcpp"))
        assertEquals(unknownMode("udpp", "udp"), mode("udpp"))
    }

    @Test
    fun `an enum value close to no name suggests nothing`() {
        assertEquals(unknownMode("websocket", null), mode("websocket"))
    }

    @Test
    fun `a subclass name that differs only in case suggests the name`() {
        assertEquals(unknownShape("Circle", "circle"), shape("Circle"))
    }

    @Test
    fun `a subclass name that begins exactly one name suggests it`() {
        assertEquals(unknownShape("sq", "square"), shape("sq"))
    }

    @Test
    fun `a misspelled subclass name suggests the nearest name`() {
        assertEquals(unknownShape("sqare", "square"), shape("sqare"))
        assertEquals(unknownShape("pont", "point"), shape("pont"))
    }

    @Test
    fun `a subclass name close to no name suggests nothing`() {
        assertEquals(unknownShape("triangle", null), shape("triangle"))
    }

    @Test
    fun `the suggestion travels in the error's JSON`() {
        assertEquals(
            """{"type":"UnknownSubtype","path":"shape","name":"sqare","names":["circle","point","square"],""" +
                """"suggestion":"square","position":{"line":1,"column":2}}""",
            Json.encodeToString(TomlDecodeError.serializer(), shape("sqare")),
        )
        assertEquals(
            """{"type":"UnknownEnumValue","path":"mode","value":"websocket","names":["TCP","udp"],""" +
                """"suggestion":null,"position":{"line":3,"column":1}}""",
            Json.encodeToString(TomlDecodeError.serializer(), mode("websocket")),
        )
    }
}
