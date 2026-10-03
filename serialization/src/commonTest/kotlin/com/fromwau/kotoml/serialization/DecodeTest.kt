package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DecodeTest {

    @Test
    fun `the owner's model reads every field`() {
        val text = """
            hostname = "example.org"
            port = 8080
            timeout = "30s"
            mode = "udp"

            [[knownServers]]
            first = "alpha"
            second = 1

            [[knownServers]]
            first = "beta"
            second = 2
        """.trimIndent()
        assertEquals(
            Config("example.org", 8080, 30.seconds, Mode.UDP, listOf("alpha" to 1, "beta" to 2)),
            Toml.decodeFromString<Config>(text).unwrap(),
        )
    }

    @Test
    fun `missing keys take the field defaults`() {
        assertEquals(
            Config("example.org", 8080),
            Toml.decodeFromString<Config>("hostname = \"example.org\"\nport = 8080\n").unwrap(),
        )
    }

    @Test
    fun `a missing required key names its full path`() {
        val text = "title = \"t\"\n[database]\n[database.pool]\nsize = 2\n"
        assertEquals(
            TomlDecodeError.MissingKey("database.url"),
            Toml.decodeFromString<App>(text).unwrapErr(),
        )
    }

    @Test
    fun `an unknown key is refused with its path`() {
        val text = "name = \"f\"\n[[servers]]\nhost = \"a\"\n[[servers]]\nhost = \"b\"\nextra = 1\n"
        assertEquals(
            TomlDecodeError.UnknownKey("servers[1].extra"),
            Toml.decodeFromString<Fleet>(text).unwrapErr(),
        )
    }

    @Test
    fun `an unknown key is skipped when ignoreUnknownKeys is set`() {
        val text = "name = \"f\"\nextra = 1\n[[servers]]\nhost = \"a\"\nmore = { a = 1 }\n"
        assertEquals(
            Fleet("f", listOf(Server("a"))),
            Toml { ignoreUnknownKeys = true }.decodeFromString<Fleet>(text).unwrap(),
        )
    }

    @Test
    fun `an unknown key with a quoted name is spelled as TOML quotes it`() {
        assertEquals(
            TomlDecodeError.UnknownKey("\"a.b\""),
            Toml.decodeFromString<Server>("host = \"h\"\n\"a.b\" = 1\n").unwrapErr(),
        )
    }

    @Test
    fun `an enum reads its serial name`() {
        val text = "hostname = \"h\"\nport = 1\nmode = \"udp\"\n"
        assertEquals(Mode.UDP, Toml.decodeFromString<Config>(text).unwrap().mode)
    }

    @Test
    fun `an enum name is case sensitive and the error lists the names`() {
        val text = "hostname = \"h\"\nport = 1\nmode = \"tcp\"\n"
        assertEquals(
            TomlDecodeError.UnknownEnumValue("mode", "tcp", listOf("TCP", "udp")),
            Toml.decodeFromString<Config>(text).unwrapErr(),
        )
    }

    @Test
    fun `a sealed value is picked by the type key`() {
        val text = "[[shapes]]\ntype = \"circle\"\nradius = 1.5\n\n[[shapes]]\ntype = \"point\"\n"
        assertEquals(
            Drawing(listOf(Circle(1.5), Point)),
            Toml.decodeFromString<Drawing>(text).unwrap(),
        )
    }

    @Test
    fun `a sealed value reads a custom discriminator`() {
        val toml = Toml { classDiscriminator = "kind" }
        assertEquals(
            Holder(Square(2.0)),
            toml.decodeFromString<Holder>("shape = { kind = \"square\", side = 2.0 }\n").unwrap(),
        )
    }

    @Test
    fun `an unknown subtype is a typed error listing the subtypes`() {
        assertEquals(
            TomlDecodeError.UnknownSubtype("shape", "triangle", listOf("circle", "point", "square")),
            Toml.decodeFromString<Holder>("[shape]\ntype = \"triangle\"\n").unwrapErr(),
        )
    }

    @Test
    fun `a missing discriminator is a missing key`() {
        assertEquals(
            TomlDecodeError.MissingKey("shape.type"),
            Toml.decodeFromString<Holder>("[shape]\nradius = 1.0\n").unwrapErr(),
        )
    }

    @Test
    fun `a pair reads first and second`() {
        val text = "hostname = \"h\"\nport = 1\nknownServers = [{ first = \"a\", second = 2 }]\n"
        assertEquals(listOf("a" to 2), Toml.decodeFromString<Config>(text).unwrap().knownServers)
    }

    @Test
    fun `a duration reads Kotlin's short form`() {
        assertEquals(Timeout(90.seconds), Toml.decodeFromString<Timeout>("timeout = \"1m 30s\"\n").unwrap())
    }

    @Test
    fun `a duration reads ISO 8601`() {
        assertEquals(Timeout(15.seconds), Toml.decodeFromString<Timeout>("timeout = \"PT15S\"\n").unwrap())
    }

    @Test
    fun `an unreadable duration is unparsable`() {
        assertEquals(
            TomlDecodeError.Unparsable("timeout", "soon", "kotlin.time.Duration"),
            Toml.decodeFromString<Timeout>("timeout = \"soon\"\n").unwrapErr(),
        )
    }

    @Test
    fun `nested classes read their tables`() {
        val text = "title = \"t\"\n\n[database]\nurl = \"db\"\n\n[database.pool]\nsize = 8\nidle = \"5m\"\n"
        assertEquals(
            App("t", database = Database("db", Pool(8, 5.minutes))),
            Toml.decodeFromString<App>(text).unwrap(),
        )
    }

    @Test
    fun `nested classes read dotted keys and inline tables`() {
        val text = "title = \"t\"\ndatabase.url = \"db\"\ndatabase.pool = { size = 2 }\n"
        assertEquals(
            App("t", database = Database("db", Pool(2))),
            Toml.decodeFromString<App>(text).unwrap(),
        )
    }

    @Test
    fun `a list of classes reads an array of tables`() {
        val text = "name = \"f\"\n[[servers]]\nhost = \"a\"\n[[servers]]\nhost = \"b\"\nport = 1\n"
        assertEquals(
            Fleet("f", listOf(Server("a"), Server("b", 1))),
            Toml.decodeFromString<Fleet>(text).unwrap(),
        )
    }

    @Test
    fun `a list of classes reads an inline array of inline tables`() {
        val text = "name = \"f\"\nservers = [{ host = \"a\" }, { host = \"b\", port = 1 }]\n"
        assertEquals(
            Fleet("f", listOf(Server("a"), Server("b", 1))),
            Toml.decodeFromString<Fleet>(text).unwrap(),
        )
    }

    @Test
    fun `lists of lists read nested arrays`() {
        assertEquals(
            Grid(listOf(listOf(1, 2), emptyList())),
            Toml.decodeFromString<Grid>("rows = [[1, 2], []]\n").unwrap(),
        )
    }

    @Test
    fun `a map with string keys reads a table`() {
        assertEquals(
            Ports(mapOf("http" to 80, "a.b" to 1)),
            Toml.decodeFromString<Ports>("[ports]\nhttp = 80\n\"a.b\" = 1\n").unwrap(),
        )
    }

    @Test
    fun `a map with enum keys reads their names`() {
        assertEquals(
            ModePorts(mapOf(Mode.TCP to 1, Mode.UDP to 2)),
            Toml.decodeFromString<ModePorts>("ports = { TCP = 1, udp = 2 }\n").unwrap(),
        )
    }

    @Test
    fun `a map with an unknown enum key names the key`() {
        assertEquals(
            TomlDecodeError.UnknownEnumValue("ports.sctp", "sctp", listOf("TCP", "udp")),
            Toml.decodeFromString<ModePorts>("ports = { sctp = 1 }\n").unwrapErr(),
        )
    }

    @Test
    fun `a map with non-string keys is refused`() {
        assertEquals(
            TomlDecodeError.NonStringMapKey("ports"),
            Toml.decodeFromString<NumberedPorts>("ports = { 1 = \"a\" }\n").unwrapErr(),
        )
    }

    @Test
    fun `a map can be the root`() {
        assertEquals(
            mapOf("a" to 1L, "b" to 2L),
            Toml.decodeFromString<Map<String, Long>>("a = 1\nb = 2\n").unwrap(),
        )
    }

    @Test
    fun `a root that is not a table is refused`() {
        assertEquals(
            TomlDecodeError.UnsupportedRoot("kotlin.collections.ArrayList"),
            Toml.decodeFromString<List<Int>>("a = 1\n").unwrapErr(),
        )
    }

    @Test
    fun `a type without a serializer is a typed error`() {
        assertIs<TomlDecodeError.NoSerializer>(Toml.decodeFromString<Any>("a = 1\n").unwrapErr())
    }

    @Test
    fun `text that is not TOML is a parse error`() {
        assertIs<TomlDecodeError.Parse>(Toml.decodeFromString<Server>("host = \n").unwrapErr())
    }

    @Test
    fun `a value of the wrong type names both types`() {
        assertEquals(
            TomlDecodeError.TypeMismatch("port", TomlType.Integer, TomlType.String),
            Toml.decodeFromString<Server>("host = \"h\"\nport = \"80\"\n").unwrapErr(),
        )
    }

    @Test
    fun `a table where a list is expected is a type mismatch`() {
        assertEquals(
            TomlDecodeError.TypeMismatch("servers", TomlType.Array, TomlType.Table),
            Toml.decodeFromString<Fleet>("name = \"f\"\n[servers]\nhost = \"a\"\n").unwrapErr(),
        )
    }

    @Test
    fun `decodeFromDocument reads a parsed document without changing it`() {
        val text = "host = \"h\" # the host\n"
        val document = TomlDocument.parse(text).unwrap()
        assertEquals(Server("h"), Toml.decodeFromDocument<Server>(document).unwrap())
        assertEquals(text, document.toString())
    }

    @Test
    fun `a quoted date that does not exist is unparsable`() {
        val error = Toml.decodeFromString<Times>("date = \"2024-02-30\"\n").unwrapErr()
        assertEquals(TomlDecodeError.Unparsable("date", "2024-02-30", "kotlinx.datetime.LocalDate"), error)
    }

    @Test
    fun `a custom serializer's exception becomes a Serializer error`() {
        val failure = Toml.decodeFromString(RefusingSerializer, "a = 1\n").unwrapErr()
        assertIs<TomlDecodeError.Serializer>(failure)
        assertEquals("", failure.path)
        assertTrue(failure.detail.orEmpty().contains("refused"))
    }
}
