package com.fromwau.kotoml.parser

import kotlin.test.Test
import kotlin.test.assertTrue

class ParseDocumentTest {

    private fun t(input: String, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseDocument(tokens, actual.receiver, actual.sink)
        actual.validate("testsuite__parse_document__$snapshot")
    }

    private fun tRecurse(input: String, maxDepth: UInt, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseDocument(tokens, RecursionGuard(actual.receiver, maxDepth), actual.sink)
        actual.validate("testsuite__parse_document__$snapshot")
    }

    @Test
    fun documentEmpty() {
        t("", "document_empty")
    }

    @Test
    fun documentWs() {
        t("  ", "document_ws")
    }

    @Test
    fun documentKeyString() {
        t(
            """hello.world = "a"
""",
            "document_key_string",
        )
    }

    @Test
    fun documentKeyStringComment() {
        t(
            """ hello = 'darkness' # my old friend
""",
            "document_key_string_comment",
        )
    }

    @Test
    fun documentKeyWs() {
        t(
            """ hello . darkness . my = 'old friend'
""",
            "document_key_ws",
        )
    }

    @Test
    fun documentTable() {
        t(
            """[parent . child]
key = "value"
""",
            "document_table",
        )
    }

    @Test
    fun documentTableMissingKey() {
        t(
            """[]
key = "value"
""",
            "document_table_missing_key",
        )
    }

    @Test
    fun documentTableDot() {
        t(
            """[ . ]
key = "value"
""",
            "document_table_dot",
        )
    }

    @Test
    fun documentTableDotDot() {
        t(
            """[ . . ]
key = "value"
""",
            "document_table_dot_dot",
        )
    }

    @Test
    fun documentTableDotKey() {
        t(
            """[ . table ]
key = "value"
""",
            "document_table_dot_key",
        )
    }

    @Test
    fun documentTableDotDotKey() {
        t(
            """[ . table ]
key = "value"
""",
            "document_table_dot_dot_key",
        )
    }

    @Test
    fun documentTableKeyDot() {
        t(
            """[ table . ]
key = "value"
""",
            "document_table_key_dot",
        )
    }

    @Test
    fun documentTableKeyDotDot() {
        t(
            """[ table . . ]
key = "value"
""",
            "document_table_key_dot_dot",
        )
    }

    @Test
    fun documentTableKeyDotDotKey() {
        t(
            """[ table . . table ]
key = "value"
""",
            "document_table_key_dot_dot_key",
        )
    }

    @Test
    fun documentMissingKey() {
        t(
            """
 = "value"
""",
            "document_missing_key",
        )
    }

    @Test
    fun documentDot() {
        t(
            """
 . = "value"
""",
            "document_dot",
        )
    }

    @Test
    fun documentDotDot() {
        t(
            """
 . . = "value"
""",
            "document_dot_dot",
        )
    }

    @Test
    fun documentDotKey() {
        t(
            """
 . key = "value"
""",
            "document_dot_key",
        )
    }

    @Test
    fun documentDotDotKey() {
        t(
            """
 . . key = "value"
""",
            "document_dot_dot_key",
        )
    }

    @Test
    fun documentKeyDot() {
        t(
            """
key . = "value"
""",
            "document_key_dot",
        )
    }

    @Test
    fun documentKeyDotDot() {
        t(
            """
key . . = "value"
""",
            "document_key_dot_dot",
        )
    }

    @Test
    fun documentKeyDotDotKey() {
        t(
            """
key . . key = "value"
""",
            "document_key_dot_dot_key",
        )
    }

    @Test
    fun documentInlineTableMissingKey() {
        t(
            """
parent = { = "value" }
""",
            "document_inline_table_missing_key",
        )
    }

    @Test
    fun documentInlineTableDot() {
        t(
            """
parent = { . = "value" }
""",
            "document_inline_table_dot",
        )
    }

