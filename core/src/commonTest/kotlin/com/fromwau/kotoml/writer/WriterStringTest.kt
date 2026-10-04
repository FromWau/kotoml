package com.fromwau.kotoml.writer

import com.fromwau.kotoml.assertDebugSnapshot
import com.fromwau.kotoml.option
import com.fromwau.kotoml.str
import com.fromwau.kotoml.struct
import kotlin.random.Random
import kotlin.test.Test

class WriterStringTest {

    private fun t(decoded: String, expected: String) {
        val key = TomlKeyBuilder(decoded)
        val string = TomlStringBuilder(decoded)
        val keyDefault = key.asDefault().toTomlKey()
        val stringDefault = string.asDefault().toTomlValue()
        val results = struct(
            "StringResults",
            "decoded" to str(decoded),
            "key_default" to str(keyDefault),
            "key_unquoted" to option(key.asUnquoted()?.toTomlKey()) { str(it) },
            "key_literal" to option(key.asLiteral()?.toTomlKey()) { str(it) },
            "key_basic_pretty" to option(key.asBasicPretty()?.toTomlKey()) { str(it) },
            "key_basic" to str(key.asBasic().toTomlKey()),
            "string_default" to str(stringDefault),
            "string_literal" to option(string.asLiteral()?.toTomlValue()) { str(it) },
            "string_ml_literal" to option(string.asMlLiteral()?.toTomlValue()) { str(it) },
            "string_basic_pretty" to option(string.asBasicPretty()?.toTomlValue()) { str(it) },
            "string_ml_basic_pretty" to option(string.asMlBasicPretty()?.toTomlValue()) { str(it) },
            "string_basic" to str(string.asBasic().toTomlValue()),
            "string_ml_basic" to str(string.asMlBasic().toTomlValue()),
        )
        assertDebugSnapshot(expected, results)

        assertParsesBack(keyDefault, stringDefault, decoded)
    }

    @Test
    fun `an empty string is quoted as a key and as a value`() {
        t(
            "",
            """
StringResults {
    decoded: "",
    key_default: "\"\"",
    key_unquoted: None,
    key_literal: Some(
        "''",
    ),
    key_basic_pretty: Some(
        "\"\"",
    ),
    key_basic: "\"\"",
    string_default: "\"\"",
    string_literal: Some(
        "''",
    ),
    string_ml_literal: Some(
        "''''''",
    ),
    string_basic_pretty: Some(
        "\"\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"\"\"\"",
    ),
    string_basic: "\"\"",
    string_ml_basic: "\"\"\"\"\"\"",
}

""",
        )
    }

    @Test
    fun `letters write as a bare key and a basic string`() {
        t(
            "helloworld",
            """
StringResults {
    decoded: "helloworld",
    key_default: "helloworld",
    key_unquoted: Some(
        "helloworld",
    ),
    key_literal: Some(
        "'helloworld'",
    ),
    key_basic_pretty: Some(
        "\"helloworld\"",
    ),
    key_basic: "\"helloworld\"",
    string_default: "\"helloworld\"",
    string_literal: Some(
        "'helloworld'",
    ),
    string_ml_literal: Some(
        "'''helloworld'''",
    ),
    string_basic_pretty: Some(
        "\"helloworld\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"helloworld\"\"\"",
    ),
    string_basic: "\"helloworld\"",
    string_ml_basic: "\"\"\"helloworld\"\"\"",
}

""",
        )
    }

