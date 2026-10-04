package com.fromwau.kotoml.writer

/**
 * Chooses how a string value is written: as a basic, literal, multi-line basic or multi-line literal string.
 *
 * Each `as` function returns that encoding, or null when the string cannot be written in it without
 * escapes the encoding does not have. [asDefault] picks the most readable one that fits.
 *
 * ```kotlin
 * TomlStringBuilder("Hello\nworld!\n").asDefault().toTomlValue() // "\"\"\"\nHello\nworld!\n\"\"\""
 * ```
 */
internal class TomlStringBuilder(private val decoded: String) {
    private val metrics = ValueMetrics.calculate(decoded)

    /**
     * The first that fits of: basic without escapes, literal, multi-line basic without escapes,
     * multi-line literal; otherwise escaped basic, or escaped multi-line basic when the string has a newline.
     */
    fun asDefault(): TomlString = asBasicPretty()
        ?: asLiteral()
        ?: asMlBasicPretty()
        ?: asMlLiteral()
        ?: if (metrics.newline) asMlBasic() else asBasic()

    /** `'literal'`, unless the string has control characters, a `'` or a newline. */
    fun asLiteral(): TomlString? =
        if (metrics.escapeCodes || 0 < metrics.maxSeqSingleQuotes || metrics.newline) {
            null
        } else {
            TomlString(decoded, StringEncoding.LiteralString, metrics.newline)
        }

    /** `'''multi-line literal'''`, unless the string has control characters or three `'` in a row. */
    fun asMlLiteral(): TomlString? = if (metrics.escapeCodes || 2 < metrics.maxSeqSingleQuotes) {
        null
    } else {
        TomlString(decoded, StringEncoding.MultilineLiteralString, metrics.newline)
    }

    /** `"basic"` needing no escapes, unless the string has control characters, `\`, `"` or a newline. */
    fun asBasicPretty(): TomlString? =
        if (metrics.escapeCodes || metrics.escape || 0 < metrics.maxSeqDoubleQuotes || metrics.newline) {
            null
        } else {
            asBasic()
        }

    /** `"""multi-line basic"""` needing no escapes, unless it has control characters, `\` or `"""`. */
    fun asMlBasicPretty(): TomlString? =
        if (metrics.escapeCodes || metrics.escape || 2 < metrics.maxSeqDoubleQuotes) null else asMlBasic()

    /** `"basic"`, escaping whatever needs it. Always possible. */
    fun asBasic(): TomlString = TomlString(decoded, StringEncoding.BasicString, metrics.newline)

    /** `"""multi-line basic"""`, escaping whatever needs it. Always possible. */
    fun asMlBasic(): TomlString = TomlString(decoded, StringEncoding.MultilineBasicString, metrics.newline)

    override fun toString(): String = "TomlStringBuilder(decoded=$decoded)"
}

/** A string value with its encoding chosen by [TomlStringBuilder]. Write it with [StringBuilder.value]. */
internal class TomlString internal constructor(
    private val decoded: String,
    private val encoding: StringEncoding,
    private val newline: Boolean,
) : WriteTomlValue {
    override fun writeTomlValue(writer: StringBuilder) {
        writeEncoded(decoded, encoding, newline, writer)
    }

    override fun equals(other: Any?): Boolean =
        other is TomlString && decoded == other.decoded && encoding == other.encoding && newline == other.newline

    override fun hashCode(): Int = 31 * (31 * decoded.hashCode() + encoding.hashCode()) + newline.hashCode()

    override fun toString(): String = "TomlString(decoded=$decoded, encoding=$encoding, newline=$newline)"
}

/**
 * Chooses how a key is written: bare, as a basic string or as a literal string.
 *
 * Each `as` function returns that encoding, or null when the key cannot be written in it; [asDefault] picks
 * the most readable one that fits.
 */
internal class TomlKeyBuilder(private val decoded: String) {
    private val metrics = KeyMetrics.calculate(decoded)

    /** The first that fits of: bare, basic without escapes, literal; otherwise escaped basic. */
    fun asDefault(): TomlKey = asUnquoted() ?: asBasicPretty() ?: asLiteral() ?: asBasic()

    /** A bare key, when it is non-empty and holds only `A-Z a-z 0-9 - _`. */
    fun asUnquoted(): TomlKey? = if (metrics.unquoted) TomlKey(decoded, null) else null

    /** `'literal'`, unless the key has control characters or a `'`. */
    fun asLiteral(): TomlKey? = if (metrics.escapeCodes || metrics.singleQuotes) {
        null
    } else {
        TomlKey(decoded, StringEncoding.LiteralString)
    }

    /** `"basic"` needing no escapes, unless the key has control characters, `\` or `"`. */
    fun asBasicPretty(): TomlKey? =
        if (metrics.escapeCodes || metrics.escape || metrics.doubleQuotes) null else asBasic()

    /** `"basic"`, escaping whatever needs it. Always possible. */
    fun asBasic(): TomlKey = TomlKey(decoded, StringEncoding.BasicString)

    override fun toString(): String = "TomlKeyBuilder(decoded=$decoded)"
}

