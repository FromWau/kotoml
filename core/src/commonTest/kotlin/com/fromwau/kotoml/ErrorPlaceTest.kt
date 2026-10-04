package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** Errors upstream reports without a place point at one here. */
class ErrorPlaceTest {

    @Test
    fun `a dotted key past the nesting limit points at the key`() {
        val input = "x = 1\n" + "a.".repeat(TomlDocument.NESTING_LIMIT) + "b = 1\n"
        val error = TomlDocument.parse(input).errorOrFail()
        assertEquals(TomlParseError.Reason.KeyTooDeep, error.reason)
        assertEquals(TextPosition(2, 1), error.position)
    }

    @Test
    fun `bytes that are not UTF-8 point at the first bad byte`() {
        val bytes = "a = 1\nb = \"".encodeToByteArray() + byteArrayOf(0xFF.toByte()) + "\"\n".encodeToByteArray()
        val error = TomlDocument.parse(bytes).errorOrFail()
        assertEquals(TomlParseError.Reason.InvalidUtf8(11), error.reason)
        assertEquals(TextPosition(2, 6), error.position)
    }
}
