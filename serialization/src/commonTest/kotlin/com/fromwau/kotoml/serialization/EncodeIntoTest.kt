package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EncodeIntoTest {

    private val handWritten = """
        # Application settings
        title = "Kotoml"   # shown in the window

        # Who to blame
        "owner" = 'someone'
        legacy = true # not in the model
        tags = [ "x" ,  "y" ] # labels

        [database]
        url = "postgres://localhost"  # local only
        pool.size = 4
        timeout = 3

        # The fleet
        [[servers]]
        host = "alpha"

        [[servers]]
        host = "beta"
        port = 9090

    """.trimIndent()

    private val lenient = Toml { ignoreUnknownKeys = true }

    private fun updated(
        text: String,
        toml: Toml = Toml,
        change: (App) -> App,
    ): String {
        val document = TomlDocument.parse(text).unwrap()
        val app = lenient.decodeFromDocument<App>(document).unwrap()
        toml.encodeInto(document, change(app)).unwrap()
        return document.toString()
    }

    @Test
    fun aHandFormattedDocumentKeepsItsFormattingWhenValuesChange() {
        val result = updated(handWritten) { app ->
            app.copy(
                title = "Kotoml 2",
                owner = null,
                database = app.database.copy(pool = Pool(8, 5.minutes)),
                tags = listOf("z"),
                servers = app.servers + Server("gamma"),
            )
        }
        assertEquals(
            """
            # Application settings
            title = "Kotoml 2"   # shown in the window

            # Who to blame
            legacy = true # not in the model
            tags = ["z"] # labels

            [database]
            url = "postgres://localhost"  # local only
            pool.size = 8
            pool.idle = "5m"
            timeout = 3

            # The fleet
            [[servers]]
            host = "alpha"

            [[servers]]
            host = "beta"
            port = 9090

            [[servers]]
            host = "gamma"

            """.trimIndent(),
            result,
        )
    }

    @Test
    fun anUnchangedValueLeavesTheDocumentByteForByte() {
        assertEquals(handWritten, updated(handWritten) { it })
    }

    @Test
    fun anUnchangedValueKeepsEverySpelling() {
        val text = "hostname = 'example.org'  \r\nport = 0x1F90\r\ntimeout = \"PT30S\"\r\nmode = 'udp'\r\n" +
            "knownServers = [ { first = 'a', second = 0o1 } ]\r\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Toml.decodeFromDocument<Config>(document).unwrap()).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun newFieldsAreAppendedToTheirTable() {
        val text = "# server\nhostname = \"h\" # name\nport = 1\n\n[extra]\nkept = true\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml { ignoreUnknownKeys = true }.encodeInto(document, Config("h", 1, mode = Mode.UDP)).unwrap()
        assertEquals(
            "# server\nhostname = \"h\" # name\nport = 1\nmode = \"udp\"\n\n[extra]\nkept = true\n",
            document.toString(),
        )
    }

    @Test
    fun aNewNestedClassIsAppendedAsATable() {
        val result = updated("title = \"t\"\n[database]\nurl = \"u\"\n") {
            it.copy(database = it.database.copy(pool = Pool(2)))
        }
        assertEquals("title = \"t\"\n[database]\nurl = \"u\"\n\n[database.pool]\nsize = 2\n", result)
    }

    @Test
    fun aFieldSetToNullRemovesItsKey() {
        val document = TomlDocument.parse("required = \"r\"\nnickname = \"n\" # nick\nnote = \"x\"\n").unwrap()
        Toml.encodeInto(document, Optional("r", nickname = null, note = null)).unwrap()
        assertEquals("required = \"r\"\n", document.toString())
    }

    @Test
    fun `a field set to null keeps the comments above its key`() {
        val text = "required = \"r\"\n\n# what to call them\nnickname = \"n\" # nick\n# a note\nnote = \"x\"\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Optional("r", nickname = null, note = "x")).unwrap()
        assertEquals("required = \"r\"\n\n# what to call them\n# a note\nnote = \"x\"\n", document.toString())
    }

    @Test
    fun `a nested field set to null keeps the comments above its dotted key`() {
        val text = "title = \"t\"\n[database]\nurl = \"u\"\n# pool size\npool.size = 4\n# idle time\n" +
            "pool.idle = \"5m\"\n# unknown\ntimeout = 3\n"
        val result = updated(text) { it.copy(database = it.database.copy(pool = Pool(4, idle = null))) }
        assertEquals(
            "title = \"t\"\n[database]\nurl = \"u\"\n# pool size\npool.size = 4\n# idle time\n# unknown\ntimeout = 3\n",
            result,
        )
    }

    @Test
    fun unknownKeysSurviveWhateverIgnoreUnknownKeysSays() {
        val text = "host = \"a\"\nport = 1\nlegacy = true # old\n"
        for (toml in listOf(Toml, lenient)) {
            val document = TomlDocument.parse(text).unwrap()
            toml.encodeInto(document, Server("b", 1)).unwrap()
            assertEquals("host = \"b\"\nport = 1\nlegacy = true # old\n", document.toString())
        }
    }

    @Test
    fun aNestedClassUpdatesItsTableInPlace() {
        val text = "title = \"t\"\n\n# where data lives\n[database] # primary\n# the url\nurl = \"u\"\n" +
            "[database.pool]\nsize = 4 # small\n"
        val result = updated(text) { it.copy(database = Database("v", Pool(16))) }
        assertEquals(
            "title = \"t\"\n\n# where data lives\n[database] # primary\n# the url\nurl = \"v\"\n" +
                "[database.pool]\nsize = 16 # small\n",
            result,
        )
    }

    @Test
    fun aQuotedOrDottedKeyKeepsItsSpelling() {
        val text = "\"title\" = \"t\"\ndatabase . url = \"u\" # dotted\ndatabase.\"pool\".size = 1\n"
        val result = updated(text) { it.copy(title = "s", database = Database("v", Pool(2))) }
        assertEquals("\"title\" = \"s\"\ndatabase . url = \"v\" # dotted\ndatabase.\"pool\".size = 2\n", result)
    }

    @Test
    fun aListReplacesItsArrayButKeepsTheArraysDecor() {
        val text = "title = \"t\"\ntags = [\n  \"x\", # first\n  \"y\",\n] # labels\n[database]\nurl = \"u\"\n"
        val result = updated(text) { it.copy(tags = listOf("x", "z")) }
        assertEquals("title = \"t\"\ntags = [\"x\", \"z\"] # labels\n[database]\nurl = \"u\"\n", result)
    }

    @Test
    fun aListKeepsTheFormTheDocumentWroteIt() {
        val inline = "name = \"f\"\nservers = [{ host = \"a\" }]\n"
        val fleet = Fleet("f", listOf(Server("b")))
        val inlineDocument = TomlDocument.parse(inline).unwrap()
        Toml.encodeInto(inlineDocument, fleet).unwrap()
        assertEquals("name = \"f\"\nservers = [{ host = \"b\" }]\n", inlineDocument.toString())

        val tables = "name = \"f\"\n\n[[servers]] # first\nhost = \"a\"\n"
        val tablesDocument = TomlDocument.parse(tables).unwrap()
        Toml { tableListStyle = TableListStyle.InlineArray }.encodeInto(tablesDocument, fleet).unwrap()
        assertEquals("name = \"f\"\n\n[[servers]] # first\nhost = \"b\"\n", tablesDocument.toString())
    }

    @Test
    fun aFieldAtItsDefaultUpdatesAKeyButAddsNone() {
        val document = TomlDocument.parse("hostname = \"h\"\nport = 1\ntimeout = \"30s\" # slow\n").unwrap()
        Toml.encodeInto(document, Config("h", 1, timeout = 15.seconds)).unwrap()
        assertEquals("hostname = \"h\"\nport = 1\ntimeout = \"15s\" # slow\n", document.toString())
    }

    @Test
    fun aNestedClassAtItsDefaultAddsNoKeysToItsTable() {
        val text = "url = \"u\"\n[pool] # defaults\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Database("u")).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun aFieldAtItsDefaultIsAddedWhenEncodeDefaultsIsSet() {
        val document = TomlDocument.parse("hostname = \"h\"\nport = 1\n").unwrap()
        Toml { encodeDefaults = true }.encodeInto(document, Config("h", 1)).unwrap()
        assertEquals("hostname = \"h\"\nport = 1\ntimeout = \"15s\"\nmode = \"TCP\"\n", document.toString())
    }

    @Test
    fun aMapAccountsForAllOfItsKeys() {
        val document = TomlDocument.parse("[ports]\nhttp = 80 # web\nftp = 21\n").unwrap()
        Toml.encodeInto(document, Ports(mapOf("http" to 8080, "ssh" to 22))).unwrap()
        assertEquals("[ports]\nhttp = 8080 # web\nssh = 22\n", document.toString())
    }

    @Test
    fun aChangedSubtypeDropsTheOldSubtypesKeys() {
        val document = TomlDocument.parse("[shape] # the shape\ntype = \"circle\"\nradius = 1.0\n").unwrap()
        Toml.encodeInto(document, Holder(Square(2.0))).unwrap()
        assertEquals("[shape] # the shape\ntype = \"square\"\nside = 2.0\n", document.toString())
    }

    @Test
    fun aDateReadIntoAStringWritesBackUnchanged() {
        val text = "text = 1979-05-27\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Toml.decodeFromDocument<Text>(document).unwrap()).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun aValueSpelledAnotherWayIsLeftAsItIsWhenItReadsTheSame() {
        val text = "date = \"1979-05-27\"\ninstant = 1979-05-27T07:32:00+02:00\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Toml.decodeFromDocument<Times>(document).unwrap()).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun anErrorLeavesTheDocumentUnchanged() {
        val text = "values = [1] # one\n"
        val document = TomlDocument.parse(text).unwrap()
        assertEquals(
            TomlEncodeError.NullElement("values[1]"),
            Toml.encodeInto(document, MaybeList(listOf(2, null))).unwrapErr(),
        )
        assertEquals(text, document.toString())
    }
}
