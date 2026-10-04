package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A model is written only as deep as kotoml reads back, whichever way its lists are written. */
class DepthTest {

    @Serializable
    private data class Node(val n: Int = 0, val kids: List<Node> = emptyList())

    /** A node whose deepest list element is [levels] lists and classes below the root. */
    private fun nested(levels: Int): Node {
        var node = Node()
        repeat(levels / 2) { node = Node(it, listOf(node)) }
        return node
    }

    private val inline = Toml { tableListStyle = TableListStyle.InlineArray }

    @Test
    fun `a model nested to the limit is written and read back in either list style`() {
        val node = nested(TomlDocument.NESTING_LIMIT - 2)
        for (toml in listOf(Toml, inline)) {
            val text = toml.encodeToString(node).getOrFail()
            assertEquals(node, toml.decodeFromString<Node>(text).getOrFail())
        }
    }

    @Test
    fun `a model nested past the limit is TooDeep`() {
        for (toml in listOf(Toml, inline)) {
            val error = toml.encodeToString(nested(TomlDocument.NESTING_LIMIT + 20)).errorOrFail()
            assertIs<TomlEncodeError.TooDeep>(error)
        }
    }

    @Test
    fun `a model nested thousands deep is TooDeep instead of overflowing the stack`() {
        assertIs<TomlEncodeError.TooDeep>(Toml.encodeToString(nested(6000)).errorOrFail())
    }

    @Test
    fun `encodeInto refuses a model nested past the limit and leaves the document as it was`() {
        val document = TomlDocument.parse("n = 1\n").getOrFail()
        val error = Toml.encodeInto(document, Node.serializer(), nested(TomlDocument.NESTING_LIMIT + 20)).errorOrFail()
        assertIs<TomlEncodeError.TooDeep>(error)
        assertEquals("n = 1\n", document.toString())
    }
}
