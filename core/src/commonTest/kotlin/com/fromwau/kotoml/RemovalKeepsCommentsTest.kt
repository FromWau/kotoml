package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** Removing a key takes only its own text: the comment and blank lines above it stay where they print. */
class RemovalKeepsCommentsTest {

    private fun removing(
        text: String,
        edit: (TomlDocument) -> Unit,
    ): String {
        val document = TomlDocument.parse(text).getOrFail()
        edit(document)
        return document.toString()
    }

    @Test
    fun `a dotted table left empty prints its key as an empty table below the comment kept from it`() {
        val doc = TomlDocument.parse("[t]\n# about b\na.b = 1\nz = 2\n").getOrFail()
        doc["t"]["a"].getOrFail().standardTableOrNull!!.remove("b")
        assertEquals("[t]\n# about b\na = {}\nz = 2\n", doc.toString())
    }

    @Test
    fun `a dotted table in an inline table left empty prints its key as an empty table`() {
        val doc = TomlDocument.parse("a = { a.b = 1 }\n").getOrFail()
        doc["a"].getOrFail().inlineTableOrNull!!.getItem("a")!!.inlineTableOrNull!!.remove("b")
        assertEquals("a = { a = {} }\n", doc.toString())
    }

    @Test
    fun `an implicit table left holding nothing prints its header`() {
        val doc = TomlDocument.parse("[a.b]\nx = 1\n").getOrFail()
        doc["a"].getOrFail().standardTableOrNull!!.remove("b")
        assertEquals("[a]\n", doc.toString())
    }

    @Test
    fun `a table added after a removal prints below the comment kept from the removed table`() {
        val doc = TomlDocument.parse("[b]\ny = 1\n\n# about a\n[a]\nx = 1\n").getOrFail()
        doc.remove("a")
        doc["c"] = standardTableOf("z" to Item(Value.Integer(1L))).getOrFail()
        assertEquals("[b]\ny = 1\n\n# about a\n\n[c]\nz = 1\n", doc.toString())
    }

    @Test
    fun `a key in the middle leaves its comment above the next key`() {
        val text = "# the port to listen on\nport = 8080\n# bind address\nhost = \"0.0.0.0\"\n"
        assertEquals(
            "# the port to listen on\n# bind address\nhost = \"0.0.0.0\"\n",
            removing(text) { it.remove("port") },
        )
    }

    @Test
    fun `the first key leaves the lines above it at the top`() {
        val text = "\n# first\nport = 1\n\n# second\nhost = 2\n"
        assertEquals("\n# first\n\n# second\nhost = 2\n", removing(text) { it.remove("port") })
    }

    @Test
    fun `the last key of a table leaves its comment above the next header`() {
        val text = "[server]\nhost = 1\n# the port\nport = 2\n\n# client side\n[client]\nx = 1\n"
        assertEquals(
            "[server]\nhost = 1\n# the port\n\n# client side\n[client]\nx = 1\n",
            removing(text) { it["server"].getOrFail().standardTableOrNull!!.remove("port") },
        )
    }

    @Test
    fun `the last key of the document leaves its comment before the trailing text`() {
        val text = "a = 1\n# about b\nb = 2\n# the end\n"
        assertEquals("a = 1\n# about b\n# the end\n", removing(text) { it.remove("b") })
    }

    @Test
    fun `a comment on the key's own line goes with the key`() {
        val text = "a = 1 # one\n# about b\nb = 2 # two\nc = 3\n"
        assertEquals("a = 1 # one\n# about b\nc = 3\n", removing(text) { it.remove("b") })
    }

    @Test
    fun `an indented key leaves the lines above it and takes its indentation`() {
        val text = "  # about a\n  a = 1\n  b = 2\n"
        assertEquals("  # about a\n  b = 2\n", removing(text) { it.remove("a") })
    }

    @Test
    fun `lines ending in CRLF stay as they were`() {
        val text = "# about a\r\na = 1\r\nb = 2\r\n"
        assertEquals("# about a\r\nb = 2\r\n", removing(text) { it.remove("a") })
    }

    private val interleaved = "# about a.x\na.x = 1\n# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n"

    @Test
    fun `a key between dotted keys printed in read order leaves its comment in place`() {
        assertEquals(
            "# about a.x\na.x = 1\n# about b\n# about a.y\na.y = 3\nc = 4\n",
            removing(interleaved) { it.remove("b") },
        )
    }

