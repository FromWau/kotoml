package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Encoding
import com.fromwau.kotoml.Expected
import com.fromwau.kotoml.ParseErrorReason
import com.fromwau.kotoml.Span

/**
 * Receives the [Event]s of a parse, in input order. Every function has a default that ignores its event,
 * so an implementation overrides only what it needs.
 *
 * The spans of all events together cover the input without gaps. The parser does not decode or validate
 * event content: that is the receiver's choice, through [TomlSource.get] and the [Raw] decoders.
 */
internal interface EventReceiver {
    /** `[` of a standard table header. */
    fun stdTableOpen(span: Span, error: ErrorSink) {}

    /** `]` of a standard table header. */
    fun stdTableClose(span: Span, error: ErrorSink) {}

    /** `[[` of an array-of-tables header. */
    fun arrayTableOpen(span: Span, error: ErrorSink) {}

    /** `]]` of an array-of-tables header. */
    fun arrayTableClose(span: Span, error: ErrorSink) {}

    /** `{` of an inline table. Returning false skips the table's content, as a nesting limit does. */
    fun inlineTableOpen(span: Span, error: ErrorSink): Boolean = true

    /** `}` of an inline table. */
    fun inlineTableClose(span: Span, error: ErrorSink) {}

    /** `[` of an array. Returning false skips the array's content, as a nesting limit does. */
    fun arrayOpen(span: Span, error: ErrorSink): Boolean = true

    /** `]` of an array. */
    fun arrayClose(span: Span, error: ErrorSink) {}

    /** One part of a key, written in [encoding] when quoted. The span is empty where a key is missing. */
    fun simpleKey(span: Span, encoding: Encoding?, error: ErrorSink) {}

    /** `.` between the parts of a dotted key. */
    fun keySep(span: Span, error: ErrorSink) {}

    /** `=` between a key and its value. */
    fun keyValSep(span: Span, error: ErrorSink) {}

    /** A scalar value, written in [encoding] when quoted. The span is empty where a value is missing. */
    fun scalar(span: Span, encoding: Encoding?, error: ErrorSink) {}

    /** `,` between values. */
    fun valueSep(span: Span, error: ErrorSink) {}

    /** A run of spaces and tabs. */
    fun whitespace(span: Span, error: ErrorSink) {}

    /** A comment. */
    fun comment(span: Span, error: ErrorSink) {}

    /** A newline. */
    fun newline(span: Span, error: ErrorSink) {}

    /** Input the parser skipped while recovering from an error it has reported. */
    fun error(span: Span, error: ErrorSink) {}

    companion object {
        /** A receiver that ignores every event. */
        val IGNORE: EventReceiver = object : EventReceiver {}
    }
}

/**
 * A receiver that turns each callback into an [Event] and hands it to [push], such as `events::add` to
 * collect them.
 */
internal fun eventReceiver(push: (Event) -> Unit): EventReceiver = object : EventReceiver {
    override fun stdTableOpen(span: Span, error: ErrorSink) = push(Event(EventKind.StdTableOpen, null, span))

    override fun stdTableClose(span: Span, error: ErrorSink) = push(Event(EventKind.StdTableClose, null, span))

    override fun arrayTableOpen(span: Span, error: ErrorSink) = push(Event(EventKind.ArrayTableOpen, null, span))

    override fun arrayTableClose(span: Span, error: ErrorSink) =
        push(Event(EventKind.ArrayTableClose, null, span))

    override fun inlineTableOpen(span: Span, error: ErrorSink): Boolean {
        push(Event(EventKind.InlineTableOpen, null, span))
        return true
    }

    override fun inlineTableClose(span: Span, error: ErrorSink) =
        push(Event(EventKind.InlineTableClose, null, span))

    override fun arrayOpen(span: Span, error: ErrorSink): Boolean {
        push(Event(EventKind.ArrayOpen, null, span))
        return true
    }

    override fun arrayClose(span: Span, error: ErrorSink) = push(Event(EventKind.ArrayClose, null, span))

    override fun simpleKey(span: Span, encoding: Encoding?, error: ErrorSink) =
        push(Event(EventKind.SimpleKey, encoding, span))

    override fun keySep(span: Span, error: ErrorSink) = push(Event(EventKind.KeySep, null, span))

    override fun keyValSep(span: Span, error: ErrorSink) = push(Event(EventKind.KeyValSep, null, span))

    override fun scalar(span: Span, encoding: Encoding?, error: ErrorSink) =
        push(Event(EventKind.Scalar, encoding, span))

    override fun valueSep(span: Span, error: ErrorSink) = push(Event(EventKind.ValueSep, null, span))

    override fun whitespace(span: Span, error: ErrorSink) = push(Event(EventKind.Whitespace, null, span))

    override fun comment(span: Span, error: ErrorSink) = push(Event(EventKind.Comment, null, span))

    override fun newline(span: Span, error: ErrorSink) = push(Event(EventKind.Newline, null, span))

    override fun error(span: Span, error: ErrorSink) = push(Event(EventKind.Error, null, span))
}

