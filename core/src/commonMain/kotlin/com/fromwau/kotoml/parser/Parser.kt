package com.fromwau.kotoml.parser

import com.fromwau.kotoml.EMPTY
import com.fromwau.kotoml.Encoding
import com.fromwau.kotoml.Expected
import com.fromwau.kotoml.ExpectedToken
import com.fromwau.kotoml.ParseErrorReason
import com.fromwau.kotoml.Span
import com.fromwau.kotoml.after
import com.fromwau.kotoml.append
import com.fromwau.kotoml.before

/**
 * Parses lexed [tokens] as a whole TOML document, reporting [Event]s to [receiver] and problems to
 * [error].
 *
 * Only the order of events is checked here, not their content or semantics such as duplicate keys:
 * decode the events to validate keys and values, and build a document from them to find duplicates.
 * Parsing never stops at an error; it recovers and keeps reporting.
 *
 * Nesting is followed with a heap-allocated stack, so any depth parses without exhausting the call stack;
 * wrap [receiver] in a [RecursionGuard] to bound it.
 */
internal fun parseDocument(tokens: List<Token>, receiver: EventReceiver, error: ErrorSink) {
    val parser = DocumentParser(TokenStream(tokens), receiver, error)
    parser.document()
    parser.eof()
}

/** Parses lexed [tokens] as one key, simple or dotted. See [parseDocument]. */
internal fun parseKey(tokens: List<Token>, receiver: EventReceiver, error: ErrorSink) {
    val parser = DocumentParser(TokenStream(tokens), receiver, error)
    parser.key(ParseErrorReason.InvalidKey)
    parser.eof()
}

/** Parses lexed [tokens] as one simple, undotted key. See [parseDocument]. */
internal fun parseSimpleKey(tokens: List<Token>, receiver: EventReceiver, error: ErrorSink) {
    val parser = DocumentParser(TokenStream(tokens), receiver, error)
    parser.simpleKey(ParseErrorReason.InvalidKey)
    parser.eof()
}

/** Parses lexed [tokens] as one value. See [parseDocument]. */
internal fun parseValue(tokens: List<Token>, receiver: EventReceiver, error: ErrorSink) {
    val parser = DocumentParser(TokenStream(tokens), receiver, error)
    parser.value()
    parser.eof()
}

internal class TokenStream(private val tokens: List<Token>) {
    private var position = 0

    fun next(): Token? = if (position < tokens.size) tokens[position++] else null

    fun peek(): Token? = tokens.getOrNull(position)

    fun get(offset: Int): Token? = tokens.getOrNull(position + offset)

    fun skip(count: Int) {
        position = minOf(position + count, tokens.size)
    }

    fun seek(offset: Int) {
        val target = position + offset
        position = if (target in 0 until tokens.size) target else tokens.size
    }

    /** The span of the last consumed token that is not whitespace, a comment, a newline or the end. */
    fun previousSignificantSpan(): Span {
        for (index in position - 1 downTo 0) {
            val kind = tokens[index].kind
            if (kind != TokenKind.Whitespace &&
                kind != TokenKind.Comment &&
                kind != TokenKind.Newline &&
                kind != TokenKind.Eof
            ) {
                return tokens[index].span
            }
        }
        return Span.EMPTY
    }

    fun previousNonWhitespace(): Token? {
        for (index in position - 1 downTo 0) {
            if (tokens[index].kind != TokenKind.Whitespace) return tokens[index]
        }
        return null
    }
}

private enum class ArrayState { NeedsValue, NeedsComma }

private enum class InlineState {
    NeedsKey,
    NeedsEquals,
    NeedsValue,
    NeedsComma,
    ;

    fun expected(): List<Expected> = when (this) {
        NeedsKey -> listOf(Expected.Token(ExpectedToken.Key))
        NeedsEquals -> listOf(Expected.Literal("="))
        NeedsValue -> listOf(Expected.Token(ExpectedToken.Value))
        NeedsComma -> listOf(Expected.Literal(","))
    }
}

private sealed class Frame(val open: Token)

private class ArrayFrame(open: Token) : Frame(open) {
    var state = ArrayState.NeedsValue
}

private class InlineFrame(open: Token) : Frame(open) {
    var state = InlineState.NeedsKey
}