/** A key with its encoding chosen by [TomlKeyBuilder]. Write it with [StringBuilder.key]. */
internal class TomlKey internal constructor(
    private val decoded: String,
    private val encoding: StringEncoding?,
) : WriteTomlKey {
    override fun writeTomlKey(writer: StringBuilder) {
        writeEncoded(decoded, encoding, newline = false, writer)
    }

    override fun equals(other: Any?): Boolean =
        other is TomlKey && decoded == other.decoded && encoding == other.encoding

    override fun hashCode(): Int = 31 * decoded.hashCode() + encoding.hashCode()

    override fun toString(): String = "TomlKey(decoded=$decoded, encoding=$encoding)"
}

/** The four string forms a [TomlString] or [TomlKey] can be written in. */
internal enum class StringEncoding {
    /** `'literal'` */
    LiteralString,

    /** `"basic"` */
    BasicString,

    /** `'''multi-line literal'''` */
    MultilineLiteralString,

    /** `"""multi-line basic"""` */
    MultilineBasicString,
}

private fun writeEncoded(
    decoded: String,
    encoding: StringEncoding?,
    newline: Boolean,
    writer: StringBuilder,
) {
    val delimiter = when (encoding) {
        StringEncoding.LiteralString -> "'"
        StringEncoding.BasicString -> "\""
        StringEncoding.MultilineLiteralString -> "'''"
        StringEncoding.MultilineBasicString -> "\"\"\""
        null -> ""
    }
    val escaped = encoding == StringEncoding.BasicString || encoding == StringEncoding.MultilineBasicString
    val isMl = encoding == StringEncoding.MultilineLiteralString || encoding == StringEncoding.MultilineBasicString

    writer.append(delimiter)
    if (newline && isMl) writer.newline()
    if (escaped) {
        // basic-unescaped = wschar / %x21 / %x23-5B / %x5D-7E / non-ascii
        val maxSeqDoubleQuotes = if (isMl) 2 else 0
        var position = 0
        while (position < decoded.length) {
            var unescapedEnd = position
            var escape: String? = null
            var seqDoubleQuotes = 0
            for (index in position until decoded.length) {
                val char = decoded[index]
                if (char == '"') {
                    seqDoubleQuotes += 1
                    if (maxSeqDoubleQuotes < seqDoubleQuotes) {
                        escape = "\\\""
                        break
                    }
                } else {
                    seqDoubleQuotes = 0
                }

                when {
                    char == '\b' -> escape = "\\b"
                    char == '\t' -> escape = "\\t"
                    char == '\n' && !isMl -> escape = "\\n"
                    char == '\u000C' -> escape = "\\f"
                    char == '\r' -> escape = "\\r"
                    char == '\\' -> escape = "\\\\"
                    char != '\n' && (char.code <= 0x1F || char.code == 0x7F) -> break
                }
                if (escape != null) break

                unescapedEnd = index + 1
            }
            writer.append(decoded.substring(position, unescapedEnd))
            if (escape != null) writer.append(escape)
            position = unescapedEnd + if (escape != null) 1 else 0
            if (escape == null && position < decoded.length) {
                val code = decoded[position].code
                writer.append("\\u" + code.toString(16).uppercase().padStart(4, '0'))
                position += 1
            }
        }
    } else {
        writer.append(decoded)
    }
    writer.append(delimiter)
}

private class ValueMetrics(
    val maxSeqSingleQuotes: Int,
    val maxSeqDoubleQuotes: Int,
    val escapeCodes: Boolean,
    val escape: Boolean,
    val newline: Boolean,
) {
    companion object {
        fun calculate(text: String): ValueMetrics {
            var maxSingle = 0
            var maxDouble = 0
            var escapeCodes = false
            var escape = false
            var newline = false
            var single = 0
            var double = 0
            for (char in text) {
                // Upstream counts in a saturating u8, so a run longer than 255 still reads as 255.
                single = if (char == '\'') minOf(single + 1, 255) else 0
                maxSingle = maxOf(maxSingle, single)
                double = if (char == '"') minOf(double + 1, 255) else 0
                maxDouble = maxOf(maxDouble, double)
                when {
                    char == '\\' -> escape = true
                    char == '\t' -> {}
                    char == '\n' -> newline = true
                    char.code <= 0x1F || char.code == 0x7F -> escapeCodes = true
                }
            }
            return ValueMetrics(maxSingle, maxDouble, escapeCodes, escape, newline)
        }
    }
}

private class KeyMetrics(
    val unquoted: Boolean,
    val singleQuotes: Boolean,
    val doubleQuotes: Boolean,
    val escapeCodes: Boolean,
    val escape: Boolean,
) {
    companion object {
        fun calculate(text: String): KeyMetrics {
            var unquoted = text.isNotEmpty()
            var singleQuotes = false
            var doubleQuotes = false
            var escapeCodes = false
            var escape = false
            for (char in text) {
                val isBare = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_'
                if (!isBare) unquoted = false
                when {
                    char == '\'' -> singleQuotes = true
                    char == '"' -> doubleQuotes = true
                    char == '\\' -> escape = true
                    char == '\t' -> {}
                    char.code <= 0x1F || char.code == 0x7F -> escapeCodes = true
                }
            }
            return KeyMetrics(unquoted, singleQuotes, doubleQuotes, escapeCodes, escape)
        }
    }
}
