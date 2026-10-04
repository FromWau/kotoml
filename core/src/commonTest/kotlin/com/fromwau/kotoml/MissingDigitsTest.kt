package com.fromwau.kotoml

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** A radix prefix with no digits after it is its own error, not an overflow. */
class MissingDigitsTest {

    private fun reason(text: String): TomlParseError.Reason = TomlDocument.parse(text).errorOrFail().reason

    @Test
    fun `each radix prefix with no digits reports missing digits in that radix`() {
        assertEquals(TomlParseError.Reason.MissingDigits(IntegerRadix.Hex), reason("a = 0x\n"))
        assertEquals(TomlParseError.Reason.MissingDigits(IntegerRadix.Oct), reason("a = 0o\n"))
        assertEquals(TomlParseError.Reason.MissingDigits(IntegerRadix.Bin), reason("a = 0b\n"))
    }

    @Test
    fun `an integer too large still reports an overflow`() {
        assertEquals(TomlParseError.Reason.IntegerOverflow, reason("a = 0x1_0000_0000_0000_0000\n"))
    }

    @Test
    fun `missing digits encode with the radix`() {
        assertEquals(
            """{"type":"MissingDigits","radix":"Hex"}""",
            Json.encodeToString(TomlParseError.Reason.serializer(), reason("a = 0x\n")),
        )
    }
}
