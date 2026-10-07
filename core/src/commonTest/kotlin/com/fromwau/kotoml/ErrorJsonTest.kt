package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** Every error type is data a program can hand on: it encodes with its own fields and a short `type`. */
class ErrorJsonTest {

    private fun json(text: String): String =
        Json.encodeToString(TomlParseError.serializer(), TomlDocument.parse(text).assertError<TomlParseError>())

    @Test
    fun `a duplicate key names the key and both places`() {
        assertEquals(
            """{"reason":{"type":"DuplicateKey","keyPath":["a"]},"span":{"start":6,"end":7},""" +
                """"position":{"line":2,"column":1},"context":{"start":0,"end":1}}""",
            json("a = 1\na = 2\n"),
        )
    }

    @Test
    fun `a syntax error nests the parser reason and the value it was found in`() {
        assertEquals(
            """{"reason":{"type":"Syntax","reason":{"type":"InvalidScalar",""" +
                """"kind":{"type":"Integer","radix":"Hex"}}},""" +
                """"span":{"start":6,"end":6},"position":{"line":1,"column":7},"context":{"start":4,"end":7}}""",
            json("a = 0xg\n"),
        )
    }

    @Test
    fun `an invalid date-time carries its part and expectation`() {
        assertEquals(
            """{"reason":{"type":"InvalidDatetime","error":{"part":"Date","expected":""" +
                """{"type":"Range","field":"Month","min":1,"max":12},"value":13}},"span":{"start":4,"end":14},""" +
                """"position":{"line":1,"column":5}}""",
            json("d = 2021-13-01\n"),
        )
    }

    @Test
    fun `an edit error encodes with its type and fields`() {
        assertEquals(
            """{"type":"NotATable","key":"k","itemType":"String"}""",
            Json.encodeToString(TomlEditError.serializer(), TomlEditError.NotATable("k", TomlType.String)),
        )
    }

    @Test
    fun `a rename onto a key already there names the key`() {
        assertEquals(
            """{"type":"KeyTaken","key":"b"}""",
            Json.encodeToString(TomlEditError.serializer(), TomlEditError.KeyTaken("b")),
        )
    }

    @Test
    fun `a layout change refused for its comments names the key path and the count`() {
        val error = TomlEditError.CommentsWouldBeLost(listOf("t", "sub"), 2)
        assertEquals(
            """{"type":"CommentsWouldBeLost","keyPath":["t","sub"],"count":2}""",
            Json.encodeToString(TomlEditError.serializer(), error),
        )
    }

    @Test
    fun `an edit refused in a TOML 1_0 document names the key and the TOML 1_1 feature`() {
        val error = TomlEditError.NotInToml10(listOf("t"), Toml11Feature.InlineTableTrailingComma)
        assertEquals(
            """{"type":"NotInToml10","keyPath":["t"],"feature":"InlineTableTrailingComma"}""",
            Json.encodeToString(TomlEditError.serializer(), error),
        )
    }
}
