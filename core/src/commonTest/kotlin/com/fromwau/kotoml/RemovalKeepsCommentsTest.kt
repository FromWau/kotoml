package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Removing a key takes only its own text: the comment and blank lines above it stay where they print. */
class RemovalKeepsCommentsTest {

    private fun removing(
        text: String,
        edit: (TomlDocument) -> Unit,
    ): String {
        val document = TomlDocument.parse(text).assertSuccess()
        edit(document)
        return document.toString()
    }

    @Test
    fun `a dotted table left empty prints its key as an empty table below the comment kept from it`() {
        val doc = TomlDocument.parse("[t]\n# about b\na.b = 1\nz = 2\n").assertSuccess()
        doc["t"]["a"].assertSuccess().standardTableOrNull!!.remove("b")
        assertEquals("[t]\n# about b\na = {}\nz = 2\n", doc.toString())
    }

    @Test
    fun `a dotted table on a table's lines left empty prints its empty table spaced as its removed line`() {
        val cases = listOf(
            Triple("[a]\nx=1\nb.d.e=2 # e\ny=3\n", listOf("a", "b", "d"), "[a]\nx=1\nb.d={}\ny=3\n"),
            Triple("[a]\n  # about e\n  b.d.e  =  2\n", listOf("a", "b", "d"), "[a]\n  # about e\n  b.d  =  {}\n"),
            Triple("b.d.e=2\nx=1\nb.d.f=3\n", listOf("b", "d"), "b.d={}\nx=1\n"),
        )
        for ((text, path, expected) in cases) {
            val doc = TomlDocument.parse(text).assertSuccess()
            val table = path.drop(1).fold(doc[path.first()]) { slot, key -> slot[key] }.assertSuccess()
            table.standardTableOrNull!!.removeAll { true }
            assertEquals(expected, doc.toString(), text)
            val formatted = TomlDocument.parse(expected).assertSuccess()
            formatted.format()
            doc.format()
            assertEquals(formatted.toString(), doc.toString(), text)
        }
    }

    @Test
    fun `a header table made dotted and left empty keeps its header spacing when made a header again`() {
        val doc = TomlDocument.parse("[a.b.d]\ne=2\n").assertSuccess()
        val d = doc["a"]["b"]["d"].assertSuccess()
        d.setLayout(TableLayout.Dotted).assertSuccess()
        d.standardTableOrNull!!.remove("e")
        assertEquals("[a.b]\nd={}\n", doc.toString())
        d.setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[a.b.d]\n", doc.toString())
    }

    @Test
    fun `a dotted table in an inline table left empty prints its key as an empty table`() {
        val doc = TomlDocument.parse("a = { a.b = 1 }\n").assertSuccess()
        doc["a"].assertSuccess().inlineTableOrNull!!.getItem("a")!!.inlineTableOrNull!!.remove("b")
        assertEquals("a = { a = {} }\n", doc.toString())
    }

    @Test
    fun `an implicit table left holding nothing prints its header`() {
        val doc = TomlDocument.parse("[a.b]\nx = 1\n").assertSuccess()
        doc["a"].assertSuccess().standardTableOrNull!!.remove("b")
        assertEquals("[a]\n", doc.toString())
    }

