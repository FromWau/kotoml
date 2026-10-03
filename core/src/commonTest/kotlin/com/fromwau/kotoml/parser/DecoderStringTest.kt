package com.fromwau.kotoml.parser

import com.fromwau.kotoml.DEFAULT
import com.fromwau.kotoml.Encoding
import com.fromwau.kotoml.Span
import com.fromwau.kotoml.assertData
import com.fromwau.kotoml.assertDebug
import kotlin.test.Test

class DecoderStringTest {

    @Test
    fun `a literal string decodes to its content with backslashes kept`() {
        val cases = listOf(
            Triple(
                "'C:\\Users\\nodejs\\templates'",
                "C:\\Users\\nodejs\\templates",
                """
[]

""",
            ),
            Triple(
                "'\\\\ServerX\\admin\$\\system32\\'",
                "\\\\ServerX\\admin\$\\system32\\",
                """
[]

""",
            ),
            Triple(
                "'Tom \"Dubs\" Preston-Werner'",
                "Tom \"Dubs\" Preston-Werner",
                """
[]

""",
            ),
            Triple(
                "'<\\i\\c*\\s*>'",
                "<\\i\\c*\\s*>",
                """
[]

""",
            ),
        )
        for ((input, expected, expectedError) in cases) {
            val error = mutableListOf<ParseError>()
            val actual = StringOutput()
            decodeLiteralString(
                Raw.newUnchecked(input, Encoding.LiteralString, Span.DEFAULT),
                actual,
                ErrorSink { error += it },
            )
            assertData(expected, actual.toString())
            assertDebug(expectedError, error.dbg())
        }
    }

    @Test
    fun `a multi-line literal string drops its first newline and keeps inner quotes`() {
        val cases = listOf(
            Triple(
                "'''I [dw]on't need \\d{2} apples'''",
                "I [dw]on't need \\d{2} apples",
                """
[]

""",
            ),
            Triple(
                "''''one_quote''''",
                "'one_quote'",
                """
[]

""",
            ),
            Triple(
                """'''
The first newline is
trimmed in raw strings.
   All other whitespace
   is preserved.
'''""",
                """
The first newline is
trimmed in raw strings.
   All other whitespace
   is preserved.

""",
                """
[]

""",
            ),
        )
        for ((input, expected, expectedError) in cases) {
            val error = mutableListOf<ParseError>()
            val actual = StringOutput()
            decodeMlLiteralString(
                Raw.newUnchecked(input, Encoding.MlLiteralString, Span.DEFAULT),
                actual,
                ErrorSink { error += it },
            )
            assertData(expected, actual.toString())
            assertDebug(expectedError, error.dbg())
        }
    }

    @Test
    fun `a basic string decodes its escapes and reports a bad escape or a raw newline`() {
        val cases = listOf(
            Triple(
                "\"\"",
                "",
                """
[]

""",
            ),
            Triple(
                "\"content\\\"trailing\"",
                "content\"trailing",
                """
[]

""",
            ),
            Triple(
                "\"content\\\"",
                "content\\",
                """
[
    ParseError {
        context: Some(
            0..10,
        ),
        description: "missing escaped value",
        expected: Some(
            [
                Literal(
                    "b",
                ),
                Literal(
                    "e",
                ),
                Literal(
                    "f",
                ),
                Literal(
                    "n",
                ),
                Literal(
                    "r",
                ),
                Literal(
                    "t",
                ),
                Literal(
                    "\\",
                ),
                Literal(
                    "\"",
                ),
                Literal(
                    "x",
                ),
                Literal(
                    "u",
                ),
                Literal(
                    "U",
                ),
            ],
        ),
        unexpected: Some(
            9..9,
        ),
    },
]

""",
            ),
            Triple(
                "\"content\ntrailing\"",
                """
content
trailing
""",
                """
[
    ParseError {
        context: Some(
            0..18,
        ),
        description: "invalid basic string",
        expected: Some(
            [
                Description(
                    "non-double-quote visible characters",
                ),
                Literal(
                    "\\",
                ),
            ],
        ),
        unexpected: Some(
            8..9,
        ),
    },
]

""",
            ),
            Triple(
                "\"I'm a string. \\\"You can quote me\\\". Name\\tJos\\u00E9\\nLocation\\tSF. \\U0002070E\"",
                "\nI'm a string. \"You can quote me\". Name\tJosé\nLocation\tSF. \uD841\uDF0E\n",
                """
[]

""",
            ),
        )
        for ((input, expected, expectedError) in cases) {
            val error = mutableListOf<ParseError>()
            val actual = StringOutput()
            decodeBasicString(
                Raw.newUnchecked(input, Encoding.BasicString, Span.DEFAULT),
                actual,
                ErrorSink { error += it },
            )
            assertData(expected, actual.toString())
            assertDebug(expectedError, error.dbg())
        }
    }

