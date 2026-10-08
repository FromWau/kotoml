package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which comments a replacement keeps: the ones above what it replaces, as a removal keeps them. Only comments inside
 * the replaced value or body go.
 */
class ReplacementCommentScopeTest {

    private fun replacing(
        text: String,
        edit: (TomlDocument) -> Result<*, TomlEditError>,
    ): String {
        val document = TomlDocument.parse(text).assertSuccess()
        edit(document).assertSuccess()
        return document.toString()
    }

    private val table = standardTableOf("q" to Item(Value.Integer(1L))).assertSuccess()

    @Test
    fun `a value replaced by a value keeps the comment after it`() {
        val text = "[server]\nport = 1 # comment\nhost = \"localhost\"\n"
        val expected = "[server]\nport = 2 # comment\nhost = \"localhost\"\n"
        assertEquals(expected, replacing(text) { it["server"].set("port", Value.Integer(2L)) })
        assertEquals(
            expected,
            replacing(text) { it["server"]["port"].assertSuccess().assign(Item(Value.Integer(2L))) },
        )
    }

    @Test
    fun `a comment in a value's own decor leaves the old value's comment`() {
        val text = "port = 1 # comment\n"
        assertEquals(
            "port = 2 # comment\n",
            replacing(text) { it.set("port", Value.Integer(2L, Decor(prefix = " ", suffix = " # new"))) },
        )
    }

    @Test
    fun `a comment above a dotted key replaced by a value stays above the value`() {
        val text = "c = 2\n# about a\na.b = 1 # same line\nz = 0\n"
        val expected = "c = 2\n# about a\na = 5\nz = 0\n"
        assertEquals(expected, replacing(text) { it.set("a", Value.Integer(5L)) })
        assertEquals(expected, replacing(text) { it["a"].assertSuccess().assign(Item(Value.Integer(5L))) })
    }

    @Test
    fun `the comments above a dotted key's later lines stay where they print when it is replaced`() {
        val text = "c = 2\n# about a\na.b = 1\n# about a.d\na.d = 3\nz = 0\n"
        assertEquals(
            "c = 2\n# about a\na = 5\n# about a.d\nz = 0\n",
            replacing(text) { it.set("a", Value.Integer(5L)) },
        )
    }

    @Test
    fun `a comment above a dotted key's later line after another key stays there when it is replaced`() {
        val text = "# about a\na.b = 1\nz = 0\n# about a.d\na.d = 3\n"
        assertEquals(
            "# about a\na = 5\nz = 0\n# about a.d\n",
            replacing(text) { it.set("a", Value.Integer(5L)) },
        )
    }

    @Test
    fun `a comment above a dotted key in a table replaced by a value stays above the value`() {
        val text = "[s]\n# about a\na.b = 1\nz = 0\n"
        assertEquals(
            "[s]\n# about a\na = 5\nz = 0\n",
            replacing(text) { it["s"].set("a", Value.Integer(5L)) },
        )
    }

    @Test
    fun `a comment above a dotted key replaced by a table goes above its header`() {
        val text = "c = 2\n# about a\na.b = 1\n# about a.d\na.d = 3\nz = 0\n"
        assertEquals(
            "c = 2\n# about a.d\nz = 0\n\n# about a\n[a]\nq = 1\n",
            replacing(text) { it.set("a", table) },
        )
    }

}