    @Test
    fun `a dotted key removed through its table leaves its comment in place`() {
        assertEquals(
            "# about a.x\na.x = 1\n# about b\nb = 2\n# about a.y\nc = 4\n",
            removing(interleaved) { it["a"].getOrFail().standardTableOrNull!!.remove("y") },
        )
        assertEquals(
            "# about a.x\n# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n",
            removing(interleaved) { it["a"].getOrFail().standardTableOrNull!!.remove("x") },
        )
    }

    @Test
    fun `a dotted key removed whole leaves the comments above each of its lines`() {
        assertEquals(
            "# about a.x\n# about b\nb = 2\n# about a.y\nc = 4\n",
            removing(interleaved) { it.remove("a") },
        )
    }

    @Test
    fun `lines kept from a dotted key stay in place when a neighbour goes too`() {
        assertEquals(
            "# about a.x\na.x = 1\n# about b\n# about a.y\nc = 4\n",
            removing(interleaved) {
                it["a"].getOrFail().standardTableOrNull!!.remove("y")
                it.remove("b")
            },
        )
    }

    @Test
    fun `a dotted key made by hand leaves its comment above the next one`() {
        val document = TomlDocument()
        val pool = StandardTable()
        Item(pool).setLayout(TableLayout.Dotted).getOrFail()
        pool.put(Key("size", leafDecor = Decor(prefix = "# size\n")), Item(Value.Integer(1L)))
        pool.put(Key("idle", leafDecor = Decor(prefix = "# idle\n")), Item(Value.Integer(2L)))
        document["pool"] = pool
        document["timeout"] = Value.Integer(3L)
        document["pool"].getOrFail().standardTableOrNull!!.remove("size")
        assertEquals("# size\n# idle\npool.idle = 2\ntimeout = 3\n", document.toString())
        document["pool"].getOrFail().standardTableOrNull!!.remove("idle")
        assertEquals("# size\n# idle\npool = {}\ntimeout = 3\n", document.toString())
    }

    @Test
    fun `a sub-table leaves the lines above its header`() {
        val text = "[a]\nx = 1\n\n# about b\n[a.b]\n# inside b\ny = 2\n\n# about c\n[c]\nz = 3\n"
        assertEquals(
            "[a]\nx = 1\n\n# about b\n\n# about c\n[c]\nz = 3\n",
            removing(text) { it["a"].getOrFail().standardTableOrNull!!.remove("b") },
        )
    }

    @Test
    fun `a table leaves the lines above its first header and takes those above its sub-tables`() {
        val text = "# about a\n[a]\nx = 1\n# about c\n[c]\n# about a.b\n[a.b]\ny = 2\n"
        assertEquals("# about a\n# about c\n[c]\n", removing(text) { it.remove("a") })
    }

    @Test
    fun `a table whose sub-table prints first leaves only the lines above that header`() {
        val text = "# about a.b\n[a.b]\ny = 2\n# about c\n[c]\n# about a\n[a]\nx = 1\n"
        assertEquals("# about a.b\n# about c\n[c]\n", removing(text) { it.remove("a") })
    }

    private val fleet = "# first\n[[x]]\nn = 1\n\n# second\n[[x]]\nn = 2\n\n# after\n[y]\n"

    @Test
    fun `an array of tables leaves the lines above its first header only`() {
        assertEquals("# first\n\n# after\n[y]\n", removing(fleet) { it.remove("x") })
    }

    @Test
    fun `a table removed from an array of tables leaves the lines above its header`() {
        assertEquals(
            "# first\n\n# second\n[[x]]\nn = 2\n\n# after\n[y]\n",
            removing(fleet) { it["x"].getOrFail().arrayOfTablesOrNull!!.removeAt(0).getOrFail() },
        )
        assertEquals(
            "# first\n[[x]]\nn = 1\n\n# second\n\n# after\n[y]\n",
            removing(fleet) { document ->
                document["x"].getOrFail().arrayOfTablesOrNull!!.retainAll { it["n"].getOrFail().longOrNull == 1L }
            },
        )
    }

    private val inline = "t = {\n  # first\n  a = 1,\n  # second\n  b = 2,\n}\n"

    @Test
    fun `an inline table entry leaves its comment above the next entry`() {
        assertEquals(
            "t = {\n  # first\n  # second\n  b = 2,\n}\n",
            removing(inline) { it["t"].getOrFail().inlineTableOrNull!!.remove("a") },
        )
    }

