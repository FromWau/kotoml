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
    fun aMissingKeyIsItsPath() {
        assertEquals(
            """{"type":"MissingKey","path":"database.url"}""",
            json(Toml.decodeFromString<App>("title = \"t\"\n[database]\n").unwrapErr()),
        )
    }

    @Test
    fun aTypeMismatchNamesBothTomlTypes() {
        assertEquals(
            """{"type":"TypeMismatch","path":"servers[0].port","expected":"Integer","found":"String"}""",
            json(TomlDecodeError.TypeMismatch("servers[0].port", TomlType.Integer, TomlType.String)),
        )
    }

    @Test
    fun anUnknownEnumValueListsTheNames() {
        assertEquals(
            """{"type":"UnknownEnumValue","path":"mode","value":"tcp","names":["TCP","udp"]}""",
            json(Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"tcp\"\n").unwrapErr()),
        )
    }

    @Test
    fun aDatetimeKindMismatchNamesBothKinds() {
        assertEquals(
            """{"type":"DatetimeKindMismatch","path":"date","expected":"LocalDate","found":"LocalTime"}""",
            json(Toml.decodeFromString<Times>("date = 07:32:00\n").unwrapErr()),
        )
    }

    @Test
    fun aParseErrorNestsCoresError() {
        assertEquals(
            """{"type":"Parse","error":{"reason":{"type":"DuplicateKey","key":"a"},"span":{"start":6,"end":7},""" +
                """"position":{"line":2,"column":1},"context":{"start":0,"end":1}}}""",
            json(Toml.decodeFromString<Ports>("a = 1\na = 2\n").unwrapErr()),
        )
    }

    @Test
    fun anEncodeErrorEncodesToo() {
        assertEquals(
            """{"type":"NullElement","path":"values[1]"}""",
            json(Toml.encodeToString(MaybeList(listOf(1, null))).unwrapErr()),
        )
        assertEquals(
            """{"type":"UnsupportedRoot","serialName":"kotlin.Int"}""",
            json(Toml.encodeToString(1).unwrapErr()),
        )
    }
}
