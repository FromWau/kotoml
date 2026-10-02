package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Encoding
import com.fromwau.kotoml.IntegerRadix
import com.fromwau.kotoml.ScalarKind

/**
 * Where a decoder writes the text it decodes.
 *
 * A decoder may [clear] it, then appends whole decoded pieces with [pushStr] and single characters from
 * escapes with [pushChar]. A push returning false means the builder could not take it, which the decoder
 * reports as `could not allocate for string`.
 */
internal interface DecodedStringBuilder {
    /** Drops everything pushed so far. */
    fun clear()

    /** Appends [append], returning whether it was accepted. */
    fun pushStr(append: String): Boolean

    /** Appends the character with code point [append], returning whether it was accepted. */
    fun pushChar(append: Int): Boolean

    companion object {
        /** A builder that accepts and drops everything, for validating text without keeping it. */
        val DISCARD: DecodedStringBuilder = object : DecodedStringBuilder {
            override fun clear() {}

            override fun pushStr(append: String): Boolean = true

            override fun pushChar(append: Int): Boolean = true
        }
    }
}

/** A [DecodedStringBuilder] that keeps everything pushed, read back with [toString]. */
internal class StringOutput : DecodedStringBuilder {
    private val buffer = StringBuilder()

    override fun clear() {
        buffer.clear()
    }

    override fun pushStr(append: String): Boolean {
        buffer.append(append)
        return true
    }

    override fun pushChar(append: Int): Boolean {
        appendCodePoint(buffer, append)
        return true
    }

    /** Everything pushed since the last [clear]. */
    override fun toString(): String = buffer.toString()
}

/**
 * A [DecodedStringBuilder] that accepts only text that needs no decoding: a single [pushStr] into an empty
 * builder. Every other push is refused. This is upstream's builder over a borrowed `&str`, which can
 * point at the input but cannot own new text.
 */
internal class BorrowedOutput : DecodedStringBuilder {
    private var value = ""

    override fun clear() {
        value = ""
    }

    override fun pushStr(append: String): Boolean {
        if (value.isEmpty()) {
            value = append
            return true
        }
        return false
    }

    override fun pushChar(append: Int): Boolean = false

    /** The text accepted since the last [clear]. */
    override fun toString(): String = value
}

internal fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
    if (codePoint > 0xFFFF) {
        val offset = codePoint - 0x10000
        builder.append((0xD800 + (offset shr 10)).toChar())
        builder.append((0xDC00 + (offset and 0x3FF)).toChar())
    } else {
        builder.append(codePoint.toChar())
    }
}

/** The form in words, such as `literal string`, as error messages name it. */
internal fun Encoding.description(): String = when (this) {
    Encoding.LiteralString -> TokenKind.LiteralString.description()
    Encoding.BasicString -> TokenKind.BasicString.description()
    Encoding.MlLiteralString -> TokenKind.MlLiteralString.description()
    Encoding.MlBasicString -> TokenKind.MlBasicString.description()
}

/** The kind in words, such as `float` or `hexadecimal`. */
internal fun ScalarKind.description(): String = when (this) {
    ScalarKind.String -> "string"
    is ScalarKind.Boolean -> "boolean"
    ScalarKind.DateTime -> "date-time"
    ScalarKind.Float -> "float"
    is ScalarKind.Integer -> radix.description()
}

/** The base in words, such as `hexadecimal`. */
internal fun IntegerRadix.description(): String = when (this) {
    IntegerRadix.Dec -> "integer"
    IntegerRadix.Hex -> "hexadecimal"
    IntegerRadix.Oct -> "octal"
    IntegerRadix.Bin -> "binary"
}

internal fun IntegerRadix.isValid(byte: Int): Boolean = when (this) {
    IntegerRadix.Dec -> byte in '0'.code..'9'.code
    IntegerRadix.Hex -> byte in '0'.code..'9'.code || byte in 'a'.code..'f'.code || byte in 'A'.code..'F'.code
    IntegerRadix.Oct -> byte in '0'.code..'7'.code
    IntegerRadix.Bin -> byte in '0'.code..'1'.code
}