    @Test
    fun `the last inline table entry leaves its comment before the closing brace`() {
        assertEquals(
            "t = {\n  # first\n  a = 1,\n  # second\n}\n",
            removing(inline) { it["t"].getOrFail().tableOrNull!!.removeItem("b") },
        )
    }

    @Test
    fun `an inline table on one line prints as before`() {
        assertEquals(
            "t = { b = 2 }\n",
            removing("t = { a = 1, b = 2 }\n") { it["t"].getOrFail().inlineTableOrNull!!.remove("a") },
        )
    }

    private val ports = "ports = [\n  # http\n  80,\n  # https\n  443,\n]\n"

    @Test
    fun `an array element leaves its comment above the next element`() {
        assertEquals(
            "ports = [\n  # http\n  # https\n  443,\n]\n",
            removing(ports) { it["ports"].getOrFail().arrayOrNull!!.removeAt(0).getOrFail() },
        )
    }

    @Test
    fun `the last array element leaves its comment before the closing bracket`() {
        assertEquals(
            "ports = [\n  # http\n  80,\n  # https\n]\n",
            removing(ports) { it["ports"].getOrFail().arrayOrNull!!.removeAt(1).getOrFail() },
        )
    }

    @Test
    fun `the first array element takes the comment on its line`() {
        val text = "ports = [\n  80, # http\n  443, # https\n]\n"
        assertEquals(
            "ports = [\n  443, # https\n]\n",
            removing(text) { it["ports"].getOrFail().arrayOrNull!!.removeAt(0).getOrFail() },
        )
    }

    @Test
    fun `the last array element without a trailing comma leaves the closing bracket on its own line`() {
        val text = "ports = [\n  80, # http\n  443 # https\n]\n"
        assertEquals(
            "ports = [\n  80, # http\n]\n",
            removing(text) { it["ports"].getOrFail().arrayOrNull!!.removeAt(1).getOrFail() },
        )
    }

    @Test
    fun `an array element sharing its line leaves the comment on that line`() {
        val text = "ports = [\n  80, 443, # web\n  8080,\n]\n"
        assertEquals(
            "ports = [\n  80, # web\n  8080,\n]\n",
            removing(text) { it["ports"].getOrFail().arrayOrNull!!.removeAt(1).getOrFail() },
        )
    }

    @Test
    fun `an inline table entry takes the comment on its line`() {
        val text = "t = {\n  a = 1, # one\n  b = 2 # two\n}\n"
        assertEquals(
            "t = {\n  b = 2 # two\n}\n",
            removing(text) { it["t"].getOrFail().inlineTableOrNull!!.remove("a") },
        )
        assertEquals(
            "t = {\n  a = 1, # one\n}\n",
            removing(text) { it["t"].getOrFail().inlineTableOrNull!!.remove("b") },
        )
    }

    @Test
    fun `clearing an array keeps every comment in it`() {
        assertEquals(
            "ports = [\n  # http\n  # https\n]\n",
            removing(ports) { it["ports"].getOrFail().arrayOrNull!!.clear() },
        )
    }

    @Test
    fun `clearing a table keeps the comments above its keys`() {
        val text = "[a]\n# one\nx = 1\n# two\ny = 2\n[b]\n"
        assertEquals("[a]\n# one\n# two\n[b]\n", removing(text) { it["a"].getOrFail().standardTableOrNull!!.clear() })
    }

    @Test
    fun `retaining keys keeps the comments above the ones dropped`() {
        val text = "# one\nx = 1\n# two\ny = 2\n# three\nz = 3\n"
        assertEquals("# one\n# two\ny = 2\n# three\n", removing(text) { document ->
            document.root.retainAll { (key, _) -> key == "y" }
        })
    }

    @Test
    fun `a copy prints the kept lines too`() {
        val document = TomlDocument.parse(fleet).getOrFail()
        document.remove("x")
        assertEquals(document.toString(), document.copy().toString())
    }

    @Test
    fun `a key added after removing the last one prints above the kept lines`() {
        val text = "[a]\nx = 1\n# about y\ny = 2\n[b]\n"
        assertEquals("[a]\nx = 1\nz = 3\n# about y\n[b]\n", removing(text) { document ->
            val a = document["a"].getOrFail().standardTableOrNull!!
            a.remove("y")
            a["z"] = Value.Integer(3L)
        })
    }
}