    @Test
    fun `letters dashes and underscores write as a bare key`() {
        t(
            "_hello-world_",
            """
StringResults {
    decoded: "_hello-world_",
    key_default: "_hello-world_",
    key_unquoted: Some(
        "_hello-world_",
    ),
    key_literal: Some(
        "'_hello-world_'",
    ),
    key_basic_pretty: Some(
        "\"_hello-world_\"",
    ),
    key_basic: "\"_hello-world_\"",
    string_default: "\"_hello-world_\"",
    string_literal: Some(
        "'_hello-world_'",
    ),
    string_ml_literal: Some(
        "'''_hello-world_'''",
    ),
    string_basic_pretty: Some(
        "\"_hello-world_\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"_hello-world_\"\"\"",
    ),
    string_basic: "\"_hello-world_\"",
    string_ml_basic: "\"\"\"_hello-world_\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding single quotes defaults to basic quotes`() {
        t(
            "'hello'world'",
            """
StringResults {
    decoded: "'hello'world'",
    key_default: "\"'hello'world'\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: Some(
        "\"'hello'world'\"",
    ),
    key_basic: "\"'hello'world'\"",
    string_default: "\"'hello'world'\"",
    string_literal: None,
    string_ml_literal: Some(
        "''''hello'world''''",
    ),
    string_basic_pretty: Some(
        "\"'hello'world'\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"'hello'world'\"\"\"",
    ),
    string_basic: "\"'hello'world'\"",
    string_ml_basic: "\"\"\"'hello'world'\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding doubled single quotes defaults to basic quotes`() {
        t(
            "''hello''world''",
            """
StringResults {
    decoded: "''hello''world''",
    key_default: "\"''hello''world''\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: Some(
        "\"''hello''world''\"",
    ),
    key_basic: "\"''hello''world''\"",
    string_default: "\"''hello''world''\"",
    string_literal: None,
    string_ml_literal: Some(
        "'''''hello''world'''''",
    ),
    string_basic_pretty: Some(
        "\"''hello''world''\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"''hello''world''\"\"\"",
    ),
    string_basic: "\"''hello''world''\"",
    string_ml_basic: "\"\"\"''hello''world''\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding tripled single quotes rules out both literal forms`() {
        t(
            "'''hello'''world'''",
            """
StringResults {
    decoded: "'''hello'''world'''",
    key_default: "\"'''hello'''world'''\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: Some(
        "\"'''hello'''world'''\"",
    ),
    key_basic: "\"'''hello'''world'''\"",
    string_default: "\"'''hello'''world'''\"",
    string_literal: None,
    string_ml_literal: None,
    string_basic_pretty: Some(
        "\"'''hello'''world'''\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"'''hello'''world'''\"\"\"",
    ),
    string_basic: "\"'''hello'''world'''\"",
    string_ml_basic: "\"\"\"'''hello'''world'''\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding double quotes defaults to literal quotes`() {
        t(
            "\"hello\"world\"",
            """
StringResults {
    decoded: "\"hello\"world\"",
    key_default: "'\"hello\"world\"'",
    key_unquoted: None,
    key_literal: Some(
        "'\"hello\"world\"'",
    ),
    key_basic_pretty: None,
    key_basic: "\"\\\"hello\\\"world\\\"\"",
    string_default: "'\"hello\"world\"'",
    string_literal: Some(
        "'\"hello\"world\"'",
    ),
    string_ml_literal: Some(
        "'''\"hello\"world\"'''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: Some(
        "\"\"\"\"hello\"world\"\"\"\"",
    ),
    string_basic: "\"\\\"hello\\\"world\\\"\"",
    string_ml_basic: "\"\"\"\"hello\"world\"\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding doubled double quotes defaults to literal quotes`() {
        t(
            "\"\"hello\"\"world\"\"",
            """
StringResults {
    decoded: "\"\"hello\"\"world\"\"",
    key_default: "'\"\"hello\"\"world\"\"'",
    key_unquoted: None,
    key_literal: Some(
        "'\"\"hello\"\"world\"\"'",
    ),
    key_basic_pretty: None,
    key_basic: "\"\\\"\\\"hello\\\"\\\"world\\\"\\\"\"",
    string_default: "'\"\"hello\"\"world\"\"'",
    string_literal: Some(
        "'\"\"hello\"\"world\"\"'",
    ),
    string_ml_literal: Some(
        "'''\"\"hello\"\"world\"\"'''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: Some(
        "\"\"\"\"\"hello\"\"world\"\"\"\"\"",
    ),
    string_basic: "\"\\\"\\\"hello\\\"\\\"world\\\"\\\"\"",
    string_ml_basic: "\"\"\"\"\"hello\"\"world\"\"\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding tripled double quotes defaults to literal quotes`() {
        t(
            "\"\"\"hello\"\"\"world\"\"\"",
            """
StringResults {
    decoded: "\"\"\"hello\"\"\"world\"\"\"",
    key_default: "'\"\"\"hello\"\"\"world\"\"\"'",
    key_unquoted: None,
    key_literal: Some(
        "'\"\"\"hello\"\"\"world\"\"\"'",
    ),
    key_basic_pretty: None,
    key_basic: "\"\\\"\\\"\\\"hello\\\"\\\"\\\"world\\\"\\\"\\\"\"",
    string_default: "'\"\"\"hello\"\"\"world\"\"\"'",
    string_literal: Some(
        "'\"\"\"hello\"\"\"world\"\"\"'",
    ),
    string_ml_literal: Some(
        "'''\"\"\"hello\"\"\"world\"\"\"'''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: None,
    string_basic: "\"\\\"\\\"\\\"hello\\\"\\\"\\\"world\\\"\\\"\\\"\"",
    string_ml_basic: "\"\"\"\"\"\\\"hello\"\"\\\"world\"\"\\\"\"\"\"",
}

""",
        )
    }

    @Test
    fun `a double quote then a single quote defaults to a multi-line basic string`() {
        t(
            "\"'",
            """
StringResults {
    decoded: "\"'",
    key_default: "\"\\\"'\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: None,
    key_basic: "\"\\\"'\"",
    string_default: "\"\"\"\"'\"\"\"",
    string_literal: None,
    string_ml_literal: Some(
        "'''\"''''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: Some(
        "\"\"\"\"'\"\"\"",
    ),
    string_basic: "\"\\\"'\"",
    string_ml_basic: "\"\"\"\"'\"\"\"",
}

""",
        )
    }

    @Test
    fun `escaped double quotes beside doubled single quotes default to a multi-line literal`() {
        t(
            "mixed quoted \\\"start\\\" 'end'' mixed quote",
            """
StringResults {
    decoded: "mixed quoted \\\"start\\\" 'end'' mixed quote",
    key_default: "\"mixed quoted \\\\\\\"start\\\\\\\" 'end'' mixed quote\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: None,
    key_basic: "\"mixed quoted \\\\\\\"start\\\\\\\" 'end'' mixed quote\"",
    string_default: "'''mixed quoted \\\"start\\\" 'end'' mixed quote'''",
    string_literal: None,
    string_ml_literal: Some(
        "'''mixed quoted \\\"start\\\" 'end'' mixed quote'''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: None,
    string_basic: "\"mixed quoted \\\\\\\"start\\\\\\\" 'end'' mixed quote\"",
    string_ml_basic: "\"\"\"mixed quoted \\\\\"start\\\\\" 'end'' mixed quote\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string holding backslashes defaults to literal quotes`() {
        t(
            "\\windows\\system32\\",
            """
StringResults {
    decoded: "\\windows\\system32\\",
    key_default: "'\\windows\\system32\\'",
    key_unquoted: None,
    key_literal: Some(
        "'\\windows\\system32\\'",
    ),
    key_basic_pretty: None,
    key_basic: "\"\\\\windows\\\\system32\\\\\"",
    string_default: "'\\windows\\system32\\'",
    string_literal: Some(
        "'\\windows\\system32\\'",
    ),
    string_ml_literal: Some(
        "'''\\windows\\system32\\'''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: None,
    string_basic: "\"\\\\windows\\\\system32\\\\\"",
    string_ml_basic: "\"\"\"\\\\windows\\\\system32\\\\\"\"\"",
}

""",
        )
    }

    @Test
    fun `lone carriage returns are escaped and rule out both literal forms`() {
        t(
            "\rhello\rworld\r",
            """
StringResults {
    decoded: "\rhello\rworld\r",
    key_default: "\"\\rhello\\rworld\\r\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: None,
    key_basic: "\"\\rhello\\rworld\\r\"",
    string_default: "\"\\rhello\\rworld\\r\"",
    string_literal: None,
    string_ml_literal: None,
    string_basic_pretty: None,
    string_ml_basic_pretty: None,
    string_basic: "\"\\rhello\\rworld\\r\"",
    string_ml_basic: "\"\"\"\\rhello\\rworld\\r\"\"\"",
}

""",
        )
    }

    @Test
    fun `a string with newlines defaults to a multi-line basic string`() {
        t(
            """
hello
world
""",
            """
StringResults {
    decoded: "\nhello\nworld\n",
    key_default: "\"\\nhello\\nworld\\n\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: None,
    key_basic: "\"\\nhello\\nworld\\n\"",
    string_default: "\"\"\"\n\nhello\nworld\n\"\"\"",
    string_literal: None,
    string_ml_literal: Some(
        "'''\n\nhello\nworld\n'''",
    ),
    string_basic_pretty: None,
    string_ml_basic_pretty: Some(
        "\"\"\"\n\nhello\nworld\n\"\"\"",
    ),
    string_basic: "\"\\nhello\\nworld\\n\"",
    string_ml_basic: "\"\"\"\n\nhello\nworld\n\"\"\"",
}

""",
        )
    }

    @Test
    fun `CRLF breaks write as escaped carriage returns in a multi-line basic string`() {
        t(
            "\r\nhello\r\nworld\r\n",
            """
StringResults {
    decoded: "\r\nhello\r\nworld\r\n",
    key_default: "\"\\r\\nhello\\r\\nworld\\r\\n\"",
    key_unquoted: None,
    key_literal: None,
    key_basic_pretty: None,
    key_basic: "\"\\r\\nhello\\r\\nworld\\r\\n\"",
    string_default: "\"\"\"\n\\r\nhello\\r\nworld\\r\n\"\"\"",
    string_literal: None,
    string_ml_literal: None,
    string_basic_pretty: None,
    string_ml_basic_pretty: None,
    string_basic: "\"\\r\\nhello\\r\\nworld\\r\\n\"",
    string_ml_basic: "\"\"\"\n\\r\nhello\\r\nworld\\r\n\"\"\"",
}

""",
        )
    }

    @Test
    fun `tabs are escaped in the default basic string`() {
        t(
            "\thello\tworld\t",
            """
StringResults {
    decoded: "\thello\tworld\t",
    key_default: "\"\\thello\\tworld\\t\"",
    key_unquoted: None,
    key_literal: Some(
        "'\thello\tworld\t'",
    ),
    key_basic_pretty: Some(
        "\"\\thello\\tworld\\t\"",
    ),
    key_basic: "\"\\thello\\tworld\\t\"",
    string_default: "\"\\thello\\tworld\\t\"",
    string_literal: Some(
        "'\thello\tworld\t'",
    ),
    string_ml_literal: Some(
        "'''\thello\tworld\t'''",
    ),
    string_basic_pretty: Some(
        "\"\\thello\\tworld\\t\"",
    ),
    string_ml_basic_pretty: Some(
        "\"\"\"\\thello\\tworld\\t\"\"\"",
    ),
    string_basic: "\"\\thello\\tworld\\t\"",
    string_ml_basic: "\"\"\"\\thello\\tworld\\t\"\"\"",
}

""",
        )
    }

    @Test
    fun `a run of 256 double quotes defaults to literal quotes`() {
        t(
            "\"".repeat(256),
            "\nStringResults {\n    decoded: \"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\",\n    key_default: \"'\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"'\",\n    key_unquoted: None,\n    key_literal: Some(\n        \"'\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"'" +
                "\",\n    ),\n    key_basic_pretty: None,\n    key_basic: \"\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\"\",\n    string_default: \"'\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"'\",\n    string_literal: Some(" +
                "\n        \"'\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\" +
                "\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"'\",\n    ),\n    string_ml_literal: Some(\n        \"'''\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"\\\"" +
                "\\\"\\\"\\\"\\\"\\\"'''\",\n    ),\n    string_basic_pretty: None,\n    string_ml_basic_pretty: None" +
                ",\n    string_basic: \"\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"" +
                "\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\" +
                "\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\\\\\"\\\"\",\n    string_ml_b" +
                "asic: \"\\\"\\\"\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"" +
                "\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"" +
                "\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"" +
                "\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\" +
                "\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"" +
                "\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"" +
                "\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"" +
                "\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\" +
                "\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"" +
                "\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"" +
                "\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"" +
                "\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\" +
                "\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"" +
                "\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\\\\\"\\\"\\\"\\\"\\\"\"," +
                "\n}\n\n",
        )
    }

    @Test
    fun `the default key and string encodings of random text parse back`() {
        val random = Random(0x70_6D_6C)
        repeat(512) {
            val decoded = randomPrintableString(random)
            val keyDefault = TomlKeyBuilder(decoded).asDefault().toTomlKey()
            val stringDefault = TomlStringBuilder(decoded).asDefault().toTomlValue()
            assertParsesBack(keyDefault, stringDefault, decoded)
        }
    }
}
