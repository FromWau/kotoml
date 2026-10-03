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
    fun `a hand-formatted document keeps its formatting when values change`() {
        val result = updated(handWritten) { app ->
            app.copy(
                title = "Kotoml 2",
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
            "owner" = 'someone'
            legacy = true # not in the model
            tags = [ "z" ] # labels

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
    fun `an unchanged value leaves the document identical byte for byte`() {
        assertEquals(handWritten, updated(handWritten) { it })
    }

    @Test
    fun `an unchanged value keeps every spelling`() {
        val text = "hostname = 'example.org'  \r\nport = 0x1F90\r\ntimeout = \"PT30S\"\r\nmode = 'udp'\r\n" +
            "knownServers = [ { first = 'a', second = 0o1 } ]\r\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Toml.decodeFromDocument<Config>(document).unwrap()).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun `new fields are appended to their table`() {
        val text = "# server\nhostname = \"h\" # name\nport = 1\n\n[extra]\nkept = true\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml { ignoreUnknownKeys = true }.encodeInto(document, Config("h", 1, mode = Mode.UDP)).unwrap()
        assertEquals(
            "# server\nhostname = \"h\" # name\nport = 1\nmode = \"udp\"\n\n[extra]\nkept = true\n",
            document.toString(),
        )
    }

    @Test
    fun `a new nested class is appended as a table`() {
        val result = updated("title = \"t\"\n[database]\nurl = \"u\"\n") {
            it.copy(database = it.database.copy(pool = Pool(2)))
        }
        assertEquals("title = \"t\"\n[database]\nurl = \"u\"\n\n[database.pool]\nsize = 2\n", result)
    }

    @Test
    fun `unknown keys survive whatever ignoreUnknownKeys says`() {
        val text = "host = \"a\"\nport = 1\nlegacy = true # old\n"
        for (toml in listOf(Toml, lenient)) {
            val document = TomlDocument.parse(text).unwrap()
            toml.encodeInto(document, Server("b", 1)).unwrap()
            assertEquals("host = \"b\"\nport = 1\nlegacy = true # old\n", document.toString())
        }
    }

    @Test
    fun `a nested class updates its table in place`() {
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
    fun `a quoted or dotted key keeps its spelling`() {
        val text = "\"title\" = \"t\"\ndatabase . url = \"u\" # dotted\ndatabase.\"pool\".size = 1\n"
        val result = updated(text) { it.copy(title = "s", database = Database("v", Pool(2))) }
        assertEquals("\"title\" = \"s\"\ndatabase . url = \"v\" # dotted\ndatabase.\"pool\".size = 2\n", result)
    }

    @Test
    fun `a list keeps the layout and comments of its array`() {
        val text = "title = \"t\"\ntags = [\n  \"x\", # first\n  \"y\",\n] # labels\n[database]\nurl = \"u\"\n"
        val result = updated(text) { it.copy(tags = listOf("x", "z")) }
        assertEquals(
            "title = \"t\"\ntags = [\n  \"x\", # first\n  \"z\",\n] # labels\n[database]\nurl = \"u\"\n",
            result,
        )
    }

    @Test
    fun `a list keeps the form the document wrote it in`() {
        val inline = "name = \"f\"\nservers = [{ host = \"a\" }]\n"
        val fleet = Fleet("f", listOf(Server("b")))
        val inlineDocument = TomlDocument.parse(inline).unwrap()
        Toml.encodeInto(inlineDocument, fleet).unwrap()
        assertEquals("name = \"f\"\nservers = [{ host = \"b\" }]\n", inlineDocument.toString())

        val tables = "name = \"f\"\n\n[[servers]] # first\nhost = \"a\"\n"
        val tablesDocument = TomlDocument.parse(tables).unwrap()
        Toml { tableListStyle = TableListStyle.InlineArray }.encodeInto(tablesDocument, fleet).unwrap()
        assertEquals("name = \"f\"\n\n[[servers]]\nhost = \"b\"\n", tablesDocument.toString())
    }

    @Test
    fun `a field at its default updates a key but adds none`() {
        val document = TomlDocument.parse("hostname = \"h\"\nport = 1\ntimeout = \"30s\" # slow\n").unwrap()
        Toml.encodeInto(document, Config("h", 1, timeout = 15.seconds)).unwrap()
        assertEquals("hostname = \"h\"\nport = 1\ntimeout = \"15s\" # slow\n", document.toString())
    }

    @Test
    fun `a nested class at its default adds no keys to its table`() {
        val text = "url = \"u\"\n[pool] # defaults\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Database("u")).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun `a field at its default is added when encodeDefaults is set`() {
        val document = TomlDocument.parse("hostname = \"h\"\nport = 1\n").unwrap()
        Toml { encodeDefaults = true }.encodeInto(document, Endpoint("h", 1)).unwrap()
        assertEquals("hostname = \"h\"\nport = 1\ntimeout = \"15s\"\nmode = \"TCP\"\n", document.toString())
    }

    @Test
    fun `a map adds updates and removes keys to match its entries`() {
        val document = TomlDocument.parse("[ports]\nhttp = 80 # web\nftp = 21\n").unwrap()
        Toml.encodeInto(document, Ports(mapOf("http" to 8080, "ssh" to 22))).unwrap()
        assertEquals("[ports]\nhttp = 8080 # web\nssh = 22\n", document.toString())
    }

    @Test
    fun `a changed subtype drops the old subtype's keys`() {
        val document = TomlDocument.parse("[shape] # the shape\ntype = \"circle\"\nradius = 1.0\n").unwrap()
        Toml.encodeInto(document, Holder(Square(2.0))).unwrap()
        assertEquals("[shape] # the shape\ntype = \"square\"\nside = 2.0\n", document.toString())
    }

    @Test
    fun `a date read into a string writes back as the date it was`() {
        val text = "text = 1979-05-27\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Toml.decodeFromDocument<Text>(document).unwrap()).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun `a value spelled another way is left as it is when it reads the same`() {
        val text = "date = \"1979-05-27\"\ninstant = 1979-05-27T07:32:00+02:00\n"
        val document = TomlDocument.parse(text).unwrap()
        Toml.encodeInto(document, Toml.decodeFromDocument<Times>(document).unwrap()).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun `an error leaves the document as it was`() {
        val text = "byte = 1 # one\n"
        val document = TomlDocument.parse(text).unwrap()
        assertEquals(
            TomlEncodeError.OutOfRange("long", "${ULong.MAX_VALUE}"),
            Toml.encodeInto(document, Unsigned(byte = 2u, long = ULong.MAX_VALUE)).unwrapErr(),
        )
        assertEquals(text, document.toString())
    }
}
