package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The errors are data a program can hand on: each encodes with its own fields and a short `type`. */
class ErrorJsonTest {

    private fun json(error: TomlDecodeError): String = Json.encodeToString(TomlDecodeError.serializer(), error)

    private fun json(error: TomlEncodeError): String = Json.encodeToString(TomlEncodeError.serializer(), error)

    @Test
    fun `a missing key encodes as its path`() {
        assertEquals(
            """{"type":"MissingKey","path":"database.url"}""",
            json(Toml.decodeFromString<App>("title = \"t\"\n[database]\n").errorOrFail()),
        )
    }

    @Test
    fun `a type mismatch names both TOML types`() {
        assertEquals(
            """{"type":"TypeMismatch","path":"servers[0].port","expected":"Integer","found":"String"}""",
            json(TomlDecodeError.TypeMismatch("servers[0].port", TomlType.Integer, TomlType.String)),
        )
    }

    @Test
    fun `a type mismatch names the type it expected and the one it found`() {
        assertEquals(
            """{"type":"TypeMismatch","path":"port","expected":"Integer","found":"String"}""",
            json(TomlDecodeError.TypeMismatch("port", TomlType.Integer, TomlType.String)),
        )
    }

    @Test
    fun `an unknown enum value lists the names`() {
        assertEquals(
            """{"type":"UnknownEnumValue","path":"mode","value":"tcp","names":["TCP","udp"],"suggestion":"TCP"}""",
            json(Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"tcp\"\n").errorOrFail()),
        )
    }

    @Test
    fun `a datetime kind mismatch names both kinds`() {
        assertEquals(
            """{"type":"DatetimeKindMismatch","path":"date","expected":"LocalDate","found":"LocalTime"}""",
            json(Toml.decodeFromString<Times>("date = 07:32:00\n").errorOrFail()),
        )
    }

    @Test
    fun `a parse error nests the core error`() {
        assertEquals(
            """{"type":"Parse","error":{"reason":{"type":"DuplicateKey","keyPath":["a"]},"span":{"start":6,"end":7},""" +
                """"position":{"line":2,"column":1},"context":{"start":0,"end":1}}}""",
            json(Toml.decodeFromString<Ports>("a = 1\na = 2\n").errorOrFail()),
        )
    }

    @Test
    fun `an encode error encodes with its type and fields`() {
        assertEquals(
            """{"type":"NonStringMapKey","path":"ports"}""",
            json(Toml.encodeToString(NumberedPorts(mapOf(1 to "a"))).errorOrFail()),
        )
        assertEquals(
            """{"type":"UnsupportedRoot","serialName":"kotlin.Int"}""",
            json(Toml.encodeToString(1).errorOrFail()),
        )
    }
}
