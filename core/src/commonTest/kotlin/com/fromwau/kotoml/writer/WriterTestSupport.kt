package com.fromwau.kotoml.writer

import com.fromwau.kotoml.codePointToString
import com.fromwau.kotoml.parser.ErrorSink
import com.fromwau.kotoml.parser.Event
import com.fromwau.kotoml.parser.EventKind
import com.fromwau.kotoml.parser.ParseError
import com.fromwau.kotoml.parser.Source
import com.fromwau.kotoml.parser.StringOutput
import com.fromwau.kotoml.parser.eventReceiver
import com.fromwau.kotoml.parser.parseDocument
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upstream checks its default encodings against an older `toml` release so that new Cargo stays readable by
 * old Cargo. That crate has no Kotlin form; this parses `key = value` with this port's parser instead, which
 * holds the encodings to the same grammar.
 */
internal fun assertParsesBack(key: String, value: String, decoded: String) {
    val toml = "$key = $value"
    val source = Source(toml)
    val events = mutableListOf<Event>()
    val errors = mutableListOf<ParseError>()
    val sink = ErrorSink { errors += it }
    parseDocument(source.lex().intoVec(), eventReceiver { events += it }, sink)

    val keys = events.filter { it.kind() == EventKind.SimpleKey }
    val scalars = events.filter { it.kind() == EventKind.Scalar }
    assertEquals(1, keys.size, "could not parse: $toml")
    assertEquals(1, scalars.size, "could not parse: $toml")

    val decodedKey = StringOutput()
    source.get(keys.single())?.decodeKey(decodedKey, sink)
    val decodedValue = StringOutput()
    source.get(scalars.single())?.decodeScalar(decodedValue, sink)

    assertTrue(errors.isEmpty(), "could not parse: $toml: $errors")
    assertEquals(decoded, decodedKey.toString())
    assertEquals(decoded, decodedValue.toString())
}

/** proptest's `\PC*`: any number of characters outside the Unicode "Other" categories. */
internal fun randomPrintableString(random: Random): String = buildString {
    repeat(random.nextInt(0, 24)) {
        while (true) {
            val codePoint = when (random.nextInt(4)) {
                0 -> random.nextInt(0x20, 0x7F)
                1 -> random.nextInt(0xA0, 0x800)
                2 -> random.nextInt(0x800, 0x10000)
                else -> random.nextInt(0x10000, 0x30000)
            }
            if (codePoint > 0xFFFF) {
                // Above the BMP Kotlin has no category data; the supplementary planes sampled here are assigned
                // letters and symbols often enough, and any code point exercises the encoder the same way.
                append(codePointToString(codePoint))
                break
            }
            val category = codePoint.toChar().category
            if (category != CharCategory.CONTROL &&
                category != CharCategory.FORMAT &&
                category != CharCategory.SURROGATE &&
                category != CharCategory.PRIVATE_USE &&
                category != CharCategory.UNASSIGNED
            ) {
                append(codePoint.toChar())
                break
            }
        }
    }
}