/**
 * Validates whitespace, comments and newlines as they pass, reporting what TOML forbids in them, and
 * forwards every event to [receiver] unchanged.
 *
 * Unquoted values, and one-line strings a line break cut off, are checked as they pass too. Values are otherwise
 * decoded only once the whole text is parsed, so the parser's errors for the text after such a value, such as the
 * rest of a line a cut string leaves to be read as keys, would come first.
 */
internal class ValidateWhitespace(
    private val receiver: EventReceiver,
    private val source: TomlSource,
) : EventReceiver {
    override fun stdTableOpen(span: Span, error: ErrorSink): Unit = receiver.stdTableOpen(span, error)

    override fun stdTableClose(span: Span, error: ErrorSink): Unit = receiver.stdTableClose(span, error)

    override fun arrayTableOpen(span: Span, error: ErrorSink): Unit = receiver.arrayTableOpen(span, error)

    override fun arrayTableClose(span: Span, error: ErrorSink): Unit = receiver.arrayTableClose(span, error)

    override fun inlineTableOpen(span: Span, error: ErrorSink): Boolean = receiver.inlineTableOpen(span, error)

    override fun inlineTableClose(span: Span, error: ErrorSink): Unit = receiver.inlineTableClose(span, error)

    override fun arrayOpen(span: Span, error: ErrorSink): Boolean = receiver.arrayOpen(span, error)

    override fun arrayClose(span: Span, error: ErrorSink): Unit = receiver.arrayClose(span, error)

    override fun simpleKey(span: Span, encoding: Encoding?, error: ErrorSink) {
        reportUnclosedString(span, encoding, error)
        receiver.simpleKey(span, encoding, error)
    }

    override fun keySep(span: Span, error: ErrorSink): Unit = receiver.keySep(span, error)

    override fun keyValSep(span: Span, error: ErrorSink): Unit = receiver.keyValSep(span, error)

    override fun scalar(span: Span, encoding: Encoding?, error: ErrorSink) {
        if (encoding == null) {
            source.get(span)?.decodeScalar(DecodedStringBuilder.DISCARD, error)
        } else {
            reportUnclosedString(span, encoding, error)
        }
        receiver.scalar(span, encoding, error)
    }

    /** Reports the one-line string at [span] when it has no closing quote, as decoding it would. */
    private fun reportUnclosedString(span: Span, encoding: Encoding?, error: ErrorSink) {
        val quote = when (encoding) {
            Encoding.BasicString -> QUOTATION_MARK
            Encoding.LiteralString -> APOSTROPHE
            else -> return
        }
        val length = span.end - span.start
        // An empty string is recovery standing in for a missing value, not a string that was cut off.
        if (length <= 0) return
        if (length >= 2 && source.bytes[span.end - 1].toInt() == quote) return
        error.reportError(
            ParseError(
                ParseErrorReason.InvalidString(encoding),
                context = span,
                expected = listOf(Expected.Literal(quote.toChar().toString())),
                unexpected = Span(span.end, span.end),
            ),
        )
    }

    override fun valueSep(span: Span, error: ErrorSink): Unit = receiver.valueSep(span, error)

    override fun whitespace(span: Span, error: ErrorSink): Unit = receiver.whitespace(span, error)

    override fun comment(span: Span, error: ErrorSink) {
        source.get(span)?.decodeComment(error)
        receiver.comment(span, error)
    }

    override fun newline(span: Span, error: ErrorSink) {
        source.get(span)?.decodeNewline(error)
        receiver.newline(span, error)
    }

    override fun error(span: Span, error: ErrorSink): Unit = receiver.error(span, error)
}

