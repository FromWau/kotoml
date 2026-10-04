package com.fromwau.kotoml.parser

import com.fromwau.kotoml.assertSnapshot
import com.fromwau.kotoml.assertDebugSnapshot
import com.fromwau.kotoml.seq
import kotlin.test.Test
import kotlin.test.assertEquals

class LexerTest {

    private fun assertLexes(input: String, expected: String) {
        val source = TomlSource(input)
        val actual = source.lex().toList()
        assertDebugSnapshot(expected, seq(actual.map { it.dbg() }))

        if (actual.isNotEmpty()) {
            val spans = actual.map { it.span }
            assertEquals(0, spans.first().start)
            assertEquals(input.encodeToByteArray().size, spans.last().end)
            for (i in 0 until spans.size - 1) {
                assertEquals(spans[i].end, spans[i + 1].start)
            }
        }
    }

    @Test
    fun `lexing one ASCII character emits its token and leaves the rest`() {
        val cases = listOf(
            Triple(
                ".trailing",
                """
Token {
    kind: Dot,
    span: 0..1,
}

""",
                "trailing",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexAsciiChar(TokenKind.Dot)
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `whitespace lexes as one token up to the first non-blank character`() {
        val cases = listOf(
            Triple(
                " ",
                """
Token {
    kind: Whitespace,
    span: 0..1,
}

""",
                "",
            ),
            Triple(
                " \t  \t  \t ",
                """
Token {
    kind: Whitespace,
    span: 0..9,
}

""",
                "",
            ),
            Triple(
                """ 
""",
                """
Token {
    kind: Whitespace,
    span: 0..1,
}

""",
                """


""",
            ),
            Triple(
                " #",
                """
Token {
    kind: Whitespace,
    span: 0..1,
}

""",
                "#",
            ),
            Triple(
                " a",
                """
Token {
    kind: Whitespace,
    span: 0..1,
}

""",
                "a",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexWhitespace()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `a comment lexes up to the line ending and keeps control characters in it`() {
        val cases = listOf(
            Triple(
                "#",
                """
Token {
    kind: Comment,
    span: 0..1,
}

""",
                "",
            ),
            Triple(
                "# content",
                """
Token {
    kind: Comment,
    span: 0..9,
}

""",
                "",
            ),
            Triple(
                """# content 
trailing""",
                """
Token {
    kind: Comment,
    span: 0..10,
}

""",
                """

trailing
""",
            ),
            Triple(
                "# content \r\ntrailing",
                """
Token {
    kind: Comment,
    span: 0..10,
}

""",
                """

trailing
""",
            ),
            Triple(
                "# content \u0000continue",
                """
Token {
    kind: Comment,
    span: 0..19,
}

""",
                "",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexComment()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `CRLF and a lone carriage return each lex as one newline`() {
        val cases = listOf(
            Triple(
                "\r\ntrailing",
                """
Token {
    kind: Newline,
    span: 0..2,
}

""",
                "trailing",
            ),
            Triple(
                "\rtrailing",
                """
Token {
    kind: Newline,
    span: 0..1,
}

""",
                "trailing",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexCrlf()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `a literal string lexes to its closing quote or the end of input`() {
        val cases = listOf(
            Triple(
                "''",
                """
Token {
    kind: LiteralString,
    span: 0..2,
}

""",
                "",
            ),
            Triple(
                "''trailing",
                """
Token {
    kind: LiteralString,
    span: 0..2,
}

""",
                "trailing",
            ),
            Triple(
                "'content'trailing",
                """
Token {
    kind: LiteralString,
    span: 0..9,
}

""",
                "trailing",
            ),
            Triple(
                "'content",
                """
Token {
    kind: LiteralString,
    span: 0..8,
}

""",
                "",
            ),
            Triple(
                """'content
trailing""",
                """
Token {
    kind: LiteralString,
    span: 0..8,
}

""",
                """

trailing
""",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexLiteralString()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `a multi-line literal string takes up to two extra quotes before its closing delimiter`() {
        val cases = listOf(
            Triple(
                "''''''",
                """
Token {
    kind: MlLiteralString,
    span: 0..6,
}

""",
                "",
            ),
            Triple(
                "''''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..6,
}

""",
                "trailing",
            ),
            Triple(
                "'''content'''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..13,
}

""",
                "trailing",
            ),
            Triple(
                "'''content",
                """
Token {
    kind: MlLiteralString,
    span: 0..10,
}

""",
                "",
            ),
            Triple(
                "'''content'",
                """
Token {
    kind: MlLiteralString,
    span: 0..11,
}

""",
                "",
            ),
            Triple(
                "'''content''",
                """
Token {
    kind: MlLiteralString,
    span: 0..12,
}

""",
                "",
            ),
            Triple(
                """'''content
trailing""",
                """
Token {
    kind: MlLiteralString,
    span: 0..19,
}

""",
                "",
            ),
            Triple(
                "'''''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..7,
}

""",
                "trailing",
            ),
            Triple(
                "''''''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..8,
}

""",
                "trailing",
            ),
            Triple(
                "'''''''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..8,
}

""",
                "'trailing",
            ),
            Triple(
                "'''''content''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..16,
}

""",
                "trailing",
            ),
            Triple(
                "'''''content'''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..17,
}

""",
                "trailing",
            ),
            Triple(
                "'''''content''''''trailing",
                """
Token {
    kind: MlLiteralString,
    span: 0..17,
}

""",
                "'trailing",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexMlLiteralString()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `a basic string lexes past escaped quotes to its closing quote`() {
        val cases = listOf(
            Triple(
                "\"\"",
                """
Token {
    kind: BasicString,
    span: 0..2,
}

""",
                "",
            ),
            Triple(
                "\"\"trailing",
                """
Token {
    kind: BasicString,
    span: 0..2,
}

""",
                "trailing",
            ),
            Triple(
                "\"content\"trailing",
                """
Token {
    kind: BasicString,
    span: 0..9,
}

""",
                "trailing",
            ),
            Triple(
                "\"content",
                """
Token {
    kind: BasicString,
    span: 0..8,
}

""",
                "",
            ),
            Triple(
                "\"content\\ntrailing",
                """
Token {
    kind: BasicString,
    span: 0..18,
}

""",
                "",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexBasicString()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `an atom lexes up to whitespace or a dot`() {
        val cases = listOf(
            Triple(
                "hello",
                """
Token {
    kind: Atom,
    span: 0..5,
}

""",
                "",
            ),
            Triple(
                "hello = world",
                """
Token {
    kind: Atom,
    span: 0..5,
}

""",
                " = world",
            ),
            Triple(
                "1.100e100 ]",
                """
Token {
    kind: Atom,
    span: 0..1,
}

""",
                ".100e100 ]",
            ),
            Triple(
                "a.b.c = 5",
                """
Token {
    kind: Atom,
    span: 0..1,
}

""",
                ".b.c = 5",
            ),
            Triple(
                "true ]",
                """
Token {
    kind: Atom,
    span: 0..4,
}

""",
                " ]",
            ),
        )
        for ((input, expectedTokens, expectedStream) in cases) {
            val stream = LexStream(input.encodeToByteArray())
            val actualTokens = stream.lexAtom()
            assertDebugSnapshot(expectedTokens, actualTokens.dbg())
            assertSnapshot(expectedStream, stream.rest())
        }
    }

    @Test
    fun `literal strings of every quote count lex into string tokens`() {
        assertLexes(
            "''",
            """
[
    Token {
        kind: LiteralString,
        span: 0..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
        assertLexes(
            "''''''",
            """
[
    Token {
        kind: MlLiteralString,
        span: 0..6,
    },
    Token {
        kind: Eof,
        span: 6..6,
    },
]

""",
        )
        assertLexes(
            """'''
'''""",
            """
[
    Token {
        kind: MlLiteralString,
        span: 0..7,
    },
    Token {
        kind: Eof,
        span: 7..7,
    },
]

""",
        )
        assertLexes(
            "'a'",
            """
[
    Token {
        kind: LiteralString,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "'\"a'",
            """
[
    Token {
        kind: LiteralString,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "''''a'''",
            """
[
    Token {
        kind: MlLiteralString,
        span: 0..8,
    },
    Token {
        kind: Eof,
        span: 8..8,
    },
]

""",
        )
        assertLexes(
            """'''
'a
'''""",
            """
[
    Token {
        kind: MlLiteralString,
        span: 0..10,
    },
    Token {
        kind: Eof,
        span: 10..10,
    },
]

""",
        )
        assertLexes(
            "'''a\n'a\r\n'''",
            """
[
    Token {
        kind: MlLiteralString,
        span: 0..12,
    },
    Token {
        kind: Eof,
        span: 12..12,
    },
]

""",
        )
    }

    @Test
    fun `basic strings of every quote count and escape lex into string tokens`() {
        assertLexes(
            "\"\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
        assertLexes(
            "\"\"\"\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..6,
    },
    Token {
        kind: Eof,
        span: 6..6,
    },
]

""",
        )
        assertLexes(
            "\"a\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "\"\"\"a\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..7,
    },
    Token {
        kind: Eof,
        span: 7..7,
    },
]

""",
        )
        assertLexes(
            "\"\\t\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "\"\\u0000\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..8,
    },
    Token {
        kind: Eof,
        span: 8..8,
    },
]

""",
        )
        assertLexes(
            "\"\\U00000000\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..12,
    },
    Token {
        kind: Eof,
        span: 12..12,
    },
]

""",
        )
        assertLexes(
            "\"\\U000A0000\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..12,
    },
    Token {
        kind: Eof,
        span: 12..12,
    },
]

""",
        )
        assertLexes(
            "\"\\\\t\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..5,
    },
    Token {
        kind: Eof,
        span: 5..5,
    },
]

""",
        )
        assertLexes(
            "\"\t\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "\"\"\"\n\t\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..8,
    },
    Token {
        kind: Eof,
        span: 8..8,
    },
]

""",
        )
        assertLexes(
            "\"\"\"\\\n\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..8,
    },
    Token {
        kind: Eof,
        span: 8..8,
    },
]

""",
        )
        assertLexes(
            "\"\"\"\\\n     \t   \t  \\\r\n  \t \n  \t \r\n\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..34,
    },
    Token {
        kind: Eof,
        span: 34..34,
    },
]

""",
        )
        assertLexes(
            "\"\\r\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "\"\\n\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "\"\\b\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "\"a\\fa\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..6,
    },
    Token {
        kind: Eof,
        span: 6..6,
    },
]

""",
        )
        assertLexes(
            "\"\\\"a\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..5,
    },
    Token {
        kind: Eof,
        span: 5..5,
    },
]

""",
        )
        assertLexes(
            "\"\"\"\na\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..8,
    },
    Token {
        kind: Eof,
        span: 8..8,
    },
]

""",
        )
        assertLexes(
            "\"\"\"\n\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..7,
    },
    Token {
        kind: Eof,
        span: 7..7,
    },
]

""",
        )
        assertLexes(
            "\"\"\"a\\\"\"\"b\"\"\"",
            """
[
    Token {
        kind: MlBasicString,
        span: 0..12,
    },
    Token {
        kind: Eof,
        span: 12..12,
    },
]

""",
        )
        assertLexes(
            "\"\\a",
            """
[
    Token {
        kind: BasicString,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "\"\\\n",
            """
[
    Token {
        kind: BasicString,
        span: 0..2,
    },
    Token {
        kind: Newline,
        span: 2..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "\"\\\r\n",
            """
[
    Token {
        kind: BasicString,
        span: 0..3,
    },
    Token {
        kind: Newline,
        span: 3..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "\"\\",
            """
[
    Token {
        kind: BasicString,
        span: 0..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
        assertLexes(
            "\"\u0000",
            """
[
    Token {
        kind: BasicString,
        span: 0..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
        assertLexes(
            "\"\\U00\"",
            """
[
    Token {
        kind: BasicString,
        span: 0..6,
    },
    Token {
        kind: Eof,
        span: 6..6,
    },
]

""",
        )
        assertLexes(
            "\"\\U00",
            """
[
    Token {
        kind: BasicString,
        span: 0..5,
    },
    Token {
        kind: Eof,
        span: 5..5,
    },
]

""",
        )
        assertLexes(
            "\"\\uD800",
            """
[
    Token {
        kind: BasicString,
        span: 0..7,
    },
    Token {
        kind: Eof,
        span: 7..7,
    },
]

""",
        )
        assertLexes(
            "\"\\UFFFFFFFF",
            """
[
    Token {
        kind: BasicString,
        span: 0..11,
    },
    Token {
        kind: Eof,
        span: 11..11,
    },
]

""",
        )
    }

    @Test
    fun `keylike text of letters digits dashes and underscores lexes as one atom`() {
        assertLexes(
            "foo",
            """
[
    Token {
        kind: Atom,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "0bar",
            """
[
    Token {
        kind: Atom,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "bar0",
            """
[
    Token {
        kind: Atom,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "1234",
            """
[
    Token {
        kind: Atom,
        span: 0..4,
    },
    Token {
        kind: Eof,
        span: 4..4,
    },
]

""",
        )
        assertLexes(
            "a-b",
            """
[
    Token {
        kind: Atom,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "a_B",
            """
[
    Token {
        kind: Atom,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "-_-",
            """
[
    Token {
        kind: Atom,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            "___",
            """
[
    Token {
        kind: Atom,
        span: 0..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
    }

    @Test
    fun `a line using every token kind lexes into each kind with contiguous spans`() {
        assertLexes(
            " a ",
            """
[
    Token {
        kind: Whitespace,
        span: 0..1,
    },
    Token {
        kind: Atom,
        span: 1..2,
    },
    Token {
        kind: Whitespace,
        span: 2..3,
    },
    Token {
        kind: Eof,
        span: 3..3,
    },
]

""",
        )
        assertLexes(
            " a\t [[]] \t [] {} , . =\n# foo \r\n#foo \n ",
            """
[
    Token {
        kind: Whitespace,
        span: 0..1,
    },
    Token {
        kind: Atom,
        span: 1..2,
    },
    Token {
        kind: Whitespace,
        span: 2..4,
    },
    Token {
        kind: LeftSquareBracket,
        span: 4..5,
    },
    Token {
        kind: LeftSquareBracket,
        span: 5..6,
    },
    Token {
        kind: RightSquareBracket,
        span: 6..7,
    },
    Token {
        kind: RightSquareBracket,
        span: 7..8,
    },
    Token {
        kind: Whitespace,
        span: 8..11,
    },
    Token {
        kind: LeftSquareBracket,
        span: 11..12,
    },
    Token {
        kind: RightSquareBracket,
        span: 12..13,
    },
    Token {
        kind: Whitespace,
        span: 13..14,
    },
    Token {
        kind: LeftCurlyBracket,
        span: 14..15,
    },
    Token {
        kind: RightCurlyBracket,
        span: 15..16,
    },
    Token {
        kind: Whitespace,
        span: 16..17,
    },
    Token {
        kind: Comma,
        span: 17..18,
    },
    Token {
        kind: Whitespace,
        span: 18..19,
    },
    Token {
        kind: Dot,
        span: 19..20,
    },
    Token {
        kind: Whitespace,
        span: 20..21,
    },
    Token {
        kind: Equals,
        span: 21..22,
    },
    Token {
        kind: Newline,
        span: 22..23,
    },
    Token {
        kind: Comment,
        span: 23..29,
    },
    Token {
        kind: Newline,
        span: 29..31,
    },
    Token {
        kind: Comment,
        span: 31..36,
    },
    Token {
        kind: Newline,
        span: 36..37,
    },
    Token {
        kind: Whitespace,
        span: 37..38,
    },
    Token {
        kind: Eof,
        span: 38..38,
    },
]

""",
        )
    }

    @Test
    fun `a bare carriage return or an unterminated string lexes into tokens covering every byte`() {
        assertLexes(
            "\r",
            """
[
    Token {
        kind: Newline,
        span: 0..1,
    },
    Token {
        kind: Eof,
        span: 1..1,
    },
]

""",
        )
        assertLexes(
            """'
""",
            """
[
    Token {
        kind: LiteralString,
        span: 0..1,
    },
    Token {
        kind: Newline,
        span: 1..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
        assertLexes(
            "'\u0000",
            """
[
    Token {
        kind: LiteralString,
        span: 0..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
        assertLexes(
            "'",
            """
[
    Token {
        kind: LiteralString,
        span: 0..1,
    },
    Token {
        kind: Eof,
        span: 1..1,
    },
]

""",
        )
        assertLexes(
            "\u0000",
            """
[
    Token {
        kind: Atom,
        span: 0..1,
    },
    Token {
        kind: Eof,
        span: 1..1,
    },
]

""",
        )
    }

    @Test
    fun `a NUL inside a comment stays part of the comment token`() {
        assertLexes(
            "#\u0000",
            """
[
    Token {
        kind: Comment,
        span: 0..2,
    },
    Token {
        kind: Eof,
        span: 2..2,
    },
]

""",
        )
    }
}
