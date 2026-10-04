package com.fromwau.kotoml.parser

import kotlin.test.Test
import kotlin.test.assertTrue

class ParseDocumentTest {

    private fun assertEvents(input: String, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().toList()
        parseDocument(tokens, actual.receiver, actual.sink)
        actual.validate("testsuite__parse_document__$snapshot")
    }

    private fun assertEventsWithDepthLimit(input: String, maxDepth: UInt, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().toList()
        parseDocument(tokens, RecursionGuard(actual.receiver, maxDepth), actual.sink)
        actual.validate("testsuite__parse_document__$snapshot")
    }

    @Test
    fun `an empty document emits no events`() {
        assertEvents("", "document_empty")
    }

    @Test
    fun `a document of only spaces emits one whitespace event`() {
        assertEvents("  ", "document_ws")
    }

    @Test
    fun `a dotted key with a basic string value parses without errors`() {
        assertEvents(
            """hello.world = "a"
""",
            "document_key_string",
        )
    }

    @Test
    fun `a key value pair followed by a comment parses without errors`() {
        assertEvents(
            """ hello = 'darkness' # my old friend
""",
            "document_key_string_comment",
        )
    }

    @Test
    fun `whitespace around the dots of a dotted key parses without errors`() {
        assertEvents(
            """ hello . darkness . my = 'old friend'
""",
            "document_key_ws",
        )
    }

    @Test
    fun `a table header with a spaced dotted key parses without errors`() {
        assertEvents(
            """[parent . child]
key = "value"
""",
            "document_table",
        )
    }

    @Test
    fun `an empty table header reports an empty key`() {
        assertEvents(
            """[]
key = "value"
""",
            "document_table_missing_key",
        )
    }

    @Test
    fun `a table header of a lone dot reports two empty keys`() {
        assertEvents(
            """[ . ]
key = "value"
""",
            "document_table_dot",
        )
    }

    @Test
    fun `a table header of two dots reports three empty keys`() {
        assertEvents(
            """[ . . ]
key = "value"
""",
            "document_table_dot_dot",
        )
    }

    @Test
    fun `a table header starting with a dot reports one empty key`() {
        assertEvents(
            """[ . table ]
key = "value"
""",
            "document_table_dot_key",
        )
    }

    @Test
    fun `a table header starting with a dot reports one empty key in the dot dot key snapshot`() {
        assertEvents(
            """[ . table ]
key = "value"
""",
            "document_table_dot_dot_key",
        )
    }

    @Test
    fun `a table header ending in a dot reports one empty key`() {
        assertEvents(
            """[ table . ]
key = "value"
""",
            "document_table_key_dot",
        )
    }

    @Test
    fun `a table header ending in two dots reports two empty keys`() {
        assertEvents(
            """[ table . . ]
key = "value"
""",
            "document_table_key_dot_dot",
        )
    }

    @Test
    fun `a table header with two dots between keys reports one empty key`() {
        assertEvents(
            """[ table . . table ]
key = "value"
""",
            "document_table_key_dot_dot_key",
        )
    }

    @Test
    fun `a value with no key reports an empty key`() {
        assertEvents(
            """
 = "value"
""",
            "document_missing_key",
        )
    }

    @Test
    fun `a key of a lone dot reports two empty keys`() {
        assertEvents(
            """
 . = "value"
""",
            "document_dot",
        )
    }

    @Test
    fun `a key of two dots reports three empty keys`() {
        assertEvents(
            """
 . . = "value"
""",
            "document_dot_dot",
        )
    }

    @Test
    fun `a key starting with a dot reports one empty key`() {
        assertEvents(
            """
 . key = "value"
""",
            "document_dot_key",
        )
    }

    @Test
    fun `a key starting with two dots reports two empty keys`() {
        assertEvents(
            """
 . . key = "value"
""",
            "document_dot_dot_key",
        )
    }

    @Test
    fun `a key ending in a dot reports one empty key`() {
        assertEvents(
            """
key . = "value"
""",
            "document_key_dot",
        )
    }

    @Test
    fun `a key ending in two dots reports two empty keys`() {
        assertEvents(
            """
key . . = "value"
""",
            "document_key_dot_dot",
        )
    }

    @Test
    fun `a key with two dots between its parts reports one empty key`() {
        assertEvents(
            """
key . . key = "value"
""",
            "document_key_dot_dot_key",
        )
    }

    @Test
    fun `an inline table entry with no key reports an empty key`() {
        assertEvents(
            """
parent = { = "value" }
""",
            "document_inline_table_missing_key",
        )
    }

    @Test
    fun `an inline table key of a lone dot reports two empty keys`() {
        assertEvents(
            """
parent = { . = "value" }
""",
            "document_inline_table_dot",
        )
    }

    @Test
    fun `an inline table key of two dots reports three empty keys`() {
        assertEvents(
            """
parent = { . . = "value" }
""",
            "document_inline_table_dot_dot",
        )
    }

    @Test
    fun `an inline table key starting with a dot reports one empty key`() {
        assertEvents(
            """
parent = { . key = "value" }
""",
            "document_inline_table_dot_key",
        )
    }

    @Test
    fun `an inline table key starting with two dots reports two empty keys`() {
        assertEvents(
            """
parent = { . . key = "value" }
""",
            "document_inline_table_dot_dot_key",
        )
    }

    @Test
    fun `an inline table key ending in a dot reports one empty key`() {
        assertEvents(
            """
parent = { key . = "value" }
""",
            "document_inline_table_key_dot",
        )
    }

