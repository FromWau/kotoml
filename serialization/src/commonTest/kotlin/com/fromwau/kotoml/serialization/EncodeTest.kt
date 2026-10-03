package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EncodeTest {

    private val owners = Config("example.org", 8080, 30.seconds, Mode.UDP, listOf("alpha" to 1, "beta" to 2))

    @Test
    fun theOwnersModelWritesEveryField() {
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
            Toml.encodeToString(owners).unwrap(),
        )
    }

    @Test
    fun defaultsAreLeftOutUnlessEncodeDefaultsIsSet() {
        val config = Config("h", 1)
        assertEquals("hostname = \"h\"\nport = 1\n", Toml.encodeToString(config).unwrap())
        assertEquals(
            "hostname = \"h\"\nport = 1\ntimeout = \"15s\"\nmode = \"TCP\"\n",
            Toml { encodeDefaults = true }.encodeToString(config).unwrap(),
        )
    }

    @Test
    fun aNullFieldIsLeftOut() {
        assertEquals("required = \"r\"\n", Toml.encodeToString(Optional("r", nickname = null, note = null)).unwrap())
    }

    @Test
    fun aListOfClassesIsAnArrayOfTablesByDefault() {
        assertEquals(
            "name = \"f\"\n\n[[servers]]\nhost = \"a\"\n\n[[servers]]\nhost = \"b\"\nport = 1\n",
            Toml.encodeToString(Fleet("f", listOf(Server("a"), Server("b", 1)))).unwrap(),
        )
    }

    @Test
    fun aListOfClassesIsAnInlineArrayInTheInlineStyle() {
        val toml = Toml { tableListStyle = TableListStyle.InlineArray }
        assertEquals(
            "name = \"f\"\nservers = [{ host = \"a\" }, { host = \"b\", port = 1 }]\n",
            toml.encodeToString(Fleet("f", listOf(Server("a"), Server("b", 1)))).unwrap(),
        )
    }

    @Test
    fun aListInsideAnInlineValueIsAlwaysInline() {
        val groups = Groups(listOf(Fleet("f", listOf(Server("a")))))
        assertEquals(
            "groups = [{ name = \"f\", servers = [{ host = \"a\" }] }]\n",
            Toml { tableListStyle = TableListStyle.InlineArray }.encodeToString(groups).unwrap(),
        )
        assertEquals(
            "cells = [[{ host = \"a\" }]]\n",
            Toml.encodeToString(Matrix(listOf(listOf(Server("a"))))).unwrap(),
        )
    }

    @Test
    fun aListInsideAnArrayOfTablesElementIsItsOwnArrayOfTables() {
        val groups = Groups(listOf(Fleet("f", listOf(Server("a")))))
        assertEquals(
            "[[groups]]\nname = \"f\"\n\n[[groups.servers]]\nhost = \"a\"\n",
            Toml.encodeToString(groups).unwrap(),
        )
    }

    @Test
    fun anEmptyListOfClassesIsAnEmptyArray() {
        assertEquals(
            "name = \"f\"\nservers = []\n",
            Toml { encodeDefaults = true }.encodeToString(Fleet("f")).unwrap(),
        )
    }

    @Test
    fun listsOfScalarsAndListsAreInlineArrays() {
        assertEquals("rows = [[1, 2], []]\n", Toml.encodeToString(Grid(listOf(listOf(1, 2), emptyList()))).unwrap())
    }

    @Test
    fun aNullInsideAListIsRefused() {
        assertEquals(
            TomlEncodeError.NullElement("values[1]"),
            Toml.encodeToString(MaybeList(listOf(1, null))).unwrapErr(),
        )
    }

    @Test
    fun nestedClassesAreTables() {
        val app = App("t", database = Database("db", Pool(8, 5.minutes)))
        assertEquals(
            "title = \"t\"\n\n[database]\nurl = \"db\"\n\n[database.pool]\nsize = 8\nidle = \"5m\"\n",
            Toml.encodeToString(app).unwrap(),
        )
    }

    @Test
    fun anEnumIsWrittenAsItsSerialName() {
        assertEquals(
            "hostname = \"h\"\nport = 1\nmode = \"udp\"\n",
            Toml.encodeToString(Config("h", 1, mode = Mode.UDP)).unwrap(),
        )
    }

    @Test
    fun aSealedValueCarriesItsSubtypeUnderTheTypeKey() {
        assertEquals(
            "[[shapes]]\ntype = \"circle\"\nradius = 1.5\n\n[[shapes]]\ntype = \"point\"\n",
            Toml.encodeToString(Drawing(listOf(Circle(1.5), Point))).unwrap(),
        )
    }

    @Test
    fun aSealedValueCarriesItsSubtypeUnderACustomKey() {
        assertEquals(
            "[shape]\nkind = \"square\"\nside = 2.0\n",
            Toml { classDiscriminator = "kind" }.encodeToString(Holder(Square(2.0))).unwrap(),
        )
    }

    @Test
    fun aSubtypeWithAFieldNamedLikeTheDiscriminatorIsRefused() {
        assertEquals(
            TomlEncodeError.DiscriminatorConflict("shape", "side"),
            Toml { classDiscriminator = "side" }.encodeToString(Holder(Square(2.0))).unwrapErr(),
        )
    }

    @Test
    fun aDurationIsWrittenInKotlinsShortForm() {
        assertEquals("timeout = \"1m 30s\"\n", Toml.encodeToString(Timeout(90.seconds)).unwrap())
    }

    @Test
    fun aMapWithStringKeysIsATable() {
        assertEquals(
            "[ports]\nhttp = 80\n\"a.b\" = 1\n",
            Toml.encodeToString(Ports(mapOf("http" to 80, "a.b" to 1))).unwrap(),
        )
    }

    @Test
    fun aMapWithEnumKeysWritesTheirNames() {
        assertEquals(
            "[ports]\nTCP = 1\nudp = 2\n",
            Toml.encodeToString(ModePorts(mapOf(Mode.TCP to 1, Mode.UDP to 2))).unwrap(),
        )
    }

    @Test
    fun aMapWithNonStringKeysIsRefused() {
        assertEquals(
            TomlEncodeError.NonStringMapKey("ports"),
            Toml.encodeToString(NumberedPorts(mapOf(1 to "a"))).unwrapErr(),
        )
    }

    @Test
    fun aRootThatIsNotATableIsRefused() {
        assertEquals(TomlEncodeError.UnsupportedRoot("kotlin.Int"), Toml.encodeToString(1).unwrapErr())
        assertEquals(
            TomlEncodeError.UnsupportedRoot("kotlin.time.Duration"),
            Toml.encodeToString(1.minutes).unwrapErr(),
        )
    }

    @Test
    fun aTypeWithoutASerializerIsATypedError() {
        assertIs<TomlEncodeError.NoSerializer>(Toml.encodeToString(Any()).unwrapErr())
    }

    @Test
    fun aCustomSerializersExceptionBecomesASerializerError() {
        assertEquals(
            TomlEncodeError.Serializer("", "refused to write"),
            Toml.encodeToString(RefusingSerializer, Server("h")).unwrapErr(),
        )
    }

    @Test
    fun encodeToDocumentGivesAnEditableDocument() {
        val document = Toml.encodeToDocument(Server("h")).unwrap()
        document["port"] = Value.Integer(1L)
        assertEquals("host = \"h\"\nport = 1\n", document.toString())
    }
}
