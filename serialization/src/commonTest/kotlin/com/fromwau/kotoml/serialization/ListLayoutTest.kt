package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.assertSuccess
import com.fromwau.kotoml.TomlDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable

@Serializable
private data class PortNumbers(val ports: List<Int>)

@Serializable
private data class Zone(val zone: String)

@Serializable
private data class Machine(
    val name: String,
    val meta: Zone = Zone(""),
)

@Serializable
private data class Cluster(val nodes: List<Machine>)

/** Where `encodeInto` puts the elements of a merged list, and the text around them. */
class ListLayoutTest {

    private inline fun <reified T> merged(
        text: String,
        toml: Toml = Toml,
        change: (T) -> T,
    ): String {
        val document = TomlDocument.parse(text).assertSuccess()
        val value = toml.decodeFromDocument<T>(document).assertSuccess()
        Toml.encodeInto(document, change(value)).assertSuccess()
        return document.toString()
    }

    @Test
    fun `a number appended to a multi-line list goes on a line of its own`() {
        val text = "ports = [\n  8080, # http\n  8443, # tls\n]\n"
        val result = merged<PortNumbers>(text) { PortNumbers(it.ports + 9000) }
        assertEquals("ports = [\n  8080, # http\n  8443, # tls\n  9000,\n]\n", result)
    }

    @Test
    fun `a number removed from a multi-line list keeps the comment above it`() {
        val text = "ports = [\n  # web\n  8080,\n  # secure\n  8443, # tls\n]\n"
        val result = merged<PortNumbers>(text) { PortNumbers(listOf(8080)) }
        assertEquals("ports = [\n  # web\n  8080,\n  # secure\n]\n", result)
    }

    @Test
    fun `numbers moved on one line keep the spacing of the line`() {
        val result = merged<PortNumbers>("ports = [1, 2, 3]\n") { PortNumbers(listOf(0, 3, 2, 1)) }
        assertEquals("ports = [0, 3, 2, 1]\n", result)
    }

    @Test
    fun `the first number removed from one line leaves the next right after the bracket`() {
        val result = merged<PortNumbers>("ports = [1, 2, 3]\n") { PortNumbers(listOf(2, 3)) }
        assertEquals("ports = [2, 3]\n", result)
    }

    @Test
    fun `numbers added to an empty list are written on one line`() {
        val result = merged<PortNumbers>("ports = []\n") { PortNumbers(listOf(1, 2)) }
        assertEquals("ports = [1, 2]\n", result)
    }

    @Test
    fun `reordered tables move with the tables under them`() {
        val text = "[[nodes]]\nname = \"a\"\n[nodes.meta]\nzone = \"x\" # here\n\n# second\n[[nodes]]\nname = \"b\"\n" +
            "[nodes.meta]\nzone = \"y\"\n"
        val result = merged<Cluster>(text) { Cluster(it.nodes.reversed()) }
        assertEquals(
            "# second\n[[nodes]]\nname = \"b\"\n[nodes.meta]\nzone = \"y\"\n\n[[nodes]]\nname = \"a\"\n[nodes.meta]\n" +
                "zone = \"x\" # here\n",
            result,
        )
    }

    @Test
    fun `a table inserted at the front prints above the first element when other tables sit between the elements`() {
        val text = "name = \"f\"\n\n[[servers]]\nhost = \"a\"\n\n[other]\nk = 1\n\n[[servers]]\nhost = \"b\"\n"
        val result = merged<Fleet>(text, Toml { ignoreUnknownKeys = true }) {
            it.copy(servers = listOf(Server("z")) + it.servers)
        }
        assertEquals(
            "name = \"f\"\n\n[[servers]]\nhost = \"z\"\n\n[[servers]]\nhost = \"a\"\n\n[other]\nk = 1\n\n" +
                "[[servers]]\nhost = \"b\"\n",
            result,
        )
    }

    @Test
    fun `an edited inline table in a list keeps the comment after it`() {
        val text = "name = \"f\"\nservers = [\n  { host = \"a\", port = 80 }, # primary\n  { host = \"b\" },\n]\n"
        val result = merged<Fleet>(text) {
            it.copy(servers = listOf(it.servers[0].copy(port = 81), it.servers[1], Server("c")))
        }
        assertEquals(
            "name = \"f\"\nservers = [\n  { host = \"a\", port = 81 }, # primary\n  { host = \"b\" },\n" +
                "  { host = \"c\" },\n]\n",
            result,
        )
    }
}
