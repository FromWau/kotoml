package com.fromwau.kotoml.parser

import kotlin.test.Test
import kotlin.test.assertTrue

class DecoderScalarTest {

    @Test
    fun manyRedundantSigns() {
        val signs = "-".repeat(5_000)
        val input = "${signs}1"

        val error = mutableListOf<ParseError>()
        val output = StringOutput()
        decodeUnquotedScalar(
            Raw.newUnchecked(input, null, Span.DEFAULT),
            output,
            ErrorSink { error += it },
        )
        assertTrue(error.isNotEmpty())
    }
}
