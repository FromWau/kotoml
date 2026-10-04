package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class PortList(val ports: List<Int>)

/**
 * How `encodeInto` updates a list in place: elements are matched to the document's elements so each keeps the
 * comments written inside it. Equal elements match first, wherever they moved; the rest pair with the old element
 * that shares the most values; what is left is inserted or removed.
 */
class ListMergeTest {

    private val fleet = """
        name = "f"

        [[servers]]
        host = "a" # primary
        port = 80
        # fallback box
        [[servers]]
        host = "b"

    """.trimIndent()

    private fun merged(
        text: String,
        change: (Fleet) -> Fleet,
    ): String {
        val document = TomlDocument.parse(text).getOrFail()
        val value = Toml.decodeFromDocument<Fleet>(document).getOrFail()
        Toml.encodeInto(document, change(value)).getOrFail()
        return document.toString()
    }

    private fun mergedPorts(
        text: String,
        ports: List<Int>,
    ): String {
        val document = TomlDocument.parse(text).getOrFail()
        Toml.encodeInto(document, PortList(ports)).getOrFail()
        return document.toString()
    }

    @Test
    fun `an unchanged list leaves the document identical`() {
        assertEquals(fleet, merged(fleet) { it })
    }

    @Test
    fun `an edited element keeps the comments of its keys`() {
        val result = merged(fleet) { it.copy(servers = listOf(it.servers[0].copy(port = 81), it.servers[1])) }
        assertEquals(
            """
            name = "f"

            [[servers]]
            host = "a" # primary
            port = 81
            # fallback box
            [[servers]]
            host = "b"

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `an appended element is added after the others`() {
        val result = merged(fleet) { it.copy(servers = it.servers + Server("c")) }
        assertEquals(
            """
            name = "f"

            [[servers]]
            host = "a" # primary
            port = 80
            # fallback box
            [[servers]]
            host = "b"

            [[servers]]
            host = "c"

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `an element inserted at the front leaves the others with their comments`() {
        val result = merged(fleet) { it.copy(servers = listOf(Server("z")) + it.servers) }
        assertEquals(
            """
            name = "f"

            [[servers]]
            host = "z"

            [[servers]]
            host = "a" # primary
            port = 80
            # fallback box
            [[servers]]
            host = "b"

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `a removed element keeps the comment above it`() {
        val result = merged(fleet) { it.copy(servers = listOf(it.servers[0])) }
        assertEquals(
            """
            name = "f"

            [[servers]]
            host = "a" # primary
            port = 80
            # fallback box

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `reordered elements move with their comments and leave the blank lines in place`() {
        val result = merged(fleet) { it.copy(servers = it.servers.reversed()) }
        assertEquals(
            """
            name = "f"

            # fallback box
            [[servers]]
            host = "b"
            [[servers]]
            host = "a" # primary
            port = 80

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `an edited element next to an inserted one keeps its own comments`() {
        val result = merged(fleet) {
            it.copy(servers = listOf(Server("z"), it.servers[0].copy(port = 81), it.servers[1]))
        }
        assertEquals(
            """
            name = "f"

            [[servers]]
            host = "z"

            [[servers]]
            host = "a" # primary
            port = 81
            # fallback box
            [[servers]]
            host = "b"

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `an element changed in every field takes its place but none of its comments`() {
        val result = merged(fleet) { it.copy(servers = listOf(Server("x", 90), it.servers[1])) }
        assertEquals(
            """
            name = "f"

            [[servers]]
            host = "x"
            port = 90
            # fallback box
            [[servers]]
            host = "b"

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `a changed number in a list keeps the comment on its line`() {
        val text = "ports = [\n  8080, # http\n  8443, # tls\n]\n"
        assertEquals("ports = [\n  8080, # http\n  9443, # tls\n]\n", mergedPorts(text, listOf(8080, 9443)))
    }

    @Test
    fun `a number inserted at the front of a multi-line list takes the layout of its neighbours`() {
        val text = "ports = [\n  8080, # http\n  8443, # tls\n]\n"
        assertEquals(
            "ports = [\n  443,\n  8080, # http\n  8443, # tls\n]\n",
            mergedPorts(text, listOf(443, 8080, 8443)),
        )
    }
}
