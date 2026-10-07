package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.Value
import com.fromwau.kotoml.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EncodeTest {

    private val owners = Config("example.org", 8080, 30.seconds, Mode.UDP, listOf("alpha" to 1, "beta" to 2))

    @Test
    fun `the owner's model writes every field`() {
        assertEquals(
            """
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

            """.trimIndent(),
            Toml.encodeToString(owners).assertSuccess(),
        )
    }

    @Test
    fun `defaults are left out unless encodeDefaults is set`() {
        val config = Endpoint("h", 1)
        assertEquals("hostname = \"h\"\nport = 1\n", Toml.encodeToString(config).assertSuccess())
        assertEquals(
            "hostname = \"h\"\nport = 1\ntimeout = \"15s\"\nmode = \"TCP\"\n",
            Toml { encodeDefaults = true }.encodeToString(config).assertSuccess(),
        )
    }

    @Test
    fun `a list of classes is an array of tables by default`() {
        assertEquals(
            "name = \"f\"\n\n[[servers]]\nhost = \"a\"\n\n[[servers]]\nhost = \"b\"\nport = 1\n",
            Toml.encodeToString(Fleet("f", listOf(Server("a"), Server("b", 1)))).assertSuccess(),
        )
    }

    @Test
    fun `a list of classes is an inline array in the inline style`() {
        val toml = Toml { tableListStyle = TableListStyle.InlineArray }
        assertEquals(
            "name = \"f\"\nservers = [{ host = \"a\" }, { host = \"b\", port = 1 }]\n",
            toml.encodeToString(Fleet("f", listOf(Server("a"), Server("b", 1)))).assertSuccess(),
        )
    }

    @Test
    fun `a list inside an inline value is always inline`() {
        val groups = Groups(listOf(Fleet("f", listOf(Server("a")))))
        assertEquals(
            "groups = [{ name = \"f\", servers = [{ host = \"a\" }] }]\n",
            Toml { tableListStyle = TableListStyle.InlineArray }.encodeToString(groups).assertSuccess(),
        )
        assertEquals(
            "cells = [[{ host = \"a\" }]]\n",
            Toml.encodeToString(Matrix(listOf(listOf(Server("a"))))).assertSuccess(),
        )
    }

    @Test
    fun `a list inside an array of tables element is its own array of tables`() {
        val groups = Groups(listOf(Fleet("f", listOf(Server("a")))))
        assertEquals(
            "[[groups]]\nname = \"f\"\n\n[[groups.servers]]\nhost = \"a\"\n",
            Toml.encodeToString(groups).assertSuccess(),
        )
    }

    @Test
    fun `an empty list of classes is an empty array`() {
        assertEquals(
            "name = \"f\"\nservers = []\n",
            Toml { encodeDefaults = true }.encodeToString(Fleet("f")).assertSuccess(),
        )
    }

    @Test
    fun `lists of scalars and lists are inline arrays`() {
        assertEquals("rows = [[1, 2], []]\n", Toml.encodeToString(Grid(listOf(listOf(1, 2), emptyList()))).assertSuccess())
    }

    @Test
    fun `nested classes are tables`() {
        val app = App("t", database = Database("db", Pool(8, 5.minutes)))
        assertEquals(
            "title = \"t\"\n\n[database]\nurl = \"db\"\n\n[database.pool]\nsize = 8\nidle = \"5m\"\n",
            Toml.encodeToString(app).assertSuccess(),
        )
    }

    @Test
    fun `an enum is written as its serial name`() {
        assertEquals(
            "hostname = \"h\"\nport = 1\nmode = \"udp\"\n",
            Toml.encodeToString(Config("h", 1, mode = Mode.UDP)).assertSuccess(),
        )
    }

    @Test
    fun `a sealed value carries its subtype under the type key`() {
        assertEquals(
            "[[shapes]]\ntype = \"circle\"\nradius = 1.5\n\n[[shapes]]\ntype = \"point\"\n",
            Toml.encodeToString(Drawing(listOf(Circle(1.5), Point))).assertSuccess(),
        )
    }

    @Test
    fun `a sealed value carries its subtype under a custom key`() {
        assertEquals(
            "[shape]\nkind = \"square\"\nside = 2.0\n",
            Toml { classDiscriminator = "kind" }.encodeToString(Holder(Square(2.0))).assertSuccess(),
        )
    }

    @Test
    fun `a subtype with a field named like the discriminator is refused`() {
        assertEquals(
            TomlEncodeError.DiscriminatorConflict("shape", "side"),
            Toml { classDiscriminator = "side" }.encodeToString(Holder(Square(2.0))).assertError<TomlEncodeError.DiscriminatorConflict>(),
        )
    }

    @Test
    fun `a duration is written in Kotlin's short form`() {
        assertEquals("timeout = \"1m 30s\"\n", Toml.encodeToString(Timeout(90.seconds)).assertSuccess())
    }

    @Test
    fun `a map with string keys is a table`() {
        assertEquals(
            "[ports]\nhttp = 80\n\"a.b\" = 1\n",
            Toml.encodeToString(Ports(mapOf("http" to 80, "a.b" to 1))).assertSuccess(),
        )
    }

    @Test
    fun `a map with enum keys writes their names`() {
        assertEquals(
            "[ports]\nTCP = 1\nudp = 2\n",
            Toml.encodeToString(ModePorts(mapOf(Mode.TCP to 1, Mode.UDP to 2))).assertSuccess(),
        )
    }

    @Test
    fun `a map with non-string keys is refused`() {
        assertEquals(
            TomlEncodeError.NonStringMapKey("ports"),
            Toml.encodeToString(NumberedPorts(mapOf(1 to "a"))).assertError<TomlEncodeError.NonStringMapKey>(),
        )
    }

    @Test
    fun `a root that is not a table is refused`() {
        assertEquals(TomlEncodeError.UnsupportedRoot("kotlin.Int"), Toml.encodeToString(1).assertError<TomlEncodeError.UnsupportedRoot>())
        assertEquals(
            TomlEncodeError.UnsupportedRoot("kotlin.time.Duration"),
            Toml.encodeToString(1.minutes).assertError<TomlEncodeError.UnsupportedRoot>(),
        )
    }

    @Test
    fun `a type without a serializer is a typed error`() {
        Toml.encodeToString(Any()).assertError<TomlEncodeError.NoSerializer>()
    }

    @Test
    fun `a custom serializer's exception becomes a Serializer error`() {
        assertEquals(
            TomlEncodeError.Serializer("", "refused to write"),
            Toml.encodeToString(RefusingSerializer, Server("h")).assertError<TomlEncodeError.Serializer>(),
        )
    }

    @Test
    fun `a map of classes prints no header of its own`() {
        assertEquals(
            "name = \"x\"\n\n[tool.ruff]\nhost = \"h\"\n",
            Toml.encodeToString(Tools("x", mapOf("ruff" to Server("h")))).assertSuccess(),
        )
    }

    @Test
    fun `a map of classes encoded into a document adds no header of its own`() {
        val document = TomlDocument.parse("name = \"x\"\n").assertSuccess()
        Toml.encodeInto(document, Tools("x", mapOf("ruff" to Server("h")))).assertSuccess()
        assertEquals("name = \"x\"\n\n[tool.ruff]\nhost = \"h\"\n", document.toString())
    }

    @Test
    fun `a map of classes encoded into a document keeps a header read from it`() {
        val document = TomlDocument.parse("name = \"x\"\n\n[tool]\n").assertSuccess()
        Toml.encodeInto(document, Tools("x", mapOf("ruff" to Server("h")))).assertSuccess()
        assertEquals("name = \"x\"\n\n[tool]\n\n[tool.ruff]\nhost = \"h\"\n", document.toString())
    }

    @Test
    fun `encodeToDocument gives an editable document`() {
        val document = Toml.encodeToDocument(Server("h")).assertSuccess()
        document["port"] = Value.Integer(1L)
        assertEquals("host = \"h\"\nport = 1\n", document.toString())
    }
}
