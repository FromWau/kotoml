package com.fromwau.kotoml

import com.fromwau.kotoml.writer.randomPrintableString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `#[test]` functions inside `toml_edit/src`. */
class EditUnitTest {

    @Test
    fun valueFromIterFormatting() {
        val features = listOf("node", "mouth")
        val value = Value.fromIter(features.map { Value.from(it) })
        assertEquals("[\"node\", \"mouth\"]", value.toString())
    }

    @Test
    fun valueStringRoundtrip() {
        Value.parse(Value.from("hello").toString()).unwrap()
    }

    @Test
    fun keyStringRoundtrip() {
        Key.parseSimple(Key("hello").toString()).unwrap()
    }

    @Test
    fun itemStringRoundtrip() {
        Item.parse(value("hello").toString()).unwrap()
    }

    @Test
    fun documentDefaultRoundtrip() {
        DocumentMut.parse(DocumentMut().toString()).unwrap()
    }

    @Test
    fun encodeParseableString() {
        val random = Random(0x5354_5249)
        repeat(512) {
            val string = randomPrintableString(random)
            val value = Value.from(string)
            val encoded = value.toString()
            Value.parse(encoded).unwrap()
        }
    }

    @Test
    fun encodeParseableKey() {
        val random = Random(0x4B45_5953)
        repeat(512) {
            val string = randomPrintableString(random)
            val key = Key(string)
            val encoded = key.toString()
            Key.parseSimple(encoded).unwrap()
        }
    }

    @Test
    fun translatePositionEmpty() {
        assertEquals(0 to 0, translatePosition(byteArrayOf(), 0))
    }

    @Test
    fun translatePositionStart() {
        assertEquals(0 to 0, translatePosition("Hello".encodeToByteArray(), 0))
    }

    @Test
    fun translatePositionEnd() {
        val input = "Hello".encodeToByteArray()
        assertEquals(0 to input.size - 1, translatePosition(input, input.size - 1))
    }

    @Test
    fun translatePositionAfter() {
        val input = "Hello".encodeToByteArray()
        assertEquals(0 to input.size, translatePosition(input, input.size))
    }

    @Test
    fun translatePositionFirstLine() {
        assertEquals(0 to 2, translatePosition("Hello\nWorld\n".encodeToByteArray(), 2))
    }

    @Test
    fun translatePositionEndOfLine() {
        assertEquals(0 to 5, translatePosition("Hello\nWorld\n".encodeToByteArray(), 5))
    }

    @Test
    fun translatePositionStartOfSecondLine() {
        assertEquals(1 to 0, translatePosition("Hello\nWorld\n".encodeToByteArray(), 6))
    }

    @Test
    fun translatePositionSecondLine() {
        assertEquals(1 to 2, translatePosition("Hello\nWorld\n".encodeToByteArray(), 8))
    }
}
