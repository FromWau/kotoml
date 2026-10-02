package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Expected
import com.fromwau.kotoml.ParseErrorReason
import com.fromwau.kotoml.Span
import com.fromwau.kotoml.plus

/**
 * Where the lexer, parser and decoders report what they found wrong.
 *
 * Reporting never stops parsing: the parser recovers and keeps going, so a sink may receive many errors
 * for one input. Pass a lambda to collect them, `ErrorSink { errors += it }`, or [ErrorSink.IGNORE] to
 * drop them.
 */
internal fun interface ErrorSink {
    /** Receives one error, in the order they are found. */
    fun reportError(error: ParseError)

    companion object {
        /** A sink that drops every error. */
        val IGNORE: ErrorSink = ErrorSink { }
    }
}

/**
 * Keeps the first error reported and drops the rest, as upstream's `Option<ParseError>` sink does.
 */
internal class FirstErrorSink : ErrorSink {
    private var first: ParseError? = null

    /** The first error reported, or null when none was. */
    fun error(): ParseError? = first

    override fun reportError(error: ParseError) {
        if (first == null) first = error
    }
}

/**
 * One problem found in TOML text.
 *
 * [unexpected] points at the offending bytes, [context] at the surrounding construct, and [expected] lists
 * what would have been accepted instead; an empty list means nothing would. Spans are byte offsets into the
 * input the error was reported against.
 */
internal data class ParseError(
    val reason: ParseErrorReason,
    val unexpected: Span? = null,
    val context: Span? = null,
    val expected: List<Expected>? = null,
) {
    internal fun rebaseSpans(offset: Int): ParseError = copy(
        unexpected = unexpected?.plus(offset),
        context = context?.plus(offset),
    )
}
