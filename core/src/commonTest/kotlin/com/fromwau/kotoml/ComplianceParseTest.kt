package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `toml_edit/tests/compliance/parse.rs` */
class ComplianceParseTest {

    private fun parseValue(text: String): Value = Value.parse(text).getOrFail()

    private fun parseDocument(text: String): TomlDocument = TomlDocument.parse(text).getOrFail()

    @Test
    fun `each kind of value text parses as its own value kind`() {
        assertTrue(parseValue("1979-05-27T00:32:00.999999-07:00") is Value.Datetime)
        assertTrue(parseValue("1979-05-27T00:32:00.999999Z") is Value.Datetime)
        assertTrue(parseValue("1979-05-27T00:32:00.999999") is Value.Datetime)
        assertTrue(parseValue("1979-05-27T00:32:00") is Value.Datetime)
        assertTrue(parseValue("1979-05-27") is Value.Datetime)
        assertTrue(parseValue("00:32:00") is Value.Datetime)
        assertTrue(parseValue("-239") is Value.Integer)
        assertTrue(parseValue("1e200") is Value.Float)
        assertTrue(parseValue("9_224_617.445_991_228_313") is Value.Float)
        assertTrue(parseValue("\"basic string\\nJos\\u00E9\\n\"") is Value.String)
        assertTrue(parseValue("\"\"\"\nmultiline basic string\n\"\"\"") is Value.String)
        assertTrue(parseValue("'literal string\\ \\'") is Value.String)
        assertTrue(
            parseValue(
                """'''multiline
literal \ \
string'''""",
            ) is Value.String,
        )
        assertTrue(parseValue("{ hello = \"world\", a = 1}") is Value.InlineTable)
        assertTrue(parseValue("[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]") is Value.Array)
        val wp = "C:\\Users\\appveyor\\AppData\\Local\\Temp\\1\\cargo-edit-test.YizxPxxElXn9"
        val lwp = "'C:\\Users\\appveyor\\AppData\\Local\\Temp\\1\\cargo-edit-test.YizxPxxElXn9'"
        assertEquals(Value.String(wp).stringOrNull, parseValue(lwp).stringOrNull)
        assertTrue(parseValue("\"\\\\\\\"\\b\\f\\n\\r\\t\\u00E9\\U000A0000\"") is Value.String)
    }

    @Test
    fun `headers spelling the same key differently each print as written`() {
        val toml = """
[a]
[a.'b'.c]
[a."b".c.e]
[a.b.c.d]
"""
        // Upstream prints every header with the spelling the first one used; this port keeps each header as
        // written, which the toml-test round trip requires (valid/table/with-literal-string).
        val expected = """

[a]
[a.'b'.c]
[a."b".c.e]
[a.b.c.d]

"""
        assertData(expected, TomlDocument.parse(toml).getOrFail().toString())
    }

    @Test
    fun `a document with CRLF line endings parses`() {
        parseDocument(
            "[project]\r\n\r\nname = \"splay\"\r\nversion = \"0.1.0\"\r\nauthors = [\"alex@crichton.co\"]\r\n\r\n" +
                "[[lib]]\r\n\r\npath = \"lib.rs\"\r\nname = \"splay\"\r\ndescription = \"\"\"A Rust implementatio" +
                    "n of " +
                "a TAR file reader and writer. This library does not\r\ncurrently handle compression, but it is " +
                "abstract over all I/O readers and\r\nwriters. Additionally, great lengths are taken to ensure that " +
                "the entire\r\ncontents are never required to be entirely resident in memory all at once.\r\n\"\"\"",
        )
    }

