package com.fromwau.kotoml.parser

/**
 * TOML text, ready to be lexed and sliced.
 *
 * The text is held as UTF-8 bytes, which is what every [Span] counts. A `String` holding an unpaired
 * surrogate, which UTF-8 cannot encode, is read as if it held U+FFFD there.
 */
public class Source(private val input: String) {

    internal val bytes: ByteArray = input.encodeToByteArray()

    /** Starts lexing the text into [Token]s. */
    public fun lex(): Lexer = Lexer(bytes)

    /** The text this source was made from. */
    public fun input(): String = input

    /**
     * The bytes under [span], or null when the span reaches past the input or does not start and end on
     * character boundaries.
     */
    public fun get(span: Span): Raw? = get(span, encoding = null)

    /** The text of [token], carrying the string encoding its kind implies. */
    public fun get(token: Token): Raw? = get(token.span(), token.kind().encoding())

    /** The text of [event], carrying the string encoding it was reported with. */
    public fun get(event: Event): Raw? = get(event.span(), event.encoding())

    private fun get(span: Span, encoding: Encoding?): Raw? {
        val start = span.start()
        val end = span.end()
        if (start < 0 || start > end || end > bytes.size) return null
        if (!isCharBoundary(bytes, start) || !isCharBoundary(bytes, end)) return null
        return Raw(bytes, start, end, encoding, span)
    }

    override fun equals(other: Any?): Boolean = other is Source && input == other.input

    override fun hashCode(): Int = input.hashCode()

    override fun toString(): String = "Source(input=$input)"
}

/**
 * A slice of a [Source]: the text of one token or event, with the string [Encoding] it was written in
 * when it is a quoted string.
 *
 * Decoding checks the text against the TOML grammar for its kind and reports every problem to the
 * [ErrorSink] passed in, with spans rebased onto the source the slice came from.
 */
public class Raw internal constructor(
    internal val bytes: ByteArray,
    internal val from: Int,
    internal val to: Int,
    private val encoding: Encoding?,
    private val span: Span,
) {

    /** Decodes this text as a key, which may be a bare key or a basic or literal string. */
    public fun decodeKey(output: DecodedStringBuilder, error: ErrorSink) {
        val rebased = rebasing(error)
        when (encoding) {
            Encoding.LiteralString -> decodeLiteralString(this, output, rebased)
            Encoding.BasicString -> decodeBasicString(this, output, rebased)
            Encoding.MlLiteralString -> {
                rebased.reportError(
                    ParseError(
                        ParseErrorReason.MultilineKey(Encoding.MlLiteralString),
                        expected = KEY_STRING_KINDS,
                        unexpected = Span(0, len()),
                    ),
                )
                decodeMlLiteralString(this, output, rebased)
            }
            Encoding.MlBasicString -> {
                rebased.reportError(
                    ParseError(
                        ParseErrorReason.MultilineKey(Encoding.MlBasicString),
                        expected = KEY_STRING_KINDS,
                        unexpected = Span(0, len()),
                    ),
                )
                decodeMlBasicString(this, output, rebased)
            }
            null -> decodeUnquotedKey(this, output, rebased)
        }
    }

    /**
     * Decodes this text as a value and returns which kind of scalar it spells. Numbers are written to
     * [output] without their underscores, ready for a number parser; strings are written decoded.
     */
    public fun decodeScalar(output: DecodedStringBuilder, error: ErrorSink): ScalarKind {
        val rebased = rebasing(error)
        return when (encoding) {
            Encoding.LiteralString -> {
                decodeLiteralString(this, output, rebased)
                ScalarKind.String
            }
            Encoding.BasicString -> {
                decodeBasicString(this, output, rebased)
                ScalarKind.String
            }
            Encoding.MlLiteralString -> {
                decodeMlLiteralString(this, output, rebased)
                ScalarKind.String
            }
            Encoding.MlBasicString -> {
                decodeMlBasicString(this, output, rebased)
                ScalarKind.String
            }
            null -> decodeUnquotedScalar(this, output, rebased)
        }
    }

    /** Checks this text as whitespace, which is always valid. */
    public fun decodeWhitespace(error: ErrorSink) {
    }

    /** Checks this text as a comment, reporting any character a comment may not hold. */
    public fun decodeComment(error: ErrorSink) {
        decodeCommentText(this, rebasing(error))
    }

    /** Checks this text as a newline, reporting a carriage return without its line feed. */
    public fun decodeNewline(error: ErrorSink) {
        decodeNewlineText(this, rebasing(error))
    }

    /** The text of this slice. */
    public fun asStr(): String = bytes.decodeToString(from, to)

    /** The UTF-8 bytes of this slice, as a copy. */
    public fun asBytes(): ByteArray = bytes.copyOfRange(from, to)

    /** The length of this slice in bytes. */
    public fun len(): Int = to - from

    /** Whether this slice is empty. */
    public fun isEmpty(): Boolean = from == to

    internal fun byteAt(offset: Int): Int = bytes[from + offset].toInt() and 0xFF

    internal fun text(start: Int, end: Int): String = bytes.decodeToString(from + start, from + end)

    private fun rebasing(error: ErrorSink): ErrorSink = ErrorSink { error.reportError(it.rebaseSpans(span.start())) }

    override fun toString(): String = "Raw(raw=${asStr()}, encoding=$encoding, span=$span)"

    public companion object {
        /**
         * A slice holding [raw] as if it had been cut from a source at [span], without checking that it
         * was. Errors from decoding it are rebased by [span]'s start.
         */
        public fun newUnchecked(raw: String, encoding: Encoding?, span: Span): Raw {
            val bytes = raw.encodeToByteArray()
            return Raw(bytes, 0, bytes.size, encoding, span)
        }

        private val KEY_STRING_KINDS = listOf(
            Expected.Token(ExpectedToken.BasicString),
            Expected.Token(ExpectedToken.LiteralString),
        )
    }
}

internal fun isCharBoundary(bytes: ByteArray, index: Int): Boolean {
    if (index == 0 || index == bytes.size) return true
    return (bytes[index].toInt() and 0xC0) != 0x80
}
