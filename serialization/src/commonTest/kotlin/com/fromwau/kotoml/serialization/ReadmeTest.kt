package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.render
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** The examples of this module's README, as written there. */
class ReadmeTest {

    private fun describe(text: String): String = when (val decoded = Toml.decodeFromString<Config>(text)) {
        is Result.Success -> "${decoded.value}"
        is Result.Error -> when (val error = decoded.error) {
            is TomlDecodeError.Parse -> error.error.render(text)
            is TomlDecodeError.MissingKey -> "${error.path} is required"
            else -> "$error"
        }
    }

    @Test
    fun `the README reads TOML into a class`() {
        val text = "hostname = \"example.org\"\nport = 8080\nmode = \"udp\"\n"
        assertEquals("Config(host=example.org, port=8080, timeout=15s, mode=UDP, knownServers=null)", describe(text))
        assertEquals("hostname is required", describe("port = 8080\n"))
    }

    @Test
    fun `the README writes a value as TOML`() {
        val text = Toml.encodeToString(Config("example.org", 8080, knownServers = listOf("alpha" to 1))).getOrNull()
        assertEquals(
            "hostname = \"example.org\"\nport = 8080\n\n[[knownServers]]\nfirst = \"alpha\"\nsecond = 1\n",
            text,
        )
    }

    @Test
    fun `the README updates a file in place`() {
        val document = TomlDocument.parse("# where to listen\nhostname = \"example.org\"\nport = 8080 # the default\n")
            .getOrNull()!!
        val config = Toml.decodeFromDocument<Config>(document).getOrNull()!!
        Toml.encodeInto(document, config.copy(port = 9090, mode = Mode.UDP))
        assertEquals(
            "# where to listen\nhostname = \"example.org\"\nport = 9090 # the default\nmode = \"udp\"\n",
            document.toString(),
        )
    }

    @Test
    fun `the README merges a list into the file`() {
        val document = TomlDocument.parse(
            "name = \"f\"\n\n[[servers]]\nhost = \"a\" # primary\n# fallback box\n[[servers]]\nhost = \"b\"\n",
        ).getOrNull()!!
        val fleet = Toml.decodeFromDocument<Fleet>(document).getOrNull()!!
        Toml.encodeInto(document, fleet.copy(servers = listOf(Server("z"), Server("a", 80), Server("b"))))
        assertEquals(
            "name = \"f\"\n\n[[servers]]\nhost = \"z\"\n\n[[servers]]\nhost = \"a\" # primary\nport = 80\n" +
                "# fallback box\n[[servers]]\nhost = \"b\"\n",
            document.toString(),
        )
    }

    @Test
    fun `the README configures the format`() {
        val toml = Toml {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "kind"
            tableListStyle = TableListStyle.InlineArray
        }
        assertEquals(
            "shapes = [{ kind = \"circle\", radius = 1.0 }]\n",
            toml.encodeToString(Drawing(listOf(Circle(1.0)))).unwrap(),
        )
        assertEquals(Server("h"), toml.decodeFromString<Server>("host = \"h\"\nextra = 1\n").unwrap())
        val derived = Toml(from = toml) { classDiscriminator = "type" }
        assertEquals(true, derived.ignoreUnknownKeys)
        assertEquals("type", derived.classDiscriminator)
    }

    @Test
    fun `the README hands an error on as JSON`() {
        val error = Toml.decodeFromString<Config>("port = 1\n").errorOrNull() ?: fail("expected an error")
        assertEquals(
            """{"type":"MissingKey","path":"hostname"}""",
            Json.encodeToString(TomlDecodeError.serializer(), error),
        )
    }

    @Test
    fun `the README's paths quote keys as TOML does`() {
        assertEquals(
            TomlDecodeError.TypeMismatch("ports.\"a.b\"", TomlType.Integer, TomlType.String),
            Toml.decodeFromString<Ports>("[ports]\n\"a.b\" = \"x\"\n").unwrapErr(),
        )
    }
}