    @Test
    fun `basic literal and multi-line strings decode their escapes and line continuations`() {
        val table = parseDocument(
            "\nbar = \"\\U00000000\"\nkey1 = \"One\\nTwo\"\nkey2 = \"\"\"One\\nTwo\"\"\"\nkey3 = \"\"\"\nOne\nTwo" +
                "\"\"\"\n" +
                "\nkey4 = \"The quick brown fox jumps over the lazy dog.\"\nkey5 = \"\"\"\nThe quick brown \\\n\n\n" +
                "fox jumps over \\\nthe lazy dog.\"\"\"\nkey6 = \"\"\"\\\n   The quick brown \\\n   fox jumps over " +
                "\\\n   the lazy dog.\\\n   \"\"\"\n# What you see is what you get.\nwinpath  = " +
                "'C:\\Users\\nodejs\\templates'\nwinpath2 = '\\\\ServerX\\admin\$\\system32\\'\nquoted   = " +
                "'Tom \"Dubs\" Preston-Werner'\nregex    = '<\\i\\c*\\s*>'\n\nregex2 = '''I [dw]on't need \\d{2} " +
                "apples'''\nlines  = '''\nThe first newline is\ntrimmed in raw strings.\nAll other whitespace\n" +
                "is preserved.\n'''\n",
        )
        assertEquals("\u0000", table["bar"].stringOrNull)
        assertEquals("One\nTwo", table["key1"].stringOrNull)
        assertEquals("One\nTwo", table["key2"].stringOrNull)
        assertEquals("One\nTwo", table["key3"].stringOrNull)

        val msg = "The quick brown fox jumps over the lazy dog."
        assertEquals(msg, table["key4"].stringOrNull)
        assertEquals(msg, table["key5"].stringOrNull)
        assertEquals(msg, table["key6"].stringOrNull)

        assertEquals("C:\\Users\\nodejs\\templates", table["winpath"].stringOrNull)
        assertEquals("\\\\ServerX\\admin\$\\system32\\", table["winpath2"].stringOrNull)
        assertEquals("Tom \"Dubs\" Preston-Werner", table["quoted"].stringOrNull)
        assertEquals("<\\i\\c*\\s*>", table["regex"].stringOrNull)
        assertEquals("I [dw]on't need \\d{2} apples", table["regex2"].stringOrNull)
        assertEquals(
            "The first newline is\ntrimmed in raw strings.\nAll other whitespace\nis preserved.\n",
            table["lines"].stringOrNull,
        )
    }

    @Test
    fun `a sub-table under each array of tables element belongs to that element`() {
        val table = parseDocument(
            """
[[foo]]
#…
[foo.bar]
#…

[[foo]] # ...
#…
[foo.bar]
#...
""",
        )
        assertTrue(table["foo"][0]["bar"].tableOrNull != null)
        assertTrue(table["foo"][1]["bar"].tableOrNull != null)
    }

    @Test
    fun `a header with no keys makes an empty table`() {
        val table = parseDocument("\n[foo]")
        assertTrue(table["foo"].tableOrNull != null)
    }

    @Test
    fun `a header under a table holding a dotted key prints as written`() {
        val input = """
[package]
metadata.msrv = "1.65.0"

[package.metadata.release.pre-release-replacements]
"""
        val expected = """

[package]
metadata.msrv = "1.65.0"

[package.metadata.release.pre-release-replacements]

"""
        val document = parseDocument(input)
        assertData(expected, document.toString())
    }

    @Test
    fun `nested arrays of tables and sub-tables read back by index`() {
        val table = parseDocument(
            """
[[fruit]]
name = "apple"

[fruit.physical]
color = "red"
shape = "round"

[[fruit.variety]]
name = "red delicious"

[[fruit.variety]]
name = "granny smith"

[[fruit]]
name = "banana"

[[fruit.variety]]
name = "plantain"
""",
        )
        assertEquals("apple", table["fruit"][0]["name"].stringOrNull)
        assertEquals("red", table["fruit"][0]["physical"]["color"].stringOrNull)
        assertEquals("round", table["fruit"][0]["physical"]["shape"].stringOrNull)
        assertEquals("red delicious", table["fruit"][0]["variety"][0]["name"].stringOrNull)
        assertEquals("granny smith", table["fruit"][0]["variety"][1]["name"].stringOrNull)
        assertEquals("banana", table["fruit"][1]["name"].stringOrNull)
        assertEquals("plantain", table["fruit"][1]["variety"][0]["name"].stringOrNull)
    }