    @Test
    fun documentInlineTableDotDot() {
        t(
            """
parent = { . . = "value" }
""",
            "document_inline_table_dot_dot",
        )
    }

    @Test
    fun documentInlineTableDotKey() {
        t(
            """
parent = { . key = "value" }
""",
            "document_inline_table_dot_key",
        )
    }

    @Test
    fun documentInlineTableDotDotKey() {
        t(
            """
parent = { . . key = "value" }
""",
            "document_inline_table_dot_dot_key",
        )
    }

    @Test
    fun documentInlineTableKeyDot() {
        t(
            """
parent = { key . = "value" }
""",
            "document_inline_table_key_dot",
        )
    }

    @Test
    fun documentInlineTableKeyDotDot() {
        t(
            """
parent = { key . . = "value" }
""",
            "document_inline_table_key_dot_dot",
        )
    }

    @Test
    fun documentInlineTableKeyDotDotKey() {
        t(
            """
parent = { key . . key = "value" }
""",
            "document_inline_table_key_dot_dot_key",
        )
    }

    @Test
    fun documentKeyDatetime() {
        t(
            """foo = 1979-05-27 # Comment
""",
            "document_key_datetime",
        )
    }

    @Test
    fun documentBom() {
        t(
            "\uFEFF\n[package]\nname = \"foo\"\nversion = \"0.0.1\"\nauthors = []\n",
            "document_bom",
        )
    }

    @Test
    fun documentComplex() {
        t(
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
    fun documentInvalid() {
        t(
            """ hello = 'darkness' # my old friend
${'$'}""",
            "document_invalid",
        )
    }

    @Test
    fun documentInvalidComment() {
        t(" hello = 'darkness' # my old\u0000 friend\n", "document_invalid_comment")
    }

    @Test
    fun documentInvalidCr() {
        t(" hello = 'darkness' # my old friend\r", "document_invalid_cr")
    }

    @Test
    fun inlineTableWithinRecursionLimit() {
        tRecurse(
            """
key = { a = { b = 1 } }
after = [10]
""",
            2u,
            "inline_table_within_recursion_limit",
        )
    }

    @Test
    fun inlineTableOutsideRecursionLimit() {
        tRecurse(
            """
key = { a = { b = { c = { d = 1 } } } }
after = [10]
""",
            2u,
            "inline_table_outside_recursion_limit",
        )
    }

    @Test
    fun arrayWithinRecursionLimit() {
        tRecurse(
            """
key = [[1]]
after = [10]
""",
            2u,
            "array_within_recursion_limit",
        )
    }

    @Test
    fun arrayOutsideRecursionLimit() {
        tRecurse(
            """
key = [[[[1]]]]
after = [10]
""",
            2u,
            "array_outside_recursion_limit",
        )
    }

    @Test
    fun arrayAndInlineTableWithinRecursionLimit() {
        tRecurse(
            """
key = [{ a = 1 }]
after = [10]
""",
            2u,
            "array_and_inline_table_within_recursion_limit",
        )
    }

    @Test
    fun arrayAndInlineTableOutsideRecursionLimit() {
        tRecurse(
            """
key = [{ a = [{ b = [{ c = 1 }] }] }]
after = [10]
""",
            2u,
            "array_and_inline_table_outside_recursion_limit",
        )
    }

    @Test
    fun hexWithSpaces() {
        t(
            """
v=0x _
""",
            "hex_with_spaces",
        )
    }

    @Test
    fun hexWithBadChars() {
        t(
            """
v=0xz_
""",
            "hex_with_bad_chars",
        )
    }

    @Test
    fun floatWithBadUnderscore() {
        t(
            """
v=1_.2
""",
            "float_with_bad_underscore",
        )
    }

    @Test
    fun manyExtraEquals() {
        val equals = "=".repeat(5_000)
        val input = "v${equals}1\n"

        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseDocument(tokens, actual.receiver, actual.sink)

        assertTrue(actual.errors.isNotEmpty())
    }
}
