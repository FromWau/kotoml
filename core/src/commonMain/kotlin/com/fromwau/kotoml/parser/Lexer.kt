package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Encoding
import com.fromwau.kotoml.Span

/**
 * Splits TOML text into [Token]s without validating them, ending with one [TokenKind.Eof] token.
 *
 * Tokens cover the input without gaps, so their spans can rebuild it exactly; a leading byte-order mark
 * is skipped. Get one from [TomlSource.lex].
 */
internal class Lexer internal constructor(bytes: ByteArray) : Iterator<Token> {

    private val stream = LexStream(bytes, if (startsWith(bytes, 0, BOM)) BOM.size else 0)
    private var eof = false

    override fun hasNext(): Boolean = !stream.isEmpty() || !eof

    override fun next(): Token {
        if (stream.isEmpty()) {
            eof = true
            return Token(TokenKind.Eof, Span(stream.position, stream.position))
        }
        return stream.processToken(stream.peek())
    }

    /** Lexes the rest of the input into a list, the [TokenKind.Eof] token included. */
    fun toList(): List<Token> {
        val tokens = ArrayList<Token>((stream.remaining + 3) / 4)
        while (hasNext()) tokens.add(next())
        return tokens
    }
}

/** The input still to be lexed: [bytes] from [position] on. */
internal class LexStream(val bytes: ByteArray, var position: Int = 0) {
    fun isEmpty(): Boolean = position >= bytes.size

    val remaining: Int
        get() = bytes.size - position

    fun peek(): Int = byteAt(position)

    fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF

    /** The input not yet lexed. */
    fun rest(): String = bytes.decodeToString(position, bytes.size)

    fun token(kind: TokenKind, start: Int): Token = Token(kind, Span(start, position))
}

internal fun LexStream.processToken(peekByte: Int): Token = when (peekByte) {
    '.'.code -> lexAsciiChar(TokenKind.Dot)
    '='.code -> lexAsciiChar(TokenKind.Equals)
    ','.code -> lexAsciiChar(TokenKind.Comma)
    '['.code -> lexAsciiChar(TokenKind.LeftSquareBracket)
    ']'.code -> lexAsciiChar(TokenKind.RightSquareBracket)
    '{'.code -> lexAsciiChar(TokenKind.LeftCurlyBracket)
    '}'.code -> lexAsciiChar(TokenKind.RightCurlyBracket)
    ' '.code, '\t'.code -> lexWhitespace()
    '#'.code -> lexComment()
    '\r'.code -> lexCrlf()
    '\n'.code -> lexAsciiChar(TokenKind.Newline)
    '\''.code -> if (startsWith(bytes, position, ML_LITERAL_STRING_DELIM)) {
        lexMlLiteralString()
    } else {
        lexLiteralString()
    }
    '"'.code -> if (startsWith(bytes, position, ML_BASIC_STRING_DELIM)) {
        lexMlBasicString()
    } else {
        lexBasicString()
    }
    else -> lexAtom()
}

internal fun LexStream.lexAsciiChar(kind: TokenKind): Token {
    val start = position
    position += 1
    return token(kind, start)
}

/** `ws = *wschar` */
internal fun LexStream.lexWhitespace(): Token {
    val start = position
    while (!isEmpty() && isWsChar(peek())) position++
    return token(TokenKind.Whitespace, start)
}

/** `comment = comment-start-symbol *non-eol`, up to but not including the line ending. */
internal fun LexStream.lexComment(): Token {
    val start = position
    while (!isEmpty() && peek() != '\r'.code && peek() != '\n'.code) position++
    return token(TokenKind.Comment, start)
}

/** `newline = %x0D.0A`, or a lone `\r` for the decoder to report. */
internal fun LexStream.lexCrlf(): Token {
    val start = position
    position += 1
    if (!isEmpty() && peek() == '\n'.code) position += 1
    return token(TokenKind.Newline, start)
}

/** `literal-string = apostrophe *literal-char apostrophe`, ending early at a newline. */
internal fun LexStream.lexLiteralString(): Token {
    val start = position
    position += 1
    while (!isEmpty()) {
        val byte = peek()
        if (byte == APOSTROPHE) {
            position += 1
            break
        }
        if (byte == '\n'.code) break
        position++
    }
    return token(TokenKind.LiteralString, start)
}

/** `ml-literal-string`, taking up to two more apostrophes after the closing delimiter as content. */
internal fun LexStream.lexMlLiteralString(): Token {
    val start = position
    position += ML_LITERAL_STRING_DELIM.size
    val close = indexOf(bytes, position, ML_LITERAL_STRING_DELIM)
    position = if (close < 0) bytes.size else close + ML_LITERAL_STRING_DELIM.size
    if (!isEmpty() && peek() == APOSTROPHE) {
        position += 1
        if (!isEmpty() && peek() == APOSTROPHE) position += 1
    }
    return token(TokenKind.MultilineLiteralString, start)
}

