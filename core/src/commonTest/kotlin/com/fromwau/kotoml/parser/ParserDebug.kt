package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Dbg
import com.fromwau.kotoml.Expected
import com.fromwau.kotoml.Span
import com.fromwau.kotoml.describe
import com.fromwau.kotoml.lit
import com.fromwau.kotoml.option
import com.fromwau.kotoml.seq
import com.fromwau.kotoml.str
import com.fromwau.kotoml.struct
import com.fromwau.kotoml.tuple

internal fun Span.dbg(): Dbg = lit("$start..$end")

internal fun Token.dbg(): Dbg = struct(
    "Token",
    "kind" to lit(kind().name),
    "span" to span().dbg(),
)

internal fun Event.dbg(): Dbg = struct(
    "Event",
    "kind" to lit(kind().name),
    "encoding" to option(encoding()) { lit(it.name) },
    "span" to span().dbg(),
)

internal fun Expected.dbg(): Dbg = when (this) {
    is Expected.Literal -> tuple("Literal", str(literal))
    is Expected.Token -> tuple("Description", str(token.describe()))
}

internal fun ParseError.dbg(): Dbg = parseErrorDbg(context, reason.describe(), expected, unexpected)

/** Upstream's `{:#?}` of a `ParseError`, whose description is text where the port's is a typed reason. */
internal fun parseErrorDbg(
    context: Span?,
    description: String,
    expected: List<Expected>?,
    unexpected: Span?,
): Dbg = struct(
    "ParseError",
    "context" to option(context) { it.dbg() },
    "description" to str(description),
    "expected" to option(expected) { expected -> seq(expected.map { it.dbg() }) },
    "unexpected" to option(unexpected) { it.dbg() },
)

internal fun List<ParseError>.dbg(): Dbg = seq(map { it.dbg() })
