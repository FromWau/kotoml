package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [TomlVersion.V1_0] refuses exactly what TOML 1.1 added; the default accepts it. */
class TomlVersionTest {

    private fun refusedIn10(input: String, expected: String) {
        assertIs<Result.Success<*>>(TomlDocument.parse(input), "TOML 1.1 accepts:\n$input")
        assertSnapshot(expected, TomlDocument.parse(input, TomlVersion.V1_0).errorOrFail().render(input))
    }

    @Test
    fun `V1_0 refuses a newline in an inline table`() {
        refusedIn10(
            "t = {\n  a = 1 }\n",
            """
TOML parse error at line 1, column 6
  |
1 | t = {
  |      ^
inline tables cannot span lines in TOML 1.0

""",
        )
    }

    @Test
    fun `V1_0 refuses a comment in an inline table`() {
        refusedIn10(
            "t = { a = 1 # one\n}\n",
            """
TOML parse error at line 1, column 13
  |
1 | t = { a = 1 # one
  |             ^^^^^
inline tables cannot span lines in TOML 1.0

""",
        )
    }

    @Test
    fun `V1_0 refuses a trailing comma in an inline table`() {
        refusedIn10(
            "t = { a = 1, }\n",
            """
TOML parse error at line 1, column 12
  |
1 | t = { a = 1, }
  |            ^
inline tables cannot end with a comma in TOML 1.0

""",
        )
    }

    @Test
    fun `V1_0 refuses a hex escape`() {
        refusedIn10(
            "s = \"\\x41\"\n",
            """
TOML parse error at line 1, column 6
  |
1 | s = "\x41"
  |      ^^^^
TOML 1.0 has no `\x` escape

""",
        )
    }

    @Test
    fun `V1_0 refuses the escape character escape in a quoted key`() {
        refusedIn10(
            "\"a\\e\" = 1\n",
            """
TOML parse error at line 1, column 3
  |
1 | "a\e" = 1
  |   ^^
TOML 1.0 has no `\e` escape

""",
        )
    }

    @Test
    fun `V1_0 lists only its own escapes after an unknown one in a string`() {
        val input = "s = \"\\q\"\n"
        assertSnapshot(
            """
TOML parse error at line 1, column 7
  |
1 | s = "\q"
  |       ^
missing escaped value, expected `b`, `f`, `n`, `r`, `t`, `\`, `"`, `u`, `U`

""",
            TomlDocument.parse(input, TomlVersion.V1_0).errorOrFail().render(input),
        )
    }

    @Test
    fun `V1_0 lists only its own escapes after an unknown one in a quoted key`() {
        val input = "\"a\\q\" = 1\n"
        assertSnapshot(
            """
TOML parse error at line 1, column 4
  |
1 | "a\q" = 1
  |    ^
missing escaped value, expected `b`, `f`, `n`, `r`, `t`, `\`, `"`, `u`, `U`

""",
            TomlDocument.parse(input, TomlVersion.V1_0).errorOrFail().render(input),
        )
    }

    @Test
    fun `V1_0 reads an escaped backslash before an x as text`() {
        val doc = TomlDocument.parse("s = \"\\\\x41\"\n", TomlVersion.V1_0).getOrFail()
        assertEquals("\\x41", doc["s"].getOrFail().stringOrNull)
    }

    @Test
    fun `V1_0 refuses a time without seconds`() {
        refusedIn10(
            "t = 07:32\n",
            """
TOML parse error at line 1, column 5
  |
1 | t = 07:32
  |     ^^^^^
times need seconds in TOML 1.0

""",
        )
    }

    @Test
    fun `V1_0 refuses a date-time without seconds`() {
        refusedIn10(
            "t = 1979-05-27T07:32Z\n",
            """
TOML parse error at line 1, column 5
  |
1 | t = 1979-05-27T07:32Z
  |     ^^^^^^^^^^^^^^^^^
times need seconds in TOML 1.0

""",
        )
    }

    @Test
    fun `parsing a value or key under V1_0 refuses what V1_1 added`() {
        assertIs<Result.Success<*>>(Value.parse("{ a = 1, }"))
        assertIs<Result.Error<*>>(Value.parse("{ a = 1, }", TomlVersion.V1_0))
        assertIs<Result.Success<*>>(Key.parse("\"\\x41\""))
        assertIs<Result.Error<*>>(Key.parse("\"\\x41\"", TomlVersion.V1_0))
        assertIs<Result.Error<*>>(Key.parsePath("a.\"\\e\"", TomlVersion.V1_0))
        assertIs<Result.Error<*>>(Value.parse("07:32", TomlVersion.V1_0))
    }
}
