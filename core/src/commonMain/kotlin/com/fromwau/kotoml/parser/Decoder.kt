package com.fromwau.kotoml.parser

import com.fromwau.kotoml.IntegerRadix

/**
 * Where a decoder writes the text it decodes.
 *
 * A decoder may [clear] it, then appends whole decoded pieces with [append] and single characters from escapes
 * with [appendCodePoint].
 */
internal interface DecodedStringBuilder {
    /** Drops everything appended so far. */
    fun clear()

    /** Appends [text]. */
    fun append(text: String)

    /** Appends the character with code point [codePoint]. */
    fun appendCodePoint(codePoint: Int)

    companion object {
        /** A builder that drops everything, for validating text without keeping it. */
        val DISCARD: DecodedStringBuilder = object : DecodedStringBuilder {
            override fun clear() {}

            override fun append(text: String) {}

            override fun appendCodePoint(codePoint: Int) {}
        }
    }
}

/** A [DecodedStringBuilder] that keeps everything appended, read back with [toString]. */
internal class StringOutput : DecodedStringBuilder {
    private val buffer = StringBuilder()

    override fun clear() {
        buffer.clear()
    }

    override fun append(text: String) {
        buffer.append(text)
    }

    override fun appendCodePoint(codePoint: Int) {
        appendCodePoint(buffer, codePoint)
    }

    /** Everything appended since the last [clear]. */
    override fun toString(): String = buffer.toString()
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

internal fun IntegerRadix.isValid(byte: Int): Boolean = when (this) {
    IntegerRadix.Dec -> byte in '0'.code..'9'.code
    IntegerRadix.Hex -> byte in '0'.code..'9'.code || byte in 'a'.code..'f'.code || byte in 'A'.code..'F'.code
    IntegerRadix.Oct -> byte in '0'.code..'7'.code
    IntegerRadix.Bin -> byte in '0'.code..'1'.code
}