/** `basic-string = quotation-mark *basic-char quotation-mark`, ending early at a newline. */
internal fun LexStream.lexBasicString(): Token {
    val start = position
    position += 1
    while (true) {
        var found = position
        while (found < bytes.size) {
            val byte = byteAt(found)
            if (byte == QUOTATION_MARK || byte == ESCAPE || byte == '\n'.code) break
            found++
        }
        if (found >= bytes.size) {
            position = bytes.size
            break
        }
        when (byteAt(found)) {
            QUOTATION_MARK -> {
                position = found + 1
                break
            }
            ESCAPE -> {
                position = found + 1
                if (!isEmpty() && (peek() == ESCAPE || peek() == QUOTATION_MARK)) position += 1
            }
            else -> {
                // A newline ends the token so the next line is lexed on its own, for error recovery.
                position = found
                break
            }
        }
    }
    return token(TokenKind.BasicString, start)
}

/** `ml-basic-string`, taking up to two more quotes after the closing delimiter as content. */
internal fun LexStream.lexMlBasicString(): Token {
    val start = position
    position += ML_BASIC_STRING_DELIM.size
    while (true) {
        var found = position
        while (found < bytes.size) {
            if (byteAt(found) == ESCAPE || startsWith(bytes, found, ML_BASIC_STRING_DELIM)) break
            found++
        }
        if (found >= bytes.size) {
            position = bytes.size
            break
        }
        if (byteAt(found) == QUOTATION_MARK) {
            position = found + ML_BASIC_STRING_DELIM.size
            break
        }
        position = found + 1
        if (!isEmpty() && (peek() == ESCAPE || peek() == QUOTATION_MARK)) position += 1
    }
    if (!isEmpty() && peek() == QUOTATION_MARK) {
        position += 1
        if (!isEmpty() && peek() == QUOTATION_MARK) position += 1
    }
    return token(TokenKind.MultilineBasicString, start)
}

/** Everything else, up to the next byte that can start another token. */
internal fun LexStream.lexAtom(): Token {
    val start = position
    // Quotes are left out of the stop set, so a value missing its opening quote stays one token.
    while (!isEmpty() && !isAtomEnd(peek())) position++
    return token(TokenKind.Atom, start)
}

private fun isAtomEnd(byte: Int): Boolean = when (byte) {
    '.'.code, '='.code, ','.code, '['.code, ']'.code, '{'.code, '}'.code, ' '.code, '\t'.code, '#'.code,
    '\r'.code, '\n'.code,
    -> true
    else -> false
}

/** One unvalidated token: its [kind] and the [span] of input it covers. */
internal data class Token(
    val kind: TokenKind,
    val span: Span,
)

/**
 * The kinds of [Token].
 *
 * They are declared in upstream's order: its kinds are numbered by the token's first byte and compare
 * by that number.
 */
internal enum class TokenKind {
    /** A `'''multi-line literal'''` string, possibly unclosed. */
    MultilineLiteralString,

    /** A `"""multi-line basic"""` string, possibly unclosed. */
    MultilineBasicString,

    /** Anything else: a bare key, a number, a boolean, a date-time, or garbage. */
    Atom,

    /** The end of the input. */
    Eof,

    /** `\n` or `\r\n`, or a lone `\r`. */
    Newline,

    /** A run of spaces and tabs. */
    Whitespace,

    /** A `"basic"` string, possibly unclosed. */
    BasicString,

    /** `#` up to the end of the line. */
    Comment,

    /** A `'literal'` string, possibly unclosed. */
    LiteralString,

    /** `,`, between values. */
    Comma,

    /** `.`, either in a dotted key or in a float. */
    Dot,

    /** `=`, between a key and its value. */
    Equals,

    /** `[`, opening an array or a table header. */
    LeftSquareBracket,

    /** `]`, closing an array or a table header. */
    RightSquareBracket,

    /** `{`, opening an inline table. */
    LeftCurlyBracket,

    /** `}`, closing an inline table. */
    RightCurlyBracket,
    ;

    /** The string encoding a token of this kind is written in, or null when it is not a string. */
    val encoding: Encoding?
        get() = when (this) {
            LiteralString -> Encoding.LiteralString
            BasicString -> Encoding.BasicString
            MultilineLiteralString -> Encoding.MultilineLiteralString
            MultilineBasicString -> Encoding.MultilineBasicString
            else -> null
        }

}

internal const val APOSTROPHE: Int = '\''.code
internal const val QUOTATION_MARK: Int = '"'.code
internal const val ESCAPE: Int = '\\'.code
internal const val COMMENT_START_SYMBOL: Int = '#'.code

internal val BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
internal val ML_LITERAL_STRING_DELIM: ByteArray = "'''".encodeToByteArray()
internal val ML_BASIC_STRING_DELIM: ByteArray = "\"\"\"".encodeToByteArray()

internal fun isWsChar(byte: Int): Boolean = byte == ' '.code || byte == '\t'.code

internal fun startsWith(bytes: ByteArray, at: Int, prefix: ByteArray): Boolean {
    if (at < 0 || at + prefix.size > bytes.size) return false
    for (index in prefix.indices) {
        if (bytes[at + index] != prefix[index]) return false
    }
    return true
}

internal fun indexOf(bytes: ByteArray, from: Int, needle: ByteArray): Int {
    var index = from
    while (index + needle.size <= bytes.size) {
        if (startsWith(bytes, index, needle)) return index
        index++
    }
    return -1
}