    @Test
    fun `a table added after a removal prints below the comment kept from the removed table`() {
        val doc = TomlDocument.parse("[b]\ny = 1\n\n# about a\n[a]\nx = 1\n").assertSuccess()
        doc.remove("a")
        doc["c"] = standardTableOf("z" to Item(Value.Integer(1L))).assertSuccess()
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
            removing(text) { it["server"].assertSuccess().standardTableOrNull!!.remove("port") },
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
            removing(interleaved) { it["a"].assertSuccess().standardTableOrNull!!.remove("y") },
        )
        assertEquals(
            "# about a.x\n# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n",
            removing(interleaved) { it["a"].assertSuccess().standardTableOrNull!!.remove("x") },
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
                it["a"].assertSuccess().standardTableOrNull!!.remove("y")
                it.remove("b")
            },
        )
    }

    @Test
    fun `a dotted key made by hand leaves its comment above the next one`() {
        val document = TomlDocument()
        val pool = StandardTable()
        Item(pool).setLayout(TableLayout.Dotted).assertSuccess()
        pool.put(Key("size", leafDecor = Decor(prefix = "# size\n")), Item(Value.Integer(1L)))
        pool.put(Key("idle", leafDecor = Decor(prefix = "# idle\n")), Item(Value.Integer(2L)))
        document["pool"] = pool
        document["timeout"] = Value.Integer(3L)
        document["pool"].assertSuccess().standardTableOrNull!!.remove("size")
        assertEquals("# size\n# idle\npool.idle = 2\ntimeout = 3\n", document.toString())
        document["pool"].assertSuccess().standardTableOrNull!!.remove("idle")
        assertEquals("# size\n# idle\npool = {}\ntimeout = 3\n", document.toString())
    }

    @Test
    fun `a sub-table leaves the lines above its header`() {
        val text = "[a]\nx = 1\n\n# about b\n[a.b]\n# inside b\ny = 2\n\n# about c\n[c]\nz = 3\n"
        assertEquals(
            "[a]\nx = 1\n\n# about b\n\n# about c\n[c]\nz = 3\n",
            removing(text) { it["a"].assertSuccess().standardTableOrNull!!.remove("b") },
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
            removing(fleet) { it["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(0).assertSuccess() },
        )
        assertEquals(
            "# first\n[[x]]\nn = 1\n\n# second\n\n# after\n[y]\n",
            removing(fleet) { document ->
                document["x"].assertSuccess().arrayOfTablesOrNull!!.retainAll { it["n"].assertSuccess().longOrNull == 1L }
            },
        )
    }

    private val inline = "t = {\n  # first\n  a = 1,\n  # second\n  b = 2,\n}\n"

    @Test
    fun `an inline table entry leaves its comment above the next entry`() {
        assertEquals(
            "t = {\n  # first\n  # second\n  b = 2,\n}\n",
            removing(inline) { it["t"].assertSuccess().inlineTableOrNull!!.remove("a") },
        )
    }

    @Test
    fun `the last inline table entry leaves its comment before the closing brace`() {
        assertEquals(
            "t = {\n  # first\n  a = 1,\n  # second\n}\n",
            removing(inline) { it["t"].assertSuccess().tableOrNull!!.removeItem("b") },
        )
    }

    @Test
    fun `an entry removed from an inline table on one line leaves it on one line`() {
        assertEquals(
            "t = { b = 2 }\n",
            removing("t = { a = 1, b = 2 }\n") { it["t"].assertSuccess().inlineTableOrNull!!.remove("a") },
        )
    }

    private val ports = "ports = [\n  # http\n  80,\n  # https\n  443,\n]\n"

    @Test
    fun `an array element leaves its comment above the next element`() {
        assertEquals(
            "ports = [\n  # http\n  # https\n  443,\n]\n",
            removing(ports) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(0).assertSuccess() },
        )
    }

    @Test
    fun `the last array element leaves its comment before the closing bracket`() {
        assertEquals(
            "ports = [\n  # http\n  80,\n  # https\n]\n",
            removing(ports) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1).assertSuccess() },
        )
    }

    @Test
    fun `the first array element takes the comment on its line`() {
        val text = "ports = [\n  80, # http\n  443, # https\n]\n"
        assertEquals(
            "ports = [\n  443, # https\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(0).assertSuccess() },
        )
    }

    @Test
    fun `the last array element without a trailing comma leaves the closing bracket on its own line`() {
        val text = "ports = [\n  80, # http\n  443 # https\n]\n"
        assertEquals(
            "ports = [\n  80, # http\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1).assertSuccess() },
        )
    }

    @Test
    fun `an array element sharing its line leaves the comment on that line`() {
        val text = "ports = [\n  80, 443, # web\n  8080,\n]\n"
        assertEquals(
            "ports = [\n  80, # web\n  8080,\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1).assertSuccess() },
        )
    }

    @Test
    fun `an inline table entry takes the comment on its line`() {
        val text = "t = {\n  a = 1, # one\n  b = 2 # two\n}\n"
        assertEquals(
            "t = {\n  b = 2 # two\n}\n",
            removing(text) { it["t"].assertSuccess().inlineTableOrNull!!.remove("a") },
        )
        assertEquals(
            "t = {\n  a = 1, # one\n}\n",
            removing(text) { it["t"].assertSuccess().inlineTableOrNull!!.remove("b") },
        )
    }

    @Test
    fun `clearing an array keeps every comment in it`() {
        assertEquals(
            "ports = [\n  # http\n  # https\n]\n",
            removing(ports) { it["ports"].assertSuccess().arrayOrNull!!.clear() },
        )
    }

    @Test
    fun `clearing a table keeps the comments above its keys`() {
        val text = "[a]\n# one\nx = 1\n# two\ny = 2\n[b]\n"
        assertEquals("[a]\n# one\n# two\n[b]\n", removing(text) { it["a"].assertSuccess().standardTableOrNull!!.clear() })
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
        val document = TomlDocument.parse(fleet).assertSuccess()
        document.remove("x")
        assertEquals(document.toString(), document.copy().toString())
    }

    @Test
    fun `a key added after removing the last one prints above the kept lines`() {
        val text = "[a]\nx = 1\n# about y\ny = 2\n[b]\n"
        assertEquals("[a]\nx = 1\nz = 3\n# about y\n[b]\n", removing(text) { document ->
            val a = document["a"].assertSuccess().standardTableOrNull!!
            a.remove("y")
            a["z"] = Value.Integer(3L)
        })
    }

    // Removes [removed] from the dotted table `s.b` in [source], then stores `s.b` at [path] under `t` in [text].
    private fun storedAfterRemoving(
        text: String,
        removed: String,
        path: List<String> = listOf("b"),
        source: String = text,
    ): TomlDocument {
        val doc = TomlDocument.parse(text).assertSuccess()
        val from = if (source == text) doc else TomlDocument.parse(source).assertSuccess()
        val b = from["s"]["b"].assertSuccess()
        b.valueOrNull!!.inlineTableOrNull!!.remove(removed)
        val holder = path.dropLast(1).fold(doc["t"].assertSuccess()) { item, key -> item[key].assertSuccess() }
        holder.standardTableOrNull!![path.last()] = b
        return doc
    }

    @Test
    fun `a comment kept in a dotted table in braces stored into a table prints on its own line`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        assertEquals("s = {\n  b.c = 1,\n  # about d\n}\n[t]\nb.c = 1\n# about d\nx = 0\n", doc.toString())
    }

    @Test
    fun `a CRLF comment kept in a dotted table in braces stored into a table prints on its own line`() {
        val source = "s = {\r\n  b.c = 1,\r\n  # about d\r\n  b.d = 2,\r\n}\r\n"
        val doc = storedAfterRemoving("[t]\r\nx = 0\r\n", "d", source = source)
        assertEquals("[t]\r\nx = 0\r\nb.c = 1\r\n# about d\r\n", doc.toString())
    }

    @Test
    fun `a comment kept above a later key of a dotted table in braces stays above that key in a table`() {
        val text = "s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n  b.e = 3,\n}\n[t]\nx = 0\n"
        val doc = storedAfterRemoving(text, "d")
        assertEquals("[t]\nb.c = 1\n# about d\nb.e = 3\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a comment kept from the only key of a dotted table in braces prints above its empty table in a table`() {
        val doc = storedAfterRemoving("s = {\n  # about c\n  b.c = 1,\n}\n[t]\nx = 0\n", "c")
        assertEquals("[t]\n# about c\nb = {}\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a comment after a comma kept in a dotted table in braces ends the line before in a table`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1, # on c\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        assertEquals("[t]\nb.c = 1 # on c\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a comment kept in a dotted table in braces stored under a dotted key of a table prints on its own line`() {
        val text = "s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n[t]\nx = 0\n[t.u]\ny = 1\n"
        val doc = storedAfterRemoving(text, "d", path = listOf("u", "b"))
        assertEquals("[t]\nx = 0\n[t.u]\nb.c = 1\n# about d\ny = 1\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a comment kept in a stored dotted table stays on its own line when the table is put on one line`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        doc["t"]["b"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("[t]\nb = { c = 1 }\n# about d\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a stored dotted table holding a kept comment prints it in braces when stored in braces again`() {
        val text = "s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n[t]\nu = {\n  y = 1,\n}\n"
        val doc = storedAfterRemoving(text, "d")
        val u = doc["t"]["u"].assertSuccess().valueOrNull!!.inlineTableOrNull!!
        u["b"] = doc["t"]["b"].assertSuccess().valueOrNull!!
        val inBraces = "u = {\n  b.c = 1,\n  # about d\n  y = 1,\n}\n"
        assertEquals("[t]\nb.c = 1\n# about d\n$inBraces", doc.toString().substringAfter("}\n"))
    }
}