    @Test
    fun `a multi-line basic string joins lines ending in a backslash and reports bad escapes`() {
        val cases = listOf(
            Triple(
                "\"\"\"\nRoses are red\nViolets are blue\"\"\"",
                """
Roses are red
Violets are blue
""",
                """
[]

""",
            ),
            Triple(
                "\"\"\" \\\"\"\" \"\"\"",
                " \"\"\" ",
                """
[]

""",
            ),
            Triple(
                "\"\"\" \\\\\"\"\"",
                " \\",
                """
[]

""",
            ),
            Triple(
                "\"\"\"\nThe quick brown \\\n\n\n  fox jumps over \\\n    the lazy dog.\"\"\"",
                "The quick brown fox jumps over the lazy dog.",
                """
[]

""",
            ),
            Triple(
                "\"\"\"\\\n       The quick brown \\\n       fox jumps over \\\n       the lazy dog.\\\n       \"\"\"",
                "The quick brown fox jumps over the lazy dog.",
                """
[]

""",
            ),
            Triple(
                "\"\"\"\\\n       \"\"\"",
                "",
                """
[]

""",
            ),
            Triple(
                "\"\"\"\n\\\n  \\\n\"\"\"",
                "",
                """
[]

""",
            ),
            Triple(
                "\"\"\"  \"\"",
                "  \"\"",
                """
[
    ParseError {
        context: Some(
            0..7,
        ),
        description: "invalid multi-line basic string",
        expected: Some(
            [
                Literal(
                    "\"",
                ),
            ],
        ),
        unexpected: Some(
            7..7,
        ),
    },
]

""",
            ),
            Triple(
                "\"\"\"  \\\"\"\"",
                "  \\",
                """
[
    ParseError {
        context: Some(
            0..9,
        ),
        description: "missing escaped value",
        expected: Some(
            [
                Literal(
                    "b",
                ),
                Literal(
                    "e",
                ),
                Literal(
                    "f",
                ),
                Literal(
                    "n",
                ),
                Literal(
                    "r",
                ),
                Literal(
                    "t",
                ),
                Literal(
                    "\\",
                ),
                Literal(
                    "\"",
                ),
                Literal(
                    "x",
                ),
                Literal(
                    "u",
                ),
                Literal(
                    "U",
                ),
            ],
        ),
        unexpected: Some(
            6..6,
        ),
    },
]

""",
            ),
        )
        for ((input, expected, expectedError) in cases) {
            val error = mutableListOf<ParseError>()
            val actual = StringOutput()
            decodeMlBasicString(
                Raw.newUnchecked(input, Encoding.MlBasicString, Span.DEFAULT),
                actual,
                ErrorSink { error += it },
            )
            assertData(expected, actual.toString())
            assertDebug(expectedError, error.dbg())
        }
    }

    @Test
    fun `an unquoted key decodes to its own text and an empty one is reported`() {
        val cases = listOf(
            Triple(
                "a",
                "a",
                """
[]

""",
            ),
            Triple(
                "hello",
                "hello",
                """
[]

""",
            ),
            Triple(
                "-",
                "-",
                """
[]

""",
            ),
            Triple(
                "_",
                "_",
                """
[]

""",
            ),
            Triple(
                "-hello-world-",
                "-hello-world-",
                """
[]

""",
            ),
            Triple(
                "_hello_world_",
                "_hello_world_",
                """
[]

""",
            ),
            Triple(
                "",
                "",
                """
[
    ParseError {
        context: Some(
            0..0,
        ),
        description: "unquoted keys cannot be empty",
        expected: Some(
            [
                Description(
                    "letters",
                ),
                Description(
                    "numbers",
                ),
                Literal(
                    "-",
                ),
                Literal(
                    "_",
                ),
            ],
        ),
        unexpected: Some(
            0..0,
        ),
    },
]

""",
            ),
        )
        for ((input, expected, expectedError) in cases) {
            val error = mutableListOf<ParseError>()
            val actual = StringOutput()
            decodeUnquotedKey(
                Raw.newUnchecked(input, null, Span.DEFAULT),
                actual,
                ErrorSink { error += it },
            )
            assertData(expected, actual.toString())
            assertDebug(expectedError, error.dbg())
        }
    }
}
