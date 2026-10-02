package com.fromwau.kotoml.parser

import kotlin.test.Test

class ParseSimpleKeyTest {

    private fun t(input: String, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseSimpleKey(tokens, actual.receiver, actual.sink)
        actual.validate("testsuite__parse_simple_key__$snapshot")
    }

    private fun tRecurse(input: String, maxDepth: UInt, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseSimpleKey(tokens, RecursionGuard(actual.receiver, maxDepth), actual.sink)
        actual.validate("testsuite__parse_simple_key__$snapshot")
    }

    @Test
    fun simpleKeyEmpty() {
        t("", "simple_key_empty")
    }

    @Test
    fun simpleKeyUnquotedAscii() {
        t("a", "simple_key_unquoted_ascii")
    }

    @Test
    fun simpleKeyString() {
        t("\"hello\\n \"", "simple_key_string")
    }

    @Test
    fun simpleKeyStringLiteral() {
        t("'hello\\n '", "simple_key_string_literal")
    }
}
