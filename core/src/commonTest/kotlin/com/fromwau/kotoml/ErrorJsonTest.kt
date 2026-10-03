package com.fromwau.kotoml

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every error type is data a program can hand on: it encodes with its own fields and a short `type`. */
class ErrorJsonTest {

    private fun json(text: String): String =
        Json.encodeToString(TomlParseError.serializer(), TomlDocument.parse(text).unwrapErr())

    @Test
    fun aDuplicateKeyNamesTheKeyAndBothPlaces() {
        assertEquals(
            """{"reason":{"type":"DuplicateKey","key":"a"},"span":{"start":6,"end":7},""" +
                """"position":{"line":2,"column":1},"context":{"start":0,"end":1}}""",
            json("a = 1\na = 2\n"),
        )
    }

    @Test
    fun aSyntaxErrorNestsTheParserReasonAndWhatWasExpected() {
        assertEquals(
            """{"reason":{"type":"Syntax","reason":{"type":"InvalidScalar",""" +
                """"kind":{"type":"Integer","radix":"Hex"}}},""" +
                """"span":{"start":6,"end":6},"position":{"line":1,"column":7}}""",
            json("a = 0xg\n"),
        )
    }

    @Test
    fun anInvalidDatetimeCarriesItsPartAndExpectation() {
        assertEquals(
            """{"reason":{"type":"InvalidDatetime","error":{"part":"Date","expected":""" +
                """{"type":"Range","field":"Month","min":1,"max":12}}},"span":{"start":4,"end":14},""" +
                """"position":{"line":1,"column":5}}""",
            json("d = 2021-13-01\n"),
        )
    }

    @Test
    fun anEditErrorEncodesToo() {
        assertEquals(
            """{"type":"NotATable","key":"k","itemType":"String"}""",
            Json.encodeToString(TomlEditError.serializer(), TomlEditError.NotATable("k", TomlType.String)),
        )
    }
}
