package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** `toml_edit/tests/compliance/invalid.rs` */
class ComplianceInvalidTest {

    private fun t(toml: String, expected: String) {
        when (val parsed = DocumentMut.parse(toml)) {
            is Result.Success -> fail("parsed to: ${parsed.value}")
            is Result.Error -> assertData(expected, parsed.error.render(toml))
        }
    }

    /** The input under an error's span, sliced by bytes as Rust slices a `str`. */
    private fun spanText(input: String, error: TomlParseError): String {
        val span = error.span!!
        return input.encodeToByteArray().decodeToString(span.start, span.end)
    }

    @Test
    fun basicStringEscape() {
        t(
            "a = \"\u007F\"",
            "\nTOML parse error at line 1, column 6\n  |\n1 | a = \"\u007F\"\n  |      ^\n" +
                "invalid basic string, expected non-double-quote visible characters, `\\`\n\n",
        )
    }

    @Test
    fun literalEscape() {
        t(
            "a = '\u007F'",
            "\nTOML parse error at line 1, column 6\n  |\n1 | a = '\u007F'\n  |      ^\n" +
                "invalid literal string, expected non-single-quote visible characters\n\n",
        )
    }

    @Test
    fun strayCr() {
        t(
            "\r",
            "\nTOML parse error at line 1, column 2\n  |\n1 | \n  |  ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        t(
            "a = [ \r ]",
            "\nTOML parse error at line 1, column 8\n  |\n1 | a = [ \n ]\n  |        ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        t(
            "a = \"\"\"\r\"\"\"",
            "\nTOML parse error at line 1, column 9\n  |\n1 | a = \"\"\"\n\"\"\"\n  |         ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        t(
            "a = \"\"\"\\  \r  \"\"\"",
            "\nTOML parse error at line 1, column 12\n  |\n1 | a = \"\"\"\\  \n  \"\"\"\n  |            ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        t(
            "a = '''\r'''",
            """
TOML parse error at line 1, column 9
  |
1 | a = '''
'''
  |         ^
carriage return must be followed by newline, expected newline

""",
        )
        t(
            "a = '\r'",
            """
TOML parse error at line 1, column 6
  |
1 | a = '
'
  |      ^
invalid literal string, expected non-single-quote visible characters

""",
        )
        t(
            "a = \"\r\"",
            """
TOML parse error at line 1, column 6
  |
1 | a = "
"
  |      ^
invalid basic string, expected non-double-quote visible characters, `\`

""",
        )
    }

    @Test
    fun duplicateKeyWithCrlf() {
        t(
            "\r\n[t1]\r\n[t2]\r\na = 1\r\na = 2\r\n",
            """
TOML parse error at line 5, column 1
  |
5 | a = 2
  | ^
duplicate key

""",
        )
    }

    @Test
    fun inlineTableMissingKey() {
        t(
            "={[]\r].",
            """
TOML parse error at line 1, column 3
  |
1 | ={[]
].
  |   ^
missing key for inline table element, expected key

""",
        )
    }

    @Test
    fun inlineTableMissingKeyInArray() {
        t(
            "a=[{[]-]{\na.",
            """
TOML parse error at line 1, column 5
  |
1 | a=[{[]-]{
  |     ^
missing key for inline table element, expected key

""",
        )
    }

    @Test
    fun emojiErrorSpan() {
        val input = "key = 😀"
        val err = DocumentMut.parse(input).unwrapErr()
        assertEquals("😀", spanText(input, err))
    }

    @Test
    fun textErrorSpan() {
        val input = "key = asdf"
        val err = DocumentMut.parse(input).unwrapErr()
        assertEquals("asdf", spanText(input, err))
    }

    @Test
    fun fuzzed68144ErrorSpan() {
        val input = "key = \"\\ᾂr\""
        val err = DocumentMut.parse(input).unwrapErr()
        // Bad escape values are reported as missing escape values.
        assertEquals("", spanText(input, err))
    }
}