    @Test
    fun `an empty literal string reads as empty`() {
        val table = parseDocument("foo = ''")
        assertEquals("", table["foo"].stringOrNull)
    }

    @Test
    fun `a multi-line string of blank lines keeps all but the trimmed first newline`() {
        val table = parseDocument("foo = \"\"\"\n\n\n\"\"\"")
        assertEquals("\n\n", table["foo"].stringOrNull)
    }

    @Test
    fun `a line-ending backslash trims a CRLF and the whitespace after it`() {
        val table = parseDocument(
            "\n        foo = \"\"\"\\\r\n\"\"\"\n        bar = \"\"\"\\\r\n   \r\n   \r\n   a\"\"\"\n    ",
        )
        assertEquals("", table["foo"].stringOrNull)
        assertEquals("a", table["bar"].stringOrNull)
    }

    @Test
    fun `floats with exponents and underscores decode to their values`() {
        fun t(actual: String, expected: Double) {
            val a = parseDocument("foo = $actual")
            assertEquals(expected, a["foo"].doubleOrNull)
        }

        t("1.0", 1.0)
        t("1.0e0", 1.0)
        t("1.0e+0", 1.0)
        t("1.0e-0", 1.0)
        t("1E-0", 1.0)
        t("1.001e-0", 1.001)
        t("2e10", 2e10)
        t("2e+10", 2e10)
        t("2e-10", 2e-10)
        t("2_0.0", 20.0)
        t("2_0.0_0e1_0", 20.0e10)
        t("2_0.1_0e1_0", 20.1e10)
    }

    @Test
    fun `bare and quoted keys of any allowed spelling are read`() {
        val a = parseDocument(
            """
        foo = 3
        foo_3 = 3
        foo_-2--3--r23f--4-f2-4 = 3
        _ = 3
        - = 3
        8 = 8
        "a" = 3
        "!" = 3
        "a^b" = 3
        "\"" = 3
        "character encoding" = "value"
        'ʎǝʞ' = "value"
    """,
        )
        for (key in listOf("foo", "-", "_", "8", "foo_3", "foo_-2--3--r23f--4-f2-4", "a", "!", "\"")) {
            assertTrue(a.containsKey(key), key)
        }
        assertTrue(a.containsKey("character encoding"))
        assertTrue(a.containsKey("ʎǝʞ"))
    }

    @Test
    fun `quoted table names may hold spaces dots and quotes`() {
        val a = parseDocument(
            """
        [a."b"]
        ["f f"]
        ["f.f"]
        ["\""]
        ['a.a']
        ['""']
    """,
        )
        assertTrue(a["a"]["b"].tableOrNull != null)
        assertTrue(a["f f"].tableOrNull != null)
        assertTrue(a["f.f"].tableOrNull != null)
        assertTrue(a["\""].tableOrNull != null)
        assertTrue(a["\"\""].tableOrNull != null)
    }

    @Test
    fun `empty nested and multi-line inline tables parse`() {
        parseDocument("a = {}")
        parseDocument("a = {b=1}")
        parseDocument("a = {   b   =   1    }")
        parseDocument("a = {a=1,b=2}")
        parseDocument("a = {a=1,b=2,c={}}")
        parseDocument("a = {a=[\n]}")
        parseDocument("a = {\"a\"=[\n]}")
        parseDocument("a = [\n{},\n{},\n]")
    }

    @Test
    fun `integers with underscores and signs decode to their values`() {
        fun t(actual: String, expected: Long) {
            val table = parseDocument("foo = $actual")
            assertEquals(expected, table["foo"].longOrNull)
        }

        t("1_0", 10)
        t("1_0_0", 100)
        t("1_000", 1000)
        t("+1_000", 1000)
        t("-1_000", -1000)
    }

    @Test
    fun `an empty basic string reads as empty`() {
        assertEquals("", parseDocument("foo = \"\"")["foo"].stringOrNull)
    }