    @Test
    fun `an inline table key ending in two dots reports two empty keys`() {
        assertEvents(
            """
parent = { key . . = "value" }
""",
            "document_inline_table_key_dot_dot",
        )
    }

    @Test
    fun `an inline table key with two dots between its parts reports one empty key`() {
        assertEvents(
            """
parent = { key . . key = "value" }
""",
            "document_inline_table_key_dot_dot_key",
        )
    }

    @Test
    fun `a local date value followed by a comment parses without errors`() {
        assertEvents(
            """foo = 1979-05-27 # Comment
""",
            "document_key_datetime",
        )
    }

    @Test
    fun `a leading byte order mark is skipped`() {
        assertEvents(
            "\uFEFF\n[package]\nname = \"foo\"\nversion = \"0.0.1\"\nauthors = []\n",
            "document_bom",
        )
    }

    @Test
    fun `the TOML example document with tables arrays and multi-line strings parses without errors`() {
        assertEvents(
            "\n# This is a TOML document.\n\ntitle = \"TOML Example\"\n\n    [owner]\n    name = \"Tom Preston-We" +
                "rner\"\n    dob = 1979-05-27T07:32:00-08:00 # First class dates\n\n    [database]\n    server = \"19" +
                "2.168.1.1\"\n    ports = [ 8001, 8001, 8002 ]\n    connection_max = 5000\n    enabled = true\n\n    " +
                "[servers]\n\n    # Indentation (tabs and/or spaces) is allowed but not required\n[servers.alpha]\n  " +
                "  ip = \"10.0.0.1\"\n    dc = \"eqdc10\"\n\n    [servers.beta]\n    ip = \"10.0.0.2\"\n    dc = \"eq" +
                "dc10\"\n\n    [clients]\n    data = [ [\"gamma\", \"delta\"], [1, 2] ]\n\n    # Line breaks are OK w" +
                "hen inside arrays\nhosts = [\n    \"alpha\",\n    \"omega\"\n]\n\n   'some.weird .stuff'   =  \"\"\"" +
                "\n                         like\n                         that\n                      #   \"\"\" # t" +
                "his broke my syntax highlighting\n   \" also. like \" = '''\nthat\n'''\n   double = 2e39 # this numb" +
                "er looks familiar\n# trailing comment",
            "document_complex",
        )
    }

    @Test
    fun `a stray dollar sign line reports an invalid key with no value`() {
        assertEvents(
            """ hello = 'darkness' # my old friend
${'$'}""",
            "document_invalid",
        )
    }

    @Test
    fun `a NUL inside a comment reports an invalid comment character`() {
        assertEvents(" hello = 'darkness' # my old\u0000 friend\n", "document_invalid_comment")
    }

    @Test
    fun `a carriage return without a newline reports the bare carriage return`() {
        assertEvents(" hello = 'darkness' # my old friend\r", "document_invalid_cr")
    }

    @Test
    fun `inline tables nested to the recursion limit parse without errors`() {
        assertEventsWithDepthLimit(
            """
key = { a = { b = 1 } }
after = [10]
""",
            2u,
            "inline_table_within_recursion_limit",
        )
    }

    @Test
    fun `inline tables past the recursion limit report it and parsing resumes at the next key`() {
        assertEventsWithDepthLimit(
            """
key = { a = { b = { c = { d = 1 } } } }
after = [10]
""",
            2u,
            "inline_table_outside_recursion_limit",
        )
    }

    @Test
    fun `arrays nested to the recursion limit parse without errors`() {
        assertEventsWithDepthLimit(
            """
key = [[1]]
after = [10]
""",
            2u,
            "array_within_recursion_limit",
        )
    }

    @Test
    fun `arrays nested past the recursion limit report it and parsing resumes at the next key`() {
        assertEventsWithDepthLimit(
            """
key = [[[[1]]]]
after = [10]
""",
            2u,
            "array_outside_recursion_limit",
        )
    }

    @Test
    fun `an inline table in an array within the recursion limit parses without errors`() {
        assertEventsWithDepthLimit(
            """
key = [{ a = 1 }]
after = [10]
""",
            2u,
            "array_and_inline_table_within_recursion_limit",
        )
    }

    @Test
    fun `inline tables and arrays nested past the recursion limit report it and parsing resumes`() {
        assertEventsWithDepthLimit(
            """
key = [{ a = [{ b = [{ c = 1 }] }] }]
after = [10]
""",
            2u,
            "array_and_inline_table_outside_recursion_limit",
        )
    }

    @Test
    fun `a hexadecimal prefix followed by a space reports an unquoted string value`() {
        assertEvents(
            """
v=0x _
""",
            "hex_with_spaces",
        )
    }

    @Test
    fun `a hexadecimal number with a non-hex digit reports an invalid hexadecimal number`() {
        assertEvents(
            """
v=0xz_
""",
            "hex_with_bad_chars",
        )
    }

    @Test
    fun `an underscore before a float's dot reports it may only go between digits`() {
        assertEvents(
            """
v=1_.2
""",
            "float_with_bad_underscore",
        )
    }

    @Test
    fun `thousands of equals signs after a key report an error without overflowing`() {
        val equals = "=".repeat(5_000)
        val input = "v${equals}1\n"

        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().toList()
        parseDocument(tokens, actual.receiver, actual.sink)

        assertTrue(actual.errors.isNotEmpty())
    }
}
