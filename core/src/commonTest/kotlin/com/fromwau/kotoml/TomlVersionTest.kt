package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TomlVersion.V1_0] refuses exactly what TOML 1.1 added; the default accepts it. */
class TomlVersionTest {

    private fun refusedIn10(input: String, expected: String) {
        assertTrue(DocumentMut.parse(input).isOk(), "TOML 1.1 accepts:\n$input")
        assertData(expected, DocumentMut.parse(input, TomlVersion.V1_0).unwrapErr().render(input))
    }

    @Test
    fun newlineInInlineTable() {
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
    fun commentInInlineTable() {
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
    fun trailingCommaInInlineTable() {
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
    fun hexEscape() {
        refusedIn10(
            "s = \"\\x41\"\n",
            """
TOML parse error at line 1, column 7
  |
1 | s = "\x41"
  |       ^
missing escaped value, expected `b`, `f`, `n`, `r`, `\`, `"`, `u`, `U`

""",
        )
    }

    @Test
    fun escapeEscapeInKey() {
        refusedIn10(
            "\"a\\e\" = 1\n",
            """
TOML parse error at line 1, column 4
  |
1 | "a\e" = 1
  |    ^
missing escaped value, expected `b`, `f`, `n`, `r`, `\`, `"`, `u`, `U`

""",
        )
    }

    @Test
    fun escapedBackslashBeforeXIsAllowed() {
        assertEquals("\\x41", DocumentMut.parse("s = \"\\\\x41\"\n", TomlVersion.V1_0).unwrap()["s"].asStr())
    }

    @Test
    fun timeWithoutSeconds() {
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
    fun datetimeWithoutSeconds() {
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
    fun valueAndKeyParsingTakeTheVersion() {
        assertTrue(Value.parse("{ a = 1, }").isOk())
        assertTrue(!Value.parse("{ a = 1, }", TomlVersion.V1_0).isOk())
        assertTrue(Key.parseSimple("\"\\x41\"").isOk())
        assertTrue(!Key.parseSimple("\"\\x41\"", TomlVersion.V1_0).isOk())
        assertTrue(!Key.parse("a.\"\\e\"", TomlVersion.V1_0).isOk())
        assertTrue(!Item.parse("07:32", TomlVersion.V1_0).isOk())
    }
}
