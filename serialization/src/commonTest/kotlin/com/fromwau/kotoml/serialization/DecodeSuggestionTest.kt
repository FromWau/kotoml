package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.assertError
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** The suggestion a decode error carries for an unknown enum value or subclass, or a value of the wrong type. */
class DecodeSuggestionTest {

    private val modes = listOf("TCP", "udp")
    private val shapes = listOf("circle", "point", "square")

    private fun mode(value: String): TomlDecodeError =
        Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"$value\"\n").assertError<TomlDecodeError.UnknownEnumValue>()

    private fun shape(name: String): TomlDecodeError =
        Toml.decodeFromString<Holder>("[shape]\ntype = \"$name\"\n").assertError<TomlDecodeError.UnknownSubtype>()

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

    private fun switches(text: String): TomlDecodeError = Toml.decodeFromString<Switches>(text).assertError<TomlDecodeError.TypeMismatch>()

    private fun numbers(text: String): TomlDecodeError = Toml.decodeFromString<Numbers>(text).assertError<TomlDecodeError.TypeMismatch>()

    // Each text below is one key on the first line.
    private fun mismatch(
        key: String,
        expected: TomlType,
        found: TomlType,
        value: String,
        suggestion: String?,
    ) =
        TomlDecodeError.TypeMismatch(key, expected, found, value, suggestion, TextPosition(1, 1))

    @Test
    fun `a string close to a boolean suggests it`() {
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.String, "\"ture\"", "true"),
            switches("enabled = \"ture\"\n"),
        )
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.String, "\"False\"", "false"),
            switches("enabled = \"False\"\n"),
        )
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.String, "\"fals\"", "false"),
            switches("enabled = \"fals\"\n"),
        )
    }

    @Test
    fun `a boolean written as a string suggests the boolean`() {
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.String, "\"true\"", "true"),
            switches("enabled = \"true\"\n"),
        )
    }

    @Test
    fun `a string close to no boolean suggests nothing`() {
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.String, "\"yes\"", null),
            switches("enabled = \"yes\"\n"),
        )
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.String, "\"nan\"", null),
            switches("enabled = \"nan\"\n"),
        )
    }

    @Test
    fun `a number in a boolean field suggests nothing`() {
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.Integer, "1", null),
            switches("enabled = 1\n"),
        )
        assertEquals(
            mismatch("enabled", TomlType.Boolean, TomlType.Float, "nan", null),
            switches("enabled = nan\n"),
        )
    }

    @Test
    fun `a string close to inf or nan in a float field suggests it`() {
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.String, "\"inf\"", "inf"),
            numbers("double = \"inf\"\n"),
        )
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.String, "\"NaN\"", "nan"),
            numbers("double = \"NaN\"\n"),
        )
        assertEquals(
            mismatch("float", TomlType.Float, TomlType.String, "\"in\"", "inf"),
            numbers("float = \"in\"\n"),
        )
    }

    @Test
    fun `a signed inf or nan keeps its sign in the suggestion`() {
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.String, "\"-inf\"", "-inf"),
            numbers("double = \"-inf\"\n"),
        )
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.String, "\"+NaN\"", "+nan"),
            numbers("double = \"+NaN\"\n"),
        )
    }

    @Test
    fun `a boolean or a string close to no float keyword in a float field suggests nothing`() {
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.Boolean, "true", null),
            numbers("double = true\n"),
        )
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.String, "\"tru\"", null),
            numbers("double = \"tru\"\n"),
        )
        assertEquals(
            mismatch("double", TomlType.Float, TomlType.String, "\"1.5\"", null),
            numbers("double = \"1.5\"\n"),
        )
    }

    @Test
    fun `a boolean close to an enum's name suggests the name`() {
        assertEquals(
            mismatch("answer", TomlType.String, TomlType.Boolean, "true", "True"),
            switches("answer = true\n"),
        )
    }

    @Test
    fun `a value close to no enum name suggests nothing`() {
        assertEquals(
            mismatch("answer", TomlType.String, TomlType.Boolean, "false", null),
            switches("answer = false\n"),
        )
        assertEquals(
            mismatch("answer", TomlType.String, TomlType.Integer, "1", null),
            switches("answer = 1\n"),
        )
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
        assertEquals(
            """{"type":"TypeMismatch","path":"enabled","expected":"Boolean","found":"String","value":"\"ture\"",""" +
                """"suggestion":"true","position":{"line":1,"column":1}}""",
            Json.encodeToString(TomlDecodeError.serializer(), switches("enabled = \"ture\"\n")),
        )
    }
}
