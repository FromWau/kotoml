package com.fromwau.kotoml.parser

import com.fromwau.kotoml.DEFAULT
import com.fromwau.kotoml.Span
import kotlin.test.Test
import kotlin.test.assertTrue

class DecoderScalarTest {

    @Test
    fun `thousands of leading signs on a number report an error without overflowing`() {
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
