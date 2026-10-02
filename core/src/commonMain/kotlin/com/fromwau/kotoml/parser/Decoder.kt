package com.fromwau.kotoml.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a quoted string was written: the four TOML string forms.
 *
 * They are declared in upstream's order, which numbers the single-line forms by their quote byte.
 */
public enum class Encoding {
    /** `'''multi-line literal'''` */
    MlLiteralString,

    /** `"""multi-line basic"""` */
    MlBasicString,

    /** `"basic"` */
    BasicString,

    /** `'literal'` */
    LiteralString,
    ;

    /** The form in words, such as `literal string`, as error messages name it. */
    public fun description(): String = when (this) {
        LiteralString -> TokenKind.LiteralString.description()
        BasicString -> TokenKind.BasicString.description()
        MlLiteralString -> TokenKind.MlLiteralString.description()
        MlBasicString -> TokenKind.MlBasicString.description()
    }
}

/**
 * Where a decoder writes the text it decodes.
 *
 * A decoder may [clear] it, then appends whole decoded pieces with [pushStr] and single characters from
 * escapes with [pushChar]. A push returning false means the builder could not take it, which the decoder
 * reports as `could not allocate for string`.
 */
public interface DecodedStringBuilder {
    /** Drops everything pushed so far. */
    public fun clear()

    /** Appends [append], returning whether it was accepted. */
    public fun pushStr(append: String): Boolean

    /** Appends the character with code point [append], returning whether it was accepted. */
    public fun pushChar(append: Int): Boolean

    public companion object {
        /** A builder that accepts and drops everything, for validating text without keeping it. */
        public val DISCARD: DecodedStringBuilder = object : DecodedStringBuilder {
            override fun clear() {}

            override fun pushStr(append: String): Boolean = true

            override fun pushChar(append: Int): Boolean = true
        }
    }
}

/** A [DecodedStringBuilder] that keeps everything pushed, read back with [toString]. */
public class StringOutput : DecodedStringBuilder {
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
public class BorrowedOutput : DecodedStringBuilder {
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

/**
 * Which kind of scalar an unquoted or quoted value spells, as [Raw.decodeScalar] classifies it.
 *
 * Kinds order as declared, a [Boolean] by its value and an [Integer] by its radix.
 */
@Serializable
public sealed interface ScalarKind : Comparable<ScalarKind> {
    /** A string, quoted or not. */
    @Serializable
    @SerialName("String")
    public data object String : ScalarKind

    /** `true` or `false`, carrying which. */
    @Serializable
    @SerialName("Boolean")
    public data class Boolean(val value: kotlin.Boolean) : ScalarKind

    /** A date, time or date-time. */
    @Serializable
    @SerialName("DateTime")
    public data object DateTime : ScalarKind

    /** A float, including `inf` and `nan`. */
    @Serializable
    @SerialName("Float")
    public data object Float : ScalarKind

    /** An integer written in [radix]. */
    @Serializable
    @SerialName("Integer")
    public data class Integer(val radix: IntegerRadix) : ScalarKind

    /** The kind in words, such as `float` or `hexadecimal`. */
    public fun description(): kotlin.String = when (this) {
        String -> "string"
        is Boolean -> "boolean"
        DateTime -> "date-time"
        Float -> "float"
        is Integer -> radix.description()
    }

    override fun compareTo(other: ScalarKind): Int {
        val byVariant = variantIndex().compareTo(other.variantIndex())
        if (byVariant != 0) return byVariant
        return when {
            this is Boolean && other is Boolean -> value.compareTo(other.value)
            this is Integer && other is Integer -> radix.compareTo(other.radix)
            else -> 0
        }
    }

    private fun variantIndex(): Int = when (this) {
        String -> 0
        is Boolean -> 1
        DateTime -> 2
        Float -> 3
        is Integer -> 4
    }
}

/** The base an integer is written in. */
public enum class IntegerRadix {
    /** Base 10, the default. */
    Dec,

    /** Base 16, `0x`. */
    Hex,

    /** Base 8, `0o`. */
    Oct,

    /** Base 2, `0b`. */
    Bin,
    ;

    /** The base in words, such as `hexadecimal`. */
    public fun description(): String = when (this) {
        Dec -> "integer"
        Hex -> "hexadecimal"
        Oct -> "octal"
        Bin -> "binary"
    }

    /** The base as a number: 10, 16, 8 or 2. */
    public fun value(): Int = when (this) {
        Dec -> 10
        Hex -> 16
        Oct -> 8
        Bin -> 2
    }

    internal fun isValid(byte: Int): kotlin.Boolean = when (this) {
        Dec -> byte in '0'.code..'9'.code
        Hex -> byte in '0'.code..'9'.code || byte in 'a'.code..'f'.code || byte in 'A'.code..'F'.code
        Oct -> byte in '0'.code..'7'.code
        Bin -> byte in '0'.code..'1'.code
    }

    public companion object {
        /** [Dec], upstream's `IntegerRadix::default()`. */
        public val DEFAULT: IntegerRadix = Dec
    }
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
