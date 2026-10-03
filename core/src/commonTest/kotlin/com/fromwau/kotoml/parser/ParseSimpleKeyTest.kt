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
    fun `an empty input reports an invalid key`() {
        t("", "simple_key_empty")
    }

    @Test
    fun `an unquoted ASCII key is one simple key event`() {
        t("a", "simple_key_unquoted_ascii")
    }

    @Test
    fun `a basic string key is one simple key event with its escapes undecoded`() {
        t("\"hello\\n \"", "simple_key_string")
    }

    @Test
    fun `a literal string key is one simple key event`() {
        t("'hello\\n '", "simple_key_string_literal")
    }
}
