package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which comments a replacement keeps: the ones above what it replaces, as a removal keeps them. Only comments inside
 * the replaced value or body go.
 */
class ReplacementCommentScopeTest {

    private fun replacing(
        text: String,
        edit: (TomlDocument) -> Unit,
    ): String {
        val document = TomlDocument.parse(text).getOrFail()
        edit(document)
        return document.toString()
    }

    private val table = standardTableOf("q" to Item(Value.Integer(1L)))

    @Test
    fun `a value replaced by a value keeps the comment after it`() {
        val text = "[server]\nport = 1 # comment\nhost = \"localhost\"\n"
        val expected = "[server]\nport = 2 # comment\nhost = \"localhost\"\n"
        assertEquals(expected, replacing(text) { it["server"]["port"] = Value.Integer(2L) })
        assertEquals(expected, replacing(text) { it["server"]["port"].assign(Item(Value.Integer(2L))).getOrFail() })
    }

    @Test
    fun `a value with a decor of its own replaces the old value's decor`() {
        val text = "port = 1 # comment\n"
        assertEquals(
            "port = 2 # new\n",
            replacing(text) { it["port"] = Value.Integer(2L, Decor(prefix = " ", suffix = " # new")) },
        )
    }

    @Test
    fun `a comment above a dotted key replaced by a value stays above the value`() {
        val text = "c = 2\n# about a\na.b = 1 # same line\nz = 0\n"
        val expected = "c = 2\n# about a\na = 5\nz = 0\n"
        assertEquals(expected, replacing(text) { it["a"] = Value.Integer(5L) })
        assertEquals(expected, replacing(text) { it["a"].assign(Item(Value.Integer(5L))).getOrFail() })
    }

    @Test
    fun `the comments above a dotted key's later lines stay where they print when it is replaced`() {
        val text = "c = 2\n# about a\na.b = 1\n# about a.d\na.d = 3\nz = 0\n"
        assertEquals(
            "c = 2\n# about a\na = 5\n# about a.d\nz = 0\n",
            replacing(text) { it["a"] = Value.Integer(5L) },
        )
    }

    @Test
    fun `a comment above a dotted key's later line after another key stays there when it is replaced`() {
        val text = "# about a\na.b = 1\nz = 0\n# about a.d\na.d = 3\n"
        assertEquals(
            "# about a\na = 5\nz = 0\n# about a.d\n",
            replacing(text) { it["a"] = Value.Integer(5L) },
        )
    }

    @Test
    fun `a comment above a dotted key in a table replaced by a value stays above the value`() {
        val text = "[s]\n# about a\na.b = 1\nz = 0\n"
        assertEquals(
            "[s]\n# about a\na = 5\nz = 0\n",
            replacing(text) { it["s"]["a"] = Value.Integer(5L) },
        )
    }

    @Test
    fun `a comment above a dotted key replaced by a table goes above its header`() {
        val text = "c = 2\n# about a\na.b = 1\n# about a.d\na.d = 3\nz = 0\n"
        assertEquals(
            "c = 2\n# about a.d\nz = 0\n\n# about a\n[a]\nq = 1\n",
            replacing(text) { it["a"] = table },
        )
    }

    @Test
    fun `a table replaced by an empty item keeps the lines above its header as removing it does`() {
        val text = "x = 0\n\n# about t\n[t]\ny = 1\n\n# about u\n[u]\n"
        val removed = replacing(text) { it.remove("t") }
        assertEquals("x = 0\n\n# about t\n\n# about u\n[u]\n", removed)
        assertEquals(removed, replacing(text) { it["t"].assign(Item()).getOrFail() })
        assertEquals(removed, replacing(text) { it.put("t", Item()) })
    }

    @Test
    fun `a value replaced by an empty item keeps the comment above it and drops the one on its line`() {
        val text = "# about a\na = 1 # same line\nz = 0\n"
        val removed = replacing(text) { it.remove("a") }
        assertEquals("# about a\nz = 0\n", removed)
        assertEquals(removed, replacing(text) { it["a"].assign(Item()).getOrFail() })
    }

    @Test
    fun `a key emptied and written again prints the comment kept from it once above the next key`() {
        val text = "# about a\na = 1\nz = 0\n"
        assertEquals(
            "a = 2\n# about a\nz = 0\n",
            replacing(text) {
                it["a"].assign(Item()).getOrFail()
                it["a"] = Value.Integer(2L)
            },
        )
    }
}