    @Test
    fun `offset date-times print back as written`() {
        fun t(actual: String) {
            val toml = parseDocument("foo = $actual")
            assertEquals(actual, toml["foo"].datetimeOrNull.toString())
        }

        t("2016-09-09T09:09:09Z")
        t("2016-09-09T09:09:09.1Z")
        t("2016-09-09T09:09:09.2+10:00")
        t("2016-09-09T09:09:09.123456789-02:00")
    }

    @Test
    fun `a header sharing a dotted key's prefix prints after it as written`() {
        val input = """
p.a=4
[p.o]
"""
        val expected = """

p.a=4
[p.o]

"""
        assertData(expected, parseDocument(input).toString())
    }

    @Test
    fun `dotted keys with comments and blank lines between them print as written`() {
        val input = """
rust.unsafe_op_in_unsafe_fn = "deny"

rust.explicit_outlives_requirements = "warn"
# rust.unused_crate_dependencies = "warn"

clippy.cast_lossless = "warn"
clippy.doc_markdown = "warn"
clippy.exhaustive_enums = "warn"
"""
        val expected = """

rust.unsafe_op_in_unsafe_fn = "deny"

rust.explicit_outlives_requirements = "warn"
# rust.unused_crate_dependencies = "warn"

clippy.cast_lossless = "warn"
clippy.doc_markdown = "warn"
clippy.exhaustive_enums = "warn"

"""
        assertData(expected, parseDocument(input).toString())
    }

    private fun assertStringReprRoundtrip(input: String, expected: String) {
        val value = parseValue(input)
        val actual = value.toString()
        parseValue(actual)
        assertData(expected, actual)
    }

    @Test
    fun `a parsed string prints with its own spelling`() {
        assertStringReprRoundtrip("\"\"", "\"\"")
        assertStringReprRoundtrip("\"a\"", "\"a\"")

        assertStringReprRoundtrip("\"tab \\t tab\"", "\"tab \\t tab\"")
        assertStringReprRoundtrip("\"lf \\n lf\"", "\"lf \\n lf\"")
        assertStringReprRoundtrip("\"crlf \\r\\n crlf\"", "\"crlf \\r\\n crlf\"")
        assertStringReprRoundtrip("\"bell \\b bell\"", "\"bell \\b bell\"")
        assertStringReprRoundtrip("\"feed \\f feed\"", "\"feed \\f feed\"")
        assertStringReprRoundtrip("\"backslash \\\\ backslash\"", "\"backslash \\\\ backslash\"")

        assertStringReprRoundtrip("\"squote ' squote\"", "\"squote ' squote\"")
        assertStringReprRoundtrip("\"triple squote ''' triple squote\"", "\"triple squote ''' triple squote\"")
        assertStringReprRoundtrip("\"end squote '\"", "\"end squote '\"")

        assertStringReprRoundtrip("\"quote \\\" quote\"", "\"quote \\\" quote\"")
        assertStringReprRoundtrip(
            "\"triple quote \\\"\\\"\\\" triple quote\"",
            "\"triple quote \\\"\\\"\\\" triple quote\"",
        )
        assertStringReprRoundtrip("\"end quote \\\"\"", "\"end quote \\\"\"")
        assertStringReprRoundtrip("\"quoted \\\"content\\\" quoted\"", "\"quoted \\\"content\\\" quoted\"")
        assertStringReprRoundtrip("\"squoted 'content' squoted\"", "\"squoted 'content' squoted\"")
        assertStringReprRoundtrip(
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
        )
    }

    private fun assertStringValueRoundtrip(input: String, expected: String) {
        val parsed = parseValue(input)
        // Drops the written spelling.
        val value = Value.String(parsed.stringOrNull!!)
        val actual = value.toString()
        parseValue(actual)
        assertData(expected, actual)
    }

