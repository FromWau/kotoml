package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/** `toml_edit/tests/compliance/invalid.rs` */
class ComplianceInvalidTest {

    private fun assertRefusedWith(toml: String, expected: String) {
        when (val parsed = TomlDocument.parse(toml)) {
            is Result.Success -> fail("parsed to: ${parsed.value}")
            is Result.Error -> assertSnapshot(expected, parsed.error.render(toml))
        }
    }

    /** The input under an error's span. */
    private fun spanText(input: String, error: TomlParseError): String {
        val span = assertNotNull(error.span)
        return input.substring(span.start, span.end)
    }

    @Test
    fun `a control character in a basic string is refused at its column`() {
        assertRefusedWith(
            "a = \"\u007F\"",
            "\nTOML parse error at line 1, column 6\n  |\n1 | a = \"\u007F\"\n  |      ^\n" +
                "invalid basic string, expected non-double-quote visible characters, `\\`\n\n",
        )
    }

    @Test
    fun `a control character in a literal string is refused at its column`() {
        assertRefusedWith(
            "a = '\u007F'",
            "\nTOML parse error at line 1, column 6\n  |\n1 | a = '\u007F'\n  |      ^\n" +
                "invalid literal string, expected non-single-quote visible characters\n\n",
        )
    }

    @Test
    fun `a carriage return without a newline is refused in every kind of string and array`() {
        assertRefusedWith(
            "\r",
            "\nTOML parse error at line 1, column 2\n  |\n1 | \n  |  ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        assertRefusedWith(
            "a = [ \r ]",
            "\nTOML parse error at line 1, column 8\n  |\n1 | a = [ \n ]\n  |        ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        assertRefusedWith(
            "a = \"\"\"\r\"\"\"",
            "\nTOML parse error at line 1, column 9\n  |\n1 | a = \"\"\"\n\"\"\"\n  |         ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        assertRefusedWith(
            "a = \"\"\"\\  \r  \"\"\"",
            "\nTOML parse error at line 1, column 12\n  |\n1 | a = \"\"\"\\  \n  \"\"\"\n  |            ^\n" +
                "carriage return must be followed by newline, expected newline\n\n",
        )
        assertRefusedWith(
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
        assertRefusedWith(
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
        assertRefusedWith(
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
    fun `a duplicate key under CRLF line endings is reported on its own line`() {
        assertRefusedWith(
            "\r\n[t1]\r\n[t2]\r\na = 1\r\na = 2\r\n",
            """
TOML parse error at line 5, column 1
  |
5 | a = 2
  | ^
duplicate key `a`

""",
        )
    }

    @Test
    fun `an inline table element without a key is refused`() {
        assertRefusedWith(
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
    fun `an inline table element without a key inside an array is refused`() {
        assertRefusedWith(
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
    fun `an error span covers a whole multi-byte character`() {
        val input = "key = 😀"
        val err = TomlDocument.parse(input).errorOrFail()
        assertEquals("😀", spanText(input, err))
    }

    @Test
    fun `an error span after non-ASCII text indexes the parsed String`() {
        val input = "a = \"é😀\"\nb = asdf"
        val err = TomlDocument.parse(input).errorOrFail()
        assertEquals("asdf", spanText(input, err))
    }

    @Test
    fun `bytes that are not UTF-8 get an empty span where the valid text ends`() {
        val bytes = "a = \"é\"".encodeToByteArray() + byteArrayOf(0xFF.toByte())
        val err = TomlDocument.parse(bytes).errorOrFail()
        assertEquals(Span(7, 7), err.span)
    }

    @Test
    fun `an error span covers the whole unquoted word`() {
        val input = "key = asdf"
        val err = TomlDocument.parse(input).errorOrFail()
        assertEquals("asdf", spanText(input, err))
    }

    @Test
    fun `a bad escape value reports an empty span where the escape value is missing`() {
        val input = "key = \"\\ᾂr\""
        val err = TomlDocument.parse(input).errorOrFail()
        // Bad escape values are reported as missing escape values.
        assertEquals("", spanText(input, err))
    }
}