/**
 * Bounds how deeply arrays and inline tables may nest, forwarding every event to [receiver].
 *
 * Past [maxDepth] it reports `cannot recurse further; max recursion depth met` and refuses the open, so
 * the parser skips that value's content instead of descending into it.
 */
internal class RecursionGuard(
    private val receiver: EventReceiver,
    private val maxDepth: UInt,
) : EventReceiver {
    private var depth = 0L

    private val withinDepth: Boolean
        get() = depth <= maxDepth.toLong()

    override fun stdTableOpen(span: Span, error: ErrorSink): Unit = receiver.stdTableOpen(span, error)

    override fun stdTableClose(span: Span, error: ErrorSink): Unit = receiver.stdTableClose(span, error)

    override fun arrayTableOpen(span: Span, error: ErrorSink): Unit = receiver.arrayTableOpen(span, error)

    override fun arrayTableClose(span: Span, error: ErrorSink): Unit = receiver.arrayTableClose(span, error)

    override fun inlineTableOpen(span: Span, error: ErrorSink): Boolean {
        val allowed = receiver.inlineTableOpen(span, error)
        depth += 1
        val withinDepth = withinDepth
        if (allowed && !withinDepth) {
            error.reportError(ParseError(ParseErrorReason.RecursionLimit, unexpected = span))
        }
        return allowed && withinDepth
    }

    override fun inlineTableClose(span: Span, error: ErrorSink) {
        depth -= 1
        receiver.inlineTableClose(span, error)
    }

    override fun arrayOpen(span: Span, error: ErrorSink): Boolean {
        val allowed = receiver.arrayOpen(span, error)
        depth += 1
        val withinDepth = withinDepth
        if (allowed && !withinDepth) {
            error.reportError(ParseError(ParseErrorReason.RecursionLimit, unexpected = span))
        }
        return allowed && withinDepth
    }

    override fun arrayClose(span: Span, error: ErrorSink) {
        depth -= 1
        receiver.arrayClose(span, error)
    }

    override fun simpleKey(span: Span, encoding: Encoding?, error: ErrorSink): Unit =
        receiver.simpleKey(span, encoding, error)

    override fun keySep(span: Span, error: ErrorSink): Unit = receiver.keySep(span, error)

    override fun keyValSep(span: Span, error: ErrorSink): Unit = receiver.keyValSep(span, error)

    override fun scalar(span: Span, encoding: Encoding?, error: ErrorSink): Unit =
        receiver.scalar(span, encoding, error)

    override fun valueSep(span: Span, error: ErrorSink): Unit = receiver.valueSep(span, error)

    override fun whitespace(span: Span, error: ErrorSink): Unit = receiver.whitespace(span, error)

    override fun comment(span: Span, error: ErrorSink): Unit = receiver.comment(span, error)

    override fun newline(span: Span, error: ErrorSink): Unit = receiver.newline(span, error)

    override fun error(span: Span, error: ErrorSink): Unit = receiver.error(span, error)
}

/** One parse event: its [kind], the string [encoding] of a key or scalar, and the [span] it covers. */
internal data class Event(
    val kind: EventKind,
    val encoding: Encoding?,
    val span: Span,
)

/** The kinds of [Event]. */
internal enum class EventKind {
    /** `[` of a standard table header. */
    StdTableOpen,

    /** `]` of a standard table header. */
    StdTableClose,

    /** `[[` of an array-of-tables header. */
    ArrayTableOpen,

    /** `]]` of an array-of-tables header. */
    ArrayTableClose,

    /** `{` of an inline table. */
    InlineTableOpen,

    /** `}` of an inline table. */
    InlineTableClose,

    /** `[` of an array. */
    ArrayOpen,

    /** `]` of an array. */
    ArrayClose,

    /** One part of a key. */
    SimpleKey,

    /** `.` between the parts of a dotted key. */
    KeySep,

    /** `=` between a key and its value. */
    KeyValSep,

    /** A scalar value. */
    Scalar,

    /** `,` between values. */
    ValueSep,

    /** A run of spaces and tabs. */
    Whitespace,

    /** A comment. */
    Comment,

    /** A newline. */
    Newline,

    /** Input skipped while recovering from a reported error. */
    Error,
}