    @Test
    fun `a string built from text picks a spelling that parses back`() {
        assertStringValueRoundtrip("\"\"", "\"\"")
        assertStringValueRoundtrip("\"a\"", "\"a\"")

        assertStringValueRoundtrip("\"tab \\t tab\"", "\"tab \\t tab\"")
        assertStringValueRoundtrip("\"lf \\n lf\"", "\n\"\"\"\nlf \n lf\"\"\"\n")
        assertStringValueRoundtrip("\"crlf \\r\\n crlf\"", "\n\"\"\"\ncrlf \\r\n crlf\"\"\"\n")
        assertStringValueRoundtrip("\"bell \\b bell\"", "\"bell \\b bell\"")
        assertStringValueRoundtrip("\"feed \\f feed\"", "\"feed \\f feed\"")
        assertStringValueRoundtrip("\"backslash \\\\ backslash\"", "'backslash \\ backslash'")

        assertStringValueRoundtrip("\"squote ' squote\"", "\"squote ' squote\"")
        assertStringValueRoundtrip("\"triple squote ''' triple squote\"", "\"triple squote ''' triple squote\"")
        assertStringValueRoundtrip("\"end squote '\"", "\"end squote '\"")

        assertStringValueRoundtrip("\"quote \\\" quote\"", "'quote \" quote'")
        assertStringValueRoundtrip("\"triple quote \\\"\\\"\\\" triple quote\"", "'triple quote \"\"\" triple quote'")
        assertStringValueRoundtrip("\"end quote \\\"\"", "'end quote \"'")
        assertStringValueRoundtrip("\"quoted \\\"content\\\" quoted\"", "'quoted \"content\" quoted'")
        assertStringValueRoundtrip("\"squoted 'content' squoted\"", "\"squoted 'content' squoted\"")
        assertStringValueRoundtrip(
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
            "\"\"\"mixed quoted \"start\" 'end'' mixed quote\"\"\"",
        )
    }

    private fun recursionLimit(input: (Int) -> String) {
        val depths = listOf(1 to true, 20 to true, 300 to false)
        for ((depth, accepted) in depths) {
            val document = TomlDocument.parse(input(depth))
            assertEquals(accepted, document is Result.Success, "depth: $depth")
        }
    }

    @Test
    fun `arrays nested 300 deep are refused and 20 deep are read`() {
        recursionLimit { depth -> "x=${"[".repeat(depth)}${"]".repeat(depth)}" }
    }

    @Test
    fun `inline tables nested 300 deep are refused and 20 deep are read`() {
        recursionLimit { depth -> "x=${"{ x = ".repeat(depth)}true${"}".repeat(depth)}" }
    }

    @Test
    fun `a header key of 300 parts is refused and one of 20 is read`() {
        recursionLimit { depth -> "[x${".x".repeat(depth)}]" }
    }

    @Test
    fun `a dotted key of 300 parts is refused and one of 20 is read`() {
        recursionLimit { depth -> "x${".x".repeat(depth)} = true" }
    }

    @Test
    fun `a dotted key of 300 parts in an inline table is refused and one of 20 is read`() {
        recursionLimit { depth -> "x = { x${".x".repeat(depth)} = true }" }
    }

    @Test
    fun `garbage inside an inline table reports an extra assignment at column 5`() {
        val input = "={=<=u=="
        val err = TomlDocument.parse(input).errorOrFail()
        assertData(
            """
TOML parse error at line 1, column 5
  |
1 | ={=<=u==
  |     ^
extra assignment between key-value pairs, expected `,`

""",
            err.render(input),
        )
    }

    @Test
    fun `garbage inside a closed inline table reports an extra assignment at column 5`() {
        val input = "={=<=u==}"
        val err = TomlDocument.parse(input).errorOrFail()
        assertData(
            """
TOML parse error at line 1, column 5
  |
1 | ={=<=u==}
  |     ^
extra assignment between key-value pairs, expected `,`

""",
            err.render(input),
        )
    }

    @Test
    fun `a line of two equals signs reports an extra equals sign at column 2`() {
        val input = "==\n[._[._"
        val err = TomlDocument.parse(input).errorOrFail()
        assertData(
            """
TOML parse error at line 1, column 2
  |
1 | ==
  |  ^
extra `=`

""",
            err.render(input),
        )
    }
}
