package com.fromwau.kotoml.parser

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.Dbg
import com.fromwau.kotoml.ScalarKind
import com.fromwau.kotoml.Span
import com.fromwau.kotoml.assertFileData
import com.fromwau.kotoml.parseRustF64
import com.fromwau.kotoml.parseRustI64
import com.fromwau.kotoml.pretty
import com.fromwau.kotoml.readSnapshot
import com.fromwau.kotoml.seq
import com.fromwau.kotoml.str
import com.fromwau.kotoml.struct
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The parser testsuite's harness: events and errors of one parse, checked against a snapshot file. */
internal class EventResults(val input: String) {
    val events = mutableListOf<Event>()
    val errors = mutableListOf<Dbg>()
    val receiver: EventReceiver = eventReceiver { events += it }
    val sink: ErrorSink = ErrorSink { errors += it.dbg() }

    fun validate(snapshot: String) {
        decodeEvents()
        assertFileData(readSnapshot("toml_parser/$snapshot.txt"), debug())

        if (events.isNotEmpty()) {
            val spans = events.map { it.span() }
            if (!input.startsWith('﻿')) {
                assertEquals(0, spans.first().start, "first span needs to start at 0")
            }
            assertEquals(input.encodeToByteArray().size, spans.last().end, "last span needs to be at the end")
            for (i in 0 until spans.size - 1) {
                assertEquals(spans[i].end, spans[i + 1].start, "events must not have gaps in spans")
            }
        }
        if (events.any { it.kind() == EventKind.Error }) {
            assertTrue(errors.isNotEmpty())
        }
    }

    /** Decodes every key, comment, newline and scalar, adding what they report to [errors]. */
    fun decodeEvents() {
        val doc = TomlSource(input)
        for (event in events) {
            val raw = doc.get(event) ?: error("event $event does not slice the input")
            when (event.kind()) {
                EventKind.SimpleKey -> raw.decodeKey(DecodedStringBuilder.DISCARD, sink)
                EventKind.Comment -> raw.decodeComment(sink)
                EventKind.Newline -> raw.decodeNewline(sink)
                EventKind.Scalar -> checkScalar(raw)
                else -> {}
            }
        }
    }

    /** Upstream's `{:#?}` of `EventResults`, with the trailing newline `to_debug` adds. */
    fun debug(): String = struct(
        "EventResults",
        "input" to str(input),
        "events" to seq(events.map { it.dbg() }),
        "errors" to seq(errors),
    ).pretty() + "\n"

    private fun checkScalar(raw: Raw) {
        val value = StringOutput()
        val kind = raw.decodeScalar(value, sink)
        val text = value.toString()
        val failure = when (kind) {
            ScalarKind.String -> null
            is ScalarKind.Boolean -> when (text) {
                "true", "false" -> if ((text == "true") != kind.value) "mismatched bool value" else null
                else -> "failed to parse bool"
            }
            ScalarKind.DateTime -> if (Datetime.parse(text) is Result.Error) "failed to parse datetime" else null
            ScalarKind.Float -> if (parseRustF64(text) == null) "failed to parse f64" else null
            is ScalarKind.Integer -> if (parseRustI64(text, kind.radix.value()) == null) "failed to parse i64" else null
        }
        if (failure != null) {
            errors += parseErrorDbg(
                context = Span(0, raw.len()),
                description = failure,
                expected = null,
                unexpected = Span(0, 2),
            )
        }
    }
}