private val EXPECTED_KEY = listOf(Expected.Token(ExpectedToken.Key))
private val EXPECTED_VALUE = listOf(Expected.Token(ExpectedToken.Value))
private val EXPECTED_NOTHING = emptyList<Expected>()
private val EXPECTED_COMMA = listOf(Expected.Literal(","))
private val EXPECTED_KEYS = listOf(
    Expected.Token(ExpectedToken.LiteralString),
    Expected.Token(ExpectedToken.BasicString),
    Expected.Token(ExpectedToken.UnquotedString),
)

internal class DocumentParser(
    private val tokens: TokenStream,
    private val receiver: EventReceiver,
    private val error: ErrorSink,
) {

    fun document() {
        while (true) {
            val current = tokens.next() ?: break
            when (current.kind) {
                TokenKind.LeftSquareBracket -> onTable(current)
                TokenKind.RightSquareBracket -> onMissingStdTable(current)
                TokenKind.LiteralString -> onExpressionKey(current, Encoding.LiteralString)
                TokenKind.BasicString -> onExpressionKey(current, Encoding.BasicString)
                TokenKind.MultilineLiteralString -> onExpressionKey(current, Encoding.MultilineLiteralString)
                TokenKind.MultilineBasicString -> onExpressionKey(current, Encoding.MultilineBasicString)
                TokenKind.Atom -> onExpressionKey(current, null)
                TokenKind.Equals -> {
                    receiver.simpleKey(current.span.before(), null, error)
                    onExpressionKeyValSep(current)
                }
                TokenKind.Dot -> onExpressionDot(current)
                TokenKind.Comma, TokenKind.RightCurlyBracket, TokenKind.LeftCurlyBracket ->
                    onMissingExpressionKey(current)
                TokenKind.Whitespace -> receiver.whitespace(current.span, error)
                TokenKind.Newline -> receiver.newline(current.span, error)
                TokenKind.Comment -> onComment(current)
                TokenKind.Eof -> break
            }
        }
    }

    private fun onTable(openToken: Token) {
        val secondOpen = nextTokenIf { it == TokenKind.LeftSquareBracket }
        val isArrayTable = if (secondOpen != null) {
            receiver.arrayTableOpen(openToken.span.append(secondOpen.span), error)
            true
        } else {
            receiver.stdTableOpen(openToken.span, error)
            false
        }

        optWhitespace()

        val validKey = key(ParseErrorReason.InvalidTable)

        optWhitespace()

        var success = false
        val closeToken = nextTokenIf { it == TokenKind.RightSquareBracket }
        if (closeToken != null) {
            if (isArrayTable) {
                val secondClose = nextTokenIf { it == TokenKind.RightSquareBracket }
                if (secondClose != null) {
                    receiver.arrayTableClose(closeToken.span.append(secondClose.span), error)
                    success = true
                } else {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.UnclosedArrayTable,
                            context = openToken.span.append(closeToken.span),
                            expected = listOf(Expected.Literal("]")),
                            unexpected = closeToken.span.after(),
                        ),
                    )
                }
            } else {
                receiver.stdTableClose(closeToken.span, error)
                success = true
            }
        } else if (validKey) {
            val lastKeyToken = tokens.previousNonWhitespace() ?: openToken
            val context = openToken.span.append(lastKeyToken.span)
            if (isArrayTable) {
                error.reportError(
                    ParseError(
                        ParseErrorReason.UnclosedArrayTable,
                        context = context,
                        expected = listOf(Expected.Literal("]]")),
                        unexpected = lastKeyToken.span.after(),
                    ),
                )
            } else {
                error.reportError(
                    ParseError(
                        ParseErrorReason.UnclosedTable,
                        context = context,
                        expected = listOf(Expected.Literal("]")),
                        unexpected = lastKeyToken.span.after(),
                    ),
                )
            }
        }

        if (success) {
            wsCommentNewline()
        } else {
            ignoreToNewline()
        }
    }

    fun key(invalidReason: ParseErrorReason): Boolean {
        while (true) {
            val current = tokens.next() ?: break
            val encoding = when (current.kind) {
                TokenKind.RightSquareBracket,
                TokenKind.Comment,
                TokenKind.Equals,
                TokenKind.Comma,
                TokenKind.LeftSquareBracket,
                TokenKind.LeftCurlyBracket,
                TokenKind.RightCurlyBracket,
                TokenKind.Newline,
                TokenKind.Eof,
                -> {
                    receiver.simpleKey(current.span.before(), null, error)
                    tokens.seek(-1)
                    return false
                }
                TokenKind.Whitespace -> {
                    receiver.whitespace(current.span, error)
                    continue
                }
                TokenKind.Dot -> {
                    receiver.simpleKey(current.span.before(), null, error)
                    receiver.keySep(current.span, error)
                    continue
                }
                TokenKind.LiteralString -> Encoding.LiteralString
                TokenKind.BasicString -> Encoding.BasicString
                TokenKind.MultilineLiteralString -> Encoding.MultilineLiteralString
                TokenKind.MultilineBasicString -> Encoding.MultilineBasicString
                TokenKind.Atom -> null
            }
            receiver.simpleKey(current.span, encoding, error)
            return optDotKeys()
        }

        val previousSpan = tokens.previousSignificantSpan()
        error.reportError(
            ParseError(
                invalidReason,
                context = previousSpan,
                expected = EXPECTED_KEY,
                unexpected = previousSpan.after(),
            ),
        )
        return false
    }

    private fun onExpressionKey(keyToken: Token, encoding: Encoding?) {
        receiver.simpleKey(keyToken.span, encoding, error)
        optDotKeys()

        optWhitespace()

        val eqToken = nextTokenIf { it == TokenKind.Equals }
        if (eqToken == null) {
            tokens.peek()?.let { peek ->
                val span = peek.span.before()
                error.reportError(
                    ParseError(
                        ParseErrorReason.KeyWithoutValue,
                        context = span,
                        expected = listOf(Expected.Literal("=")),
                        unexpected = span,
                    ),
                )
            }
            ignoreToNewline()
            return
        }
        onExpressionKeyValSep(eqToken)
    }

    private fun onExpressionDot(dotToken: Token) {
        receiver.simpleKey(dotToken.span.before(), null, error)
        tokens.seek(-1)
        optDotKeys()

        optWhitespace()

        val eqToken = nextTokenIf { it == TokenKind.Equals }
        if (eqToken == null) {
            tokens.peek()?.let { peek ->
                val span = peek.span.before()
                error.reportError(
                    ParseError(
                        ParseErrorReason.MissingValueForKey,
                        context = span,
                        expected = listOf(Expected.Literal("=")),
                        unexpected = span,
                    ),
                )
            }
            ignoreToNewline()
            return
        }
        onExpressionKeyValSep(eqToken)
    }

    private fun onExpressionKeyValSep(eqToken: Token) {
        receiver.keyValSep(eqToken.span, error)

        optWhitespace()

        value()

        wsCommentNewline()
    }

    fun simpleKey(invalidReason: ParseErrorReason) {
        val current = tokens.next()
        if (current == null) {
            val previousSpan = tokens.previousSignificantSpan()
            error.reportError(
                ParseError(
                    invalidReason,
                    context = previousSpan,
                    expected = EXPECTED_KEY,
                    unexpected = previousSpan.after(),
                ),
            )
            return
        }

        val kind = when (current.kind) {
            TokenKind.Dot,
            TokenKind.RightSquareBracket,
            TokenKind.Comment,
            TokenKind.Equals,
            TokenKind.Comma,
            TokenKind.LeftSquareBracket,
            TokenKind.LeftCurlyBracket,
            TokenKind.RightCurlyBracket,
            TokenKind.Newline,
            TokenKind.Eof,
            TokenKind.Whitespace,
            -> {
                onMissingKey(current, invalidReason)
                return
            }
            TokenKind.LiteralString -> Encoding.LiteralString
            TokenKind.BasicString -> Encoding.BasicString
            TokenKind.MultilineLiteralString -> {
                error.reportError(
                    ParseError(
                        invalidReason,
                        context = current.span,
                        expected = EXPECTED_KEYS,
                        unexpected = current.span,
                    ),
                )
                Encoding.MultilineLiteralString
            }
            TokenKind.MultilineBasicString -> {
                error.reportError(
                    ParseError(
                        invalidReason,
                        context = current.span,
                        expected = EXPECTED_KEYS,
                        unexpected = current.span,
                    ),
                )
                Encoding.MultilineBasicString
            }
            TokenKind.Atom -> null
        }
        receiver.simpleKey(current.span, kind, error)
    }

    private fun optDotKeys(): Boolean {
        optWhitespace()

        var success = true
        dot@ while (true) {
            val dotToken = nextTokenIf { it == TokenKind.Dot } ?: break
            receiver.keySep(dotToken.span, error)

            while (true) {
                val current = tokens.next() ?: break
                val kind = when (current.kind) {
                    TokenKind.Equals,
                    TokenKind.Comma,
                    TokenKind.LeftSquareBracket,
                    TokenKind.RightSquareBracket,
                    TokenKind.LeftCurlyBracket,
                    TokenKind.RightCurlyBracket,
                    TokenKind.Comment,
                    TokenKind.Newline,
                    TokenKind.Eof,
                    -> {
                        receiver.simpleKey(current.span.before(), null, error)
                        tokens.seek(-1)

                        success = false
                        break@dot
                    }
                    TokenKind.Whitespace -> {
                        receiver.whitespace(current.span, error)
                        continue
                    }
                    TokenKind.Dot -> {
                        receiver.simpleKey(current.span.before(), null, error)
                        receiver.keySep(current.span, error)
                        continue
                    }
                    TokenKind.LiteralString -> Encoding.LiteralString
                    TokenKind.BasicString -> Encoding.BasicString
                    TokenKind.MultilineLiteralString -> Encoding.MultilineLiteralString
                    TokenKind.MultilineBasicString -> Encoding.MultilineBasicString
                    TokenKind.Atom -> null
                }
                receiver.simpleKey(current.span, kind, error)
                optWhitespace()
                continue@dot
            }

            receiver.simpleKey(dotToken.span.after(), null, error)
        }

        return success
    }

    fun value() {
        var current: Token
        while (true) {
            current = tokens.next() ?: run {
                val previousSpan = tokens.previousSignificantSpan()
                error.reportError(
                    ParseError(
                        ParseErrorReason.MissingValue,
                        context = previousSpan,
                        expected = EXPECTED_VALUE,
                        unexpected = previousSpan.after(),
                    ),
                )
                return
            }
            if (current.kind != TokenKind.Equals) break
            error.reportError(
                ParseError(
                    ParseErrorReason.ExtraEquals,
                    context = current.span,
                    expected = EXPECTED_NOTHING,
                    unexpected = current.span,
                ),
            )
            receiver.error(current.span, error)
        }

        when (current.kind) {
            TokenKind.Comment,
            TokenKind.Comma,
            TokenKind.Newline,
            TokenKind.Eof,
            TokenKind.Whitespace,
            -> {
                receiver.scalar(current.span.before(), null, error)
                tokens.seek(-1)
            }
            TokenKind.Equals -> {}
            TokenKind.LeftCurlyBracket -> onNestedOpen(current, isArray = false)
            TokenKind.RightCurlyBracket -> {
                error.reportError(
                    ParseError(
                        ParseErrorReason.MissingInlineTableOpening,
                        context = current.span,
                        expected = listOf(Expected.Literal("{")),
                        unexpected = current.span.before(),
                    ),
                )

                receiver.inlineTableOpen(current.span.before(), error)
                receiver.inlineTableClose(current.span, error)
            }
            TokenKind.LeftSquareBracket -> onNestedOpen(current, isArray = true)
            TokenKind.RightSquareBracket -> {
                error.reportError(
                    ParseError(
                        ParseErrorReason.MissingArrayOpening,
                        context = current.span,
                        expected = listOf(Expected.Literal("[")),
                        unexpected = current.span.before(),
                    ),
                )

                receiver.arrayOpen(current.span.before(), error)
                receiver.arrayClose(current.span, error)
            }
            TokenKind.LiteralString,
            TokenKind.BasicString,
            TokenKind.MultilineLiteralString,
            TokenKind.MultilineBasicString,
            TokenKind.Dot,
            TokenKind.Atom,
            -> onScalar(current)
        }
    }

    private fun onScalar(scalar: Token) {
        var span = scalar.span
        val encoding = when (scalar.kind) {
            TokenKind.LiteralString -> Encoding.LiteralString
            TokenKind.BasicString -> Encoding.BasicString
            TokenKind.MultilineLiteralString -> Encoding.MultilineLiteralString
            TokenKind.MultilineBasicString -> Encoding.MultilineBasicString
            else -> {
                while (true) {
                    val next = tokens.peek() ?: break
                    when (next.kind) {
                        TokenKind.Whitespace -> {
                            val second = tokens.get(1)
                            if (second != null && second.kind == TokenKind.Atom) {
                                span = span.append(second.span)
                                tokens.skip(2)
                                continue
                            }
                            break
                        }
                        TokenKind.Dot, TokenKind.Atom -> {
                            span = span.append(next.span)
                            tokens.next()
                        }
                        else -> break
                    }
                }
                null
            }
        }
        receiver.scalar(span, encoding, error)
    }

    /**
     * Parses the array or inline table opened by [open] and every container nested in it, keeping the
     * open containers on a heap stack instead of the call stack.
     */
    private fun onNestedOpen(open: Token, isArray: Boolean) {
        val stack = ArrayDeque<Frame>()
        enter(open, isArray, stack)
        while (stack.isNotEmpty()) {
            val finished = when (val frame = stack.last()) {
                is ArrayFrame -> stepArray(frame, stack)
                is InlineFrame -> stepInlineTable(frame, stack)
            }
            if (finished) stack.removeLast()
        }
    }

    private fun enter(open: Token, isArray: Boolean, stack: ArrayDeque<Frame>) {
        if (isArray) {
            if (receiver.arrayOpen(open.span, error)) {
                stack.addLast(ArrayFrame(open))
            } else {
                ignoreToValueClose(TokenKind.RightSquareBracket)
            }
        } else {
            if (receiver.inlineTableOpen(open.span, error)) {
                stack.addLast(InlineFrame(open))
            } else {
                ignoreToValueClose(TokenKind.RightCurlyBracket)
            }
        }
    }

    private fun missingArrayComma(frame: ArrayFrame, current: Token) {
        if (frame.state != ArrayState.NeedsValue) {
            error.reportError(
                ParseError(
                    ParseErrorReason.MissingArrayComma,
                    context = frame.open.span,
                    expected = EXPECTED_COMMA,
                    unexpected = current.span.before(),
                ),
            )
            receiver.valueSep(current.span.before(), error)
        }
    }

    /** Consumes one token of the array in [frame]; returns whether the array is finished. */
    private fun stepArray(frame: ArrayFrame, stack: ArrayDeque<Frame>): Boolean {
        val current = tokens.next() ?: return finishUnclosedArray(frame)
        when (current.kind) {
            TokenKind.Comment -> onComment(current)
            TokenKind.Whitespace -> receiver.whitespace(current.span, error)
            TokenKind.Newline -> receiver.newline(current.span, error)
            TokenKind.Eof -> return finishUnclosedArray(frame)
            TokenKind.Comma -> when (frame.state) {
                ArrayState.NeedsValue -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.ExtraArrayComma,
                            context = frame.open.span,
                            expected = EXPECTED_VALUE,
                            unexpected = current.span,
                        ),
                    )
                    receiver.error(current.span, error)
                }
                ArrayState.NeedsComma -> {
                    receiver.valueSep(current.span, error)
                    frame.state = ArrayState.NeedsValue
                }
            }
            TokenKind.Equals -> {
                error.reportError(
                    ParseError(
                        ParseErrorReason.UnexpectedEqualsInArray,
                        context = frame.open.span,
                        expected = listOf(Expected.Token(ExpectedToken.Value), Expected.Literal("]")),
                        unexpected = current.span,
                    ),
                )
                receiver.error(current.span, error)
            }
            TokenKind.LeftCurlyBracket -> {
                missingArrayComma(frame, current)
                frame.state = ArrayState.NeedsComma
                enter(current, isArray = false, stack)
            }
            TokenKind.RightCurlyBracket -> {
                missingArrayComma(frame, current)

                error.reportError(
                    ParseError(
                        ParseErrorReason.MissingInlineTableOpening,
                        context = current.span,
                        expected = listOf(Expected.Literal("{")),
                        unexpected = current.span.before(),
                    ),
                )

                receiver.inlineTableOpen(current.span.before(), error)
                receiver.inlineTableClose(current.span, error)

                frame.state = ArrayState.NeedsComma
            }
            TokenKind.LeftSquareBracket -> {
                missingArrayComma(frame, current)
                frame.state = ArrayState.NeedsComma
                enter(current, isArray = true, stack)
            }
            TokenKind.RightSquareBracket -> {
                receiver.arrayClose(current.span, error)
                return true
            }
            TokenKind.LiteralString,
            TokenKind.BasicString,
            TokenKind.MultilineLiteralString,
            TokenKind.MultilineBasicString,
            TokenKind.Dot,
            TokenKind.Atom,
            -> {
                missingArrayComma(frame, current)
                onScalar(current)
                frame.state = ArrayState.NeedsComma
            }
        }
        return false
    }

    private fun finishUnclosedArray(frame: ArrayFrame): Boolean {
        val previousSpan = tokens.previousSignificantSpan()
        error.reportError(
            ParseError(
                ParseErrorReason.UnclosedArray,
                context = frame.open.span,
                expected = listOf(Expected.Literal("]")),
                unexpected = previousSpan.after(),
            ),
        )
        receiver.arrayClose(previousSpan.after(), error)
        return true
    }

    private fun inlineKey(current: Token) {
        if (current.kind == TokenKind.Dot) {
            receiver.simpleKey(current.span.before(), current.kind.encoding, error)
            tokens.seek(-1)
        } else {
            receiver.simpleKey(current.span, current.kind.encoding, error)
        }
        optDotKeys()
    }

    /** Consumes one token of the inline table in [frame]; returns whether the table is finished. */
    private fun stepInlineTable(frame: InlineFrame, stack: ArrayDeque<Frame>): Boolean {
        val open = frame.open
        val current = tokens.next() ?: return finishUnclosedInlineTable(frame)
        when (current.kind) {
            TokenKind.Comment -> onComment(current)
            TokenKind.Whitespace -> receiver.whitespace(current.span, error)
            TokenKind.Newline -> receiver.newline(current.span, error)
            TokenKind.Eof -> return finishUnclosedInlineTable(frame)
            TokenKind.Comma -> when (frame.state) {
                InlineState.NeedsKey, InlineState.NeedsEquals, InlineState.NeedsValue -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.ExtraInlineTableComma,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    receiver.error(current.span, error)
                }
                InlineState.NeedsComma -> {
                    receiver.valueSep(current.span, error)
                    frame.state = InlineState.NeedsKey
                }
            }
            TokenKind.Equals -> when (frame.state) {
                InlineState.NeedsKey -> {
                    receiver.simpleKey(current.span.before(), null, error)
                    receiver.keyValSep(current.span, error)
                    frame.state = InlineState.NeedsValue
                }
                InlineState.NeedsEquals -> {
                    receiver.keyValSep(current.span, error)
                    frame.state = InlineState.NeedsValue
                }
                InlineState.NeedsValue, InlineState.NeedsComma -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.ExtraAssignment,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    receiver.error(current.span, error)
                }
            }
            TokenKind.LeftCurlyBracket -> when (frame.state) {
                InlineState.NeedsKey, InlineState.NeedsComma -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingInlineTableKey,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    receiver.error(current.span, error)
                    ignoreToValueClose(TokenKind.RightCurlyBracket)
                }
                InlineState.NeedsEquals -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingAssignment,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    frame.state = InlineState.NeedsComma
                    enter(current, isArray = false, stack)
                }
                InlineState.NeedsValue -> {
                    frame.state = InlineState.NeedsComma
                    enter(current, isArray = false, stack)
                }
            }
            TokenKind.RightCurlyBracket -> {
                when (frame.state) {
                    InlineState.NeedsKey, InlineState.NeedsComma -> {}
                    InlineState.NeedsEquals -> {
                        receiver.keyValSep(current.span.before(), error)
                        receiver.scalar(current.span.before(), Encoding.LiteralString, error)
                    }
                    InlineState.NeedsValue -> {
                        receiver.scalar(current.span.before(), Encoding.LiteralString, error)
                    }
                }
                receiver.inlineTableClose(current.span, error)
                return true
            }
            TokenKind.LeftSquareBracket -> when (frame.state) {
                InlineState.NeedsKey, InlineState.NeedsComma -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingInlineTableKey,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    receiver.error(current.span, error)
                    ignoreToValueClose(TokenKind.RightSquareBracket)
                }
                InlineState.NeedsEquals -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingAssignment,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    frame.state = InlineState.NeedsComma
                    enter(current, isArray = true, stack)
                }
                InlineState.NeedsValue -> {
                    frame.state = InlineState.NeedsComma
                    enter(current, isArray = true, stack)
                }
            }
            TokenKind.RightSquareBracket -> when (frame.state) {
                InlineState.NeedsKey, InlineState.NeedsEquals, InlineState.NeedsComma -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.InvalidInlineTableElement,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    receiver.error(current.span, error)
                }
                InlineState.NeedsValue -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingArrayOpening,
                            context = current.span,
                            expected = listOf(Expected.Literal("[")),
                            unexpected = current.span.before(),
                        ),
                    )

                    receiver.arrayOpen(current.span.before(), error)
                    receiver.arrayClose(current.span, error)

                    frame.state = InlineState.NeedsComma
                }
            }
            TokenKind.LiteralString,
            TokenKind.BasicString,
            TokenKind.MultilineLiteralString,
            TokenKind.MultilineBasicString,
            TokenKind.Dot,
            TokenKind.Atom,
            -> when (frame.state) {
                InlineState.NeedsKey -> {
                    inlineKey(current)
                    frame.state = InlineState.NeedsEquals
                }
                InlineState.NeedsEquals -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingAssignment,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    onScalar(current)
                    frame.state = InlineState.NeedsComma
                }
                InlineState.NeedsValue -> {
                    onScalar(current)
                    frame.state = InlineState.NeedsComma
                }
                InlineState.NeedsComma -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.MissingInlineTableComma,
                            context = open.span,
                            expected = frame.state.expected(),
                            unexpected = current.span.before(),
                        ),
                    )
                    inlineKey(current)
                    frame.state = InlineState.NeedsEquals
                }
            }
        }
        return false
    }

    private fun finishUnclosedInlineTable(frame: InlineFrame): Boolean {
        val previousSpan = tokens.previousSignificantSpan()
        when (frame.state) {
            InlineState.NeedsKey, InlineState.NeedsComma -> {}
            InlineState.NeedsEquals -> {
                receiver.keyValSep(previousSpan.after(), error)
                receiver.scalar(previousSpan.after(), Encoding.LiteralString, error)
            }
            InlineState.NeedsValue -> {
                receiver.scalar(previousSpan.after(), Encoding.LiteralString, error)
            }
        }
        error.reportError(
            ParseError(
                ParseErrorReason.UnclosedInlineTable,
                context = frame.open.span,
                expected = listOf(Expected.Literal("}")),
                unexpected = previousSpan.after(),
            ),
        )
        receiver.inlineTableClose(previousSpan.after(), error)
        return true
    }

    private fun optWhitespace() {
        nextTokenIf { it == TokenKind.Whitespace }?.let { receiver.whitespace(it.span, error) }
    }

    private fun wsCommentNewline() {
        var first: Span? = null
        while (true) {
            val current = tokens.next() ?: break
            val firstSpan = first ?: current.span.also { first = it }
            when (current.kind) {
                TokenKind.Dot,
                TokenKind.Equals,
                TokenKind.Comma,
                TokenKind.LeftSquareBracket,
                TokenKind.RightSquareBracket,
                TokenKind.LeftCurlyBracket,
                TokenKind.RightCurlyBracket,
                TokenKind.LiteralString,
                TokenKind.BasicString,
                TokenKind.MultilineLiteralString,
                TokenKind.MultilineBasicString,
                TokenKind.Atom,
                -> {
                    error.reportError(
                        ParseError(
                            ParseErrorReason.UnexpectedKeyOrValue,
                            context = firstSpan.append(current.span),
                            expected = listOf(Expected.Literal("\n"), Expected.Literal("#")),
                            unexpected = current.span.before(),
                        ),
                    )

                    receiver.error(current.span, error)
                    ignoreToNewline()
                    break
                }
                TokenKind.Comment -> {
                    onComment(current)
                    break
                }
                TokenKind.Whitespace -> receiver.whitespace(current.span, error)
                TokenKind.Newline -> {
                    receiver.newline(current.span, error)
                    break
                }
                TokenKind.Eof -> break
            }
        }
    }

    private fun onComment(commentToken: Token) {
        receiver.comment(commentToken.span, error)

        val current = tokens.next() ?: return
        when (current.kind) {
            TokenKind.Newline -> receiver.newline(current.span, error)
            TokenKind.Eof -> {}
            else -> {
                error.reportError(
                    ParseError(
                        ParseErrorReason.ContentAfterComment,
                        context = commentToken.span.append(current.span),
                        expected = listOf(Expected.Literal("\n")),
                        unexpected = current.span.before(),
                    ),
                )

                receiver.error(current.span, error)
                ignoreToNewline()
            }
        }
    }

    fun eof() {
        val current = tokens.next() ?: return
        if (current.kind == TokenKind.Eof) return

        error.reportError(
            ParseError(
                ParseErrorReason.UnexpectedContent,
                context = current.span,
                expected = EXPECTED_NOTHING,
                unexpected = current.span.before(),
            ),
        )

        receiver.error(current.span, error)
        while (true) {
            val next = tokens.next() ?: break
            if (next.kind == TokenKind.Eof) continue
            receiver.error(next.span, error)
        }
    }

    private fun ignoreToNewline() {
        while (true) {
            val current = tokens.next() ?: break
            when (current.kind) {
                TokenKind.Comment -> {
                    onComment(current)
                    break
                }
                TokenKind.Whitespace -> receiver.whitespace(current.span, error)
                TokenKind.Newline -> {
                    receiver.newline(current.span, error)
                    break
                }
                TokenKind.Eof -> break
                else -> receiver.error(current.span, error)
            }
        }
    }

    private fun ignoreToValueClose(closingKind: TokenKind) {
        var arrayCount = 0
        var inlineTableCount = 0
        while (true) {
            val current = tokens.next() ?: break
            when (current.kind) {
                TokenKind.Dot,
                TokenKind.Equals,
                TokenKind.Comma,
                TokenKind.LiteralString,
                TokenKind.BasicString,
                TokenKind.MultilineLiteralString,
                TokenKind.MultilineBasicString,
                TokenKind.Atom,
                -> receiver.error(current.span, error)
                TokenKind.Comment -> onComment(current)
                TokenKind.Whitespace -> receiver.whitespace(current.span, error)
                TokenKind.Newline -> receiver.newline(current.span, error)
                TokenKind.LeftSquareBracket -> {
                    receiver.error(current.span, error)
                    arrayCount += 1
                }
                TokenKind.RightSquareBracket -> {
                    if (arrayCount == 0 && current.kind == closingKind) {
                        receiver.arrayClose(current.span, error)
                        break
                    } else {
                        receiver.error(current.span, error)
                        arrayCount = maxOf(arrayCount - 1, 0)
                    }
                }
                TokenKind.LeftCurlyBracket -> {
                    receiver.error(current.span, error)
                    inlineTableCount += 1
                }
                TokenKind.RightCurlyBracket -> {
                    if (inlineTableCount == 0 && current.kind == closingKind) {
                        receiver.inlineTableClose(current.span, error)
                        break
                    } else {
                        receiver.error(current.span, error)
                        inlineTableCount = maxOf(inlineTableCount - 1, 0)
                    }
                }
                TokenKind.Eof -> break
            }
        }
    }

    private fun onMissingKey(token: Token, invalidReason: ParseErrorReason) {
        error.reportError(
            ParseError(
                invalidReason,
                context = token.span,
                expected = EXPECTED_KEY,
                unexpected = token.span.before(),
            ),
        )

        when (token.kind) {
            TokenKind.Eof -> {}
            TokenKind.Newline -> receiver.newline(token.span, error)
            TokenKind.Comment -> onComment(token)
            else -> receiver.error(token.span, error)
        }
    }

    private fun onMissingExpressionKey(token: Token) {
        error.reportError(
            ParseError(
                ParseErrorReason.InvalidKeyValuePair,
                context = token.span,
                expected = EXPECTED_KEY,
                unexpected = token.span.before(),
            ),
        )

        receiver.error(token.span, error)
        ignoreToNewline()
    }

    private fun onMissingStdTable(token: Token) {
        error.reportError(
            ParseError(
                ParseErrorReason.MissingTableOpen,
                context = token.span,
                expected = listOf(Expected.Literal("[")),
                unexpected = token.span.before(),
            ),
        )

        receiver.error(token.span, error)
        ignoreToNewline()
    }

    private inline fun nextTokenIf(predicate: (TokenKind) -> Boolean): Token? {
        val next = tokens.peek() ?: return null
        return if (predicate(next.kind)) tokens.next() else null
    }
}
