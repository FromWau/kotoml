package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import com.fromwau.kotoml.writer.randomPrintableString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `#[test]` functions inside `toml_edit/src`. */
class EditUnitTest {

    @Test
    fun `an array built from strings prints with default spacing`() {
        val features = listOf("node", "mouth")
        val value = Value.Array(features.map { Value.String(it) }.toTomlArray().assertSuccess())
        assertEquals("[\"node\", \"mouth\"]", value.toString())
    }

    @Test
    fun `a string value prints as text that parses back`() {
        Value.parse(Value.String("hello").toString()).assertSuccess()
    }

    @Test
    fun `a key prints as text that parses back`() {
        Key.parse(Key("hello").toString()).assertSuccess()
    }

    @Test
    fun `an item prints as text that parses back`() {
        val _ = Item(Value.parse(Item(Value.String("hello")).toString()).assertSuccess())
    }

    @Test
    fun `an empty document prints as text that parses back`() {
        TomlDocument.parse(TomlDocument().toString()).assertSuccess()
    }

    @Test
    fun `random printable strings print as values that parse back`() {
        val random = Random(0x5354_5249)
        repeat(512) {
            val string = randomPrintableString(random)
            val value = Value.String(string)
            val encoded = value.toString()
            Value.parse(encoded).assertSuccess()
        }
    }

    @Test
    fun `random printable strings print as keys that parse back`() {
        val random = Random(0x4B45_5953)
        repeat(512) {
            val string = randomPrintableString(random)
            val key = Key(string)
            val encoded = key.toString()
            Key.parse(encoded).assertSuccess()
        }
    }

    @Test
    fun `an offset in empty input is line 0 column 0`() {
        assertEquals(0 to 0, translatePosition(byteArrayOf(), 0))
    }

    @Test
    fun `the first offset is line 0 column 0`() {
        assertEquals(0 to 0, translatePosition("Hello".encodeToByteArray(), 0))
    }

    @Test
    fun `the last byte's offset is its column on the first line`() {
        val input = "Hello".encodeToByteArray()
        assertEquals(0 to input.size - 1, translatePosition(input, input.size - 1))
    }

    @Test
    fun `the offset past the end is the column after the last byte`() {
        val input = "Hello".encodeToByteArray()
        assertEquals(0 to input.size, translatePosition(input, input.size))
    }

    @Test
    fun `an offset in the first line is its column`() {
        assertEquals(0 to 2, translatePosition("Hello\nWorld\n".encodeToByteArray(), 2))
    }

    @Test
    fun `the offset of a newline is the column at the end of its line`() {
        assertEquals(0 to 5, translatePosition("Hello\nWorld\n".encodeToByteArray(), 5))
    }

    @Test
    fun `the offset after a newline is column 0 of the next line`() {
        assertEquals(1 to 0, translatePosition("Hello\nWorld\n".encodeToByteArray(), 6))
    }

    @Test
    fun `an offset in the second line counts columns from its start`() {
        assertEquals(1 to 2, translatePosition("Hello\nWorld\n".encodeToByteArray(), 8))
    }
}
