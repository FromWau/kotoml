package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Removing a key with force takes its own text and the comment lines right above it; comment lines set apart from it
 * by a blank line stay where they print.
 */
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
    fun `a dotted table left empty takes the comment above its only line`() {
        val doc = TomlDocument.parse("[t]\n# about b\na.b = 1\nz = 2\n").assertSuccess()
        doc["t"]["a"].assertSuccess().standardTableOrNull!!.remove("b", force = true)
        assertEquals("[t]\na = {}\nz = 2\n", doc.toString())
    }

    @Test
    fun `a dotted table left empty prints its key as an empty table below the free lines kept from it`() {
        val doc = TomlDocument.parse("[t]\n# about b\n\na.b = 1\nz = 2\n").assertSuccess()
        doc["t"]["a"].assertSuccess().standardTableOrNull!!.remove("b", force = true)
        assertEquals("[t]\n# about b\n\na = {}\nz = 2\n", doc.toString())
    }

    @Test
    fun `a dotted table on a table's lines left empty prints its empty table spaced as its removed line`() {
        val cases = listOf(
            Triple("[a]\nx=1\nb.d.e=2 # e\ny=3\n", listOf("a", "b", "d"), "[a]\nx=1\nb.d={}\ny=3\n"),
            Triple("[a]\n  # about e\n  b.d.e  =  2\n", listOf("a", "b", "d"), "[a]\n  b.d  =  {}\n"),
            Triple("b.d.e=2\nx=1\nb.d.f=3\n", listOf("b", "d"), "b.d={}\nx=1\n"),
        )
        for ((text, path, expected) in cases) {
            val doc = TomlDocument.parse(text).assertSuccess()
            val table = path.drop(1).fold(doc[path.first()]) { slot, key -> slot[key] }.assertSuccess()
            table.standardTableOrNull!!.removeAll(force = true) { true }
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
        d.standardTableOrNull!!.remove("e", force = true)
        assertEquals("[a.b]\nd={}\n", doc.toString())
        d.setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[a.b.d]\n", doc.toString())
    }

    @Test
    fun `removing a key below free lines in braces of a CRLF document keeps its line endings whole`() {
        val doc = TomlDocument.parse("s = {\r\n  b.c = 1,\r\n  # about d\r\n\r\n  b.d = 2,\r\n}\r\n").assertSuccess()
        doc["s"]["b"].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove("d", force = true)
        assertEquals("s = {\r\n  b.c = 1,\r\n  # about d\r\n}\r\n", doc.toString())
        doc.format()
        assertEquals("s = {\r\n  b.c = 1,\r\n  # about d\r\n}\r\n", doc.toString())
    }

    @Test
    fun `the comment above a removed dotted key in braces goes with it`() {
        val doc = TomlDocument.parse("s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n").assertSuccess()
        doc["s"]["b"].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove("d", force = true)
        assertEquals("s = {\n  b.c = 1,\n}\n", doc.toString())
    }

    @Test
    fun `a dotted table in an inline table left empty prints its key as an empty table`() {
        val doc = TomlDocument.parse("a = { a.b = 1 }\n").assertSuccess()
        doc["a"].assertSuccess().inlineTableOrNull!!.getItem("a")!!.inlineTableOrNull!!.remove("b", force = true)
        assertEquals("a = { a = {} }\n", doc.toString())
    }

    @Test
    fun `an implicit table left holding nothing prints its header`() {
        val doc = TomlDocument.parse("[a.b]\nx = 1\n").assertSuccess()
        doc["a"].assertSuccess().standardTableOrNull!!.remove("b", force = true)
        assertEquals("[a]\n", doc.toString())
    }

    @Test
    fun `a table added after a removal prints below the free lines kept from the removed table`() {
        val doc = TomlDocument.parse("[b]\ny = 1\n\n# about a\n\n[a]\nx = 1\n").assertSuccess()
        doc.remove("a", force = true)
        doc["c"] = standardTableOf("z" to Item(Value.Integer(1L))).assertSuccess()
        assertEquals("[b]\ny = 1\n\n# about a\n\n[c]\nz = 1\n", doc.toString())
    }

    @Test
    fun `a key in the middle takes the comment lines right above it`() {
        val text = "# the port to listen on\nport = 8080\n# bind address\nhost = \"0.0.0.0\"\n"
        assertEquals("# bind address\nhost = \"0.0.0.0\"\n", removing(text) { it.remove("port", force = true) })
    }

    @Test
    fun `a key leaves the free lines above it where they print`() {
        val text = "# server\n\n# the port\nport = 8080\nhost = 1\n"
        assertEquals("# server\n\nhost = 1\n", removing(text) { it.remove("port", force = true) })
    }

    @Test
    fun `the first key leaves the free lines above it at the top and one blank line below them`() {
        val text = "\n# first\n\nport = 1\n\n# second\nhost = 2\n"
        assertEquals("\n# first\n\n# second\nhost = 2\n", removing(text) { it.remove("port", force = true) })
    }

    @Test
    fun `a key between blank lines leaves one`() {
        val text = "a = 1\n\n# about x\nx = 1\n\ny = 2\n"
        assertEquals("a = 1\n\ny = 2\n", removing(text) { it.remove("x", force = true) })
    }

    @Test
    fun `the first key of the document takes the blank lines below it`() {
        assertEquals("y = 2\n", removing("x = 1\n\ny = 2\n") { it.remove("x") })
    }

    @Test
    fun `the last key of a table takes its comment and leaves the blank line above the next header`() {
        val text = "[server]\nhost = 1\n# the port\nport = 2\n\n# client side\n[client]\nx = 1\n"
        assertEquals(
            "[server]\nhost = 1\n\n# client side\n[client]\nx = 1\n",
            removing(text) { it["server"].assertSuccess().standardTableOrNull!!.remove("port", force = true) },
        )
    }

    @Test
    fun `free lines kept from the last key of a table lose the blank line meeting the next header's`() {
        val expected = "[server]\nhost = 1\n\n# note\n\n[client]\nx = 1\n"
        for (text in listOf(
            "[server]\nhost = 1\n\n# note\n\nport = 2\n\n[client]\nx = 1\n",
            "[server]\nhost = 1\n\n# note\n\nport = 2\n[client]\nx = 1\n",
        )) {
            val removed = removing(text) { it["server"].assertSuccess().standardTableOrNull!!.remove("port") }
            assertEquals(expected, removed, text)
        }
    }

    @Test
    fun `free lines kept from the last key of the document lose the blank line ending them`() {
        assertEquals("a = 1\n\n# note\n", removing("a = 1\n\n# note\n\nb = 2\n") { it.remove("b") })
    }

    @Test
    fun `the last key of the document takes its comment and leaves the comment after it`() {
        val text = "a = 1\n# about b\nb = 2\n# the end\n"
        assertEquals("a = 1\n# the end\n", removing(text) { it.remove("b", force = true) })
    }

    @Test
    fun `a comment on the key's own line goes with the key`() {
        val text = "a = 1 # one\n# about b\nb = 2 # two\nc = 3\n"
        assertEquals("a = 1 # one\nc = 3\n", removing(text) { it.remove("b", force = true) })
    }

    @Test
    fun `an indented key leaves the free lines above it and the next key keeps its indentation`() {
        val text = "  # note\n\n  a = 1\n  b = 2\n"
        assertEquals("  # note\n\n  b = 2\n", removing(text) { it.remove("a", force = true) })
    }

    @Test
    fun `lines ending in CRLF stay as they were`() {
        val text = "# note\r\n\r\na = 1\r\nb = 2\r\n"
        assertEquals("# note\r\n\r\nb = 2\r\n", removing(text) { it.remove("a", force = true) })
    }

    private val interleaved = "# about a.x\na.x = 1\n# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n"

    @Test
    fun `a key between dotted keys printed in read order takes its comment and leaves theirs in place`() {
        assertEquals(
            "# about a.x\na.x = 1\n# about a.y\na.y = 3\nc = 4\n",
            removing(interleaved) { it.remove("b", force = true) },
        )
    }

    @Test
    fun `a dotted key removed through its table takes the comment above its line`() {
        assertEquals(
            "# about a.x\na.x = 1\n# about b\nb = 2\nc = 4\n",
            removing(interleaved) { it["a"].assertSuccess().standardTableOrNull!!.remove("y", force = true) },
        )
        assertEquals(
            "# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n",
            removing(interleaved) { it["a"].assertSuccess().standardTableOrNull!!.remove("x", force = true) },
        )
    }

    @Test
    fun `a dotted key removed whole takes the comments above each of its lines`() {
        assertEquals("# about b\nb = 2\nc = 4\n", removing(interleaved) { it.remove("a", force = true) })
    }

    private val interleavedFree = "a.x = 1\nb = 2\n# note\n\na.y = 3\nc = 4\n"

    @Test
    fun `a dotted key removed through its table leaves the free lines above its line in place`() {
        assertEquals(
            "a.x = 1\nb = 2\n# note\n\nc = 4\n",
            removing(interleavedFree) { it["a"].assertSuccess().standardTableOrNull!!.remove("y") },
        )
        assertEquals("b = 2\n# note\n\nc = 4\n", removing(interleavedFree) { it.remove("a") })
    }

    @Test
    fun `free lines kept from a dotted key stay in place when a neighbour goes too`() {
        assertEquals(
            "a.x = 1\n# note\n\nc = 4\n",
            removing(interleavedFree) {
                it["a"].assertSuccess().standardTableOrNull!!.remove("y")
                it.remove("b")
            },
        )
    }

    @Test
    fun `a dotted key made by hand takes the comment above it`() {
        val document = TomlDocument()
        val pool = StandardTable()
        Item(pool).setLayout(TableLayout.Dotted).assertSuccess()
        pool.put(Key("size", leafDecor = Decor(prefix = "# size\n")), Item(Value.Integer(1L)))
        pool.put(Key("idle", leafDecor = Decor(prefix = "# idle\n")), Item(Value.Integer(2L)))
        document["pool"] = pool
        document["timeout"] = Value.Integer(3L)
        document["pool"].assertSuccess().standardTableOrNull!!.remove("size", force = true)
        assertEquals("# idle\npool.idle = 2\ntimeout = 3\n", document.toString())
        document["pool"].assertSuccess().standardTableOrNull!!.remove("idle", force = true)
        assertEquals("pool = {}\ntimeout = 3\n", document.toString())
    }

    @Test
    fun `a sub-table takes the comment above its header and leaves one blank line`() {
        val text = "[a]\nx = 1\n\n# about b\n[a.b]\n# inside b\ny = 2\n\n# about c\n[c]\nz = 3\n"
        assertEquals(
            "[a]\nx = 1\n\n# about c\n[c]\nz = 3\n",
            removing(text) { it["a"].assertSuccess().standardTableOrNull!!.remove("b", force = true) },
        )
    }

    @Test
    fun `a table takes the comments above its first header and above its sub-tables`() {
        val text = "# about a\n[a]\nx = 1\n# about c\n[c]\n# about a.b\n[a.b]\ny = 2\n"
        assertEquals("# about c\n[c]\n", removing(text) { it.remove("a", force = true) })
    }

    @Test
    fun `a table whose sub-table prints first takes the comment above that header`() {
        val text = "# about a.b\n[a.b]\ny = 2\n# about c\n[c]\n# about a\n[a]\nx = 1\n"
        assertEquals("# about c\n[c]\n", removing(text) { it.remove("a", force = true) })
    }

    private val fleet = "# first\n[[x]]\nn = 1\n\n# second\n[[x]]\nn = 2\n\n# after\n[y]\n"

    private val fleetWithNote = "# note\n\n$fleet"

    @Test
    fun `an array of tables takes the comment above each of its headers`() {
        assertEquals("# after\n[y]\n", removing(fleet) { it.remove("x", force = true) })
    }

    @Test
    fun `an array of tables leaves the free lines above its first header`() {
        assertEquals("# note\n\n# after\n[y]\n", removing(fleetWithNote) { it.remove("x", force = true) })
    }

    @Test
    fun `a table removed from an array of tables takes the comment above its header`() {
        assertEquals(
            "# second\n[[x]]\nn = 2\n\n# after\n[y]\n",
            removing(fleet) { it["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(0, force = true).assertSuccess() },
        )
        assertEquals(
            "# first\n[[x]]\nn = 1\n\n# after\n[y]\n",
            removing(fleet) { document ->
                val tables = document["x"].assertSuccess().arrayOfTablesOrNull!!
                tables.retainAll(force = true) { it["n"].assertSuccess().longOrNull == 1L }
            },
        )
    }

    private val inline = "t = {\n  # first\n  a = 1,\n  # second\n  b = 2,\n}\n"

    @Test
    fun `an inline table entry takes the comment above it`() {
        assertEquals(
            "t = {\n  # second\n  b = 2,\n}\n",
            removing(inline) { it["t"].assertSuccess().inlineTableOrNull!!.remove("a", force = true) },
        )
    }

    @Test
    fun `the last inline table entry takes the comment above it`() {
        assertEquals(
            "t = {\n  # first\n  a = 1,\n}\n",
            removing(inline) { it["t"].assertSuccess().tableOrNull!!.removeItem("b", force = true) },
        )
    }

    @Test
    fun `an inline table entry leaves the free lines above it`() {
        val text = "t = {\n  # first\n\n  a = 1,\n  # second\n  b = 2,\n}\n"
        assertEquals(
            "t = {\n  # first\n\n  # second\n  b = 2,\n}\n",
            removing(text) { it["t"].assertSuccess().inlineTableOrNull!!.remove("a", force = true) },
        )
    }

    @Test
    fun `free lines kept before the closing brace lose the blank line ending them`() {
        val text = "t = {\n  a = 1,\n  # second\n\n  b = 2,\n}\n"
        assertEquals(
            "t = {\n  a = 1,\n  # second\n}\n",
            removing(text) { it["t"].assertSuccess().inlineTableOrNull!!.remove("b") },
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
    fun `an array element takes the comment above it`() {
        assertEquals(
            "ports = [\n  # https\n  443,\n]\n",
            removing(ports) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(0, force = true).assertSuccess() },
        )
    }

    @Test
    fun `the last array element takes the comment above it`() {
        assertEquals(
            "ports = [\n  # http\n  80,\n]\n",
            removing(ports) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1, force = true).assertSuccess() },
        )
    }

    @Test
    fun `an array element leaves the free lines above it`() {
        val text = "ports = [\n  # http\n\n  80,\n  # https\n  443,\n]\n"
        assertEquals(
            "ports = [\n  # http\n\n  # https\n  443,\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(0, force = true).assertSuccess() },
        )
    }

    @Test
    fun `an array element between blank lines leaves one`() {
        val text = "ports = [\n  80,\n\n  # https\n  443,\n\n  8080,\n]\n"
        assertEquals(
            "ports = [\n  80,\n\n  8080,\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1, force = true).assertSuccess() },
        )
    }

    @Test
    fun `the first array element takes the comment on its line`() {
        val text = "ports = [\n  80, # http\n  443, # https\n]\n"
        assertEquals(
            "ports = [\n  443, # https\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(0, force = true).assertSuccess() },
        )
    }

    @Test
    fun `the last array element without a trailing comma leaves the closing bracket on its own line`() {
        val text = "ports = [\n  80, # http\n  443 # https\n]\n"
        assertEquals(
            "ports = [\n  80, # http\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1, force = true).assertSuccess() },
        )
    }

    @Test
    fun `the last array element on a shared line takes the comment ending that line`() {
        val text = "ports = [\n  80, 443, # web\n  8080,\n]\n"
        assertEquals(
            "ports = [\n  80,\n  8080,\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1, force = true).assertSuccess() },
        )
    }

    @Test
    fun `an array element sharing its line with a later one leaves the comment ending that line`() {
        val text = "ports = [\n  80, 443, # web\n  8080,\n]\n"
        assertEquals(
            "ports = [\n  443, # web\n  8080,\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(0).assertSuccess() },
        )
    }

    @Test
    fun `an array element takes the comment lines between it and a comma on a later line`() {
        val text = "arr = [\n  1,\n  2 # on 2\n  # more\n  , 3,\n]\n"
        assertEquals(
            "arr = [\n  1,\n  3,\n]\n",
            removing(text) { it["arr"].assertSuccess().arrayOrNull!!.removeAt(1, force = true).assertSuccess() },
        )
    }

    @Test
    fun `an inline table entry takes the comment on its line`() {
        val text = "t = {\n  a = 1, # one\n  b = 2 # two\n}\n"
        assertEquals(
            "t = {\n  b = 2 # two\n}\n",
            removing(text) { it["t"].assertSuccess().inlineTableOrNull!!.remove("a", force = true) },
        )
        assertEquals(
            "t = {\n  a = 1, # one\n}\n",
            removing(text) { it["t"].assertSuccess().inlineTableOrNull!!.remove("b", force = true) },
        )
    }

    @Test
    fun `clearing an array with force takes the comment above each element`() {
        assertEquals(
            "ports = [\n]\n",
            removing(ports) { it["ports"].assertSuccess().arrayOrNull!!.clear(force = true).assertSuccess() },
        )
    }

    @Test
    fun `clearing a table with force takes the comments above its keys and leaves the free lines`() {
        val text = "[a]\n# note\n\n# one\nx = 1\n# two\ny = 2\n[b]\n"
        assertEquals(
            "[a]\n# note\n\n[b]\n",
            removing(text) { it["a"].assertSuccess().standardTableOrNull!!.clear(force = true).assertSuccess() },
        )
    }

    @Test
    fun `retaining keys takes the comments above the ones dropped`() {
        val text = "# one\nx = 1\n# two\ny = 2\n# three\nz = 3\n"
        assertEquals("# two\ny = 2\n", removing(text) { document ->
            document.root.retainAll(force = true) { (key, _) -> key == "y" }
        })
    }

    @Test
    fun `a copy prints the kept lines too`() {
        val document = TomlDocument.parse(fleetWithNote).assertSuccess()
        document.remove("x", force = true)
        assertEquals(document.toString(), document.copy().toString())
    }

    @Test
    fun `a key added after removing the last one prints above the free lines kept from it`() {
        val text = "[a]\nx = 1\n# note\n\ny = 2\n[b]\n"
        assertEquals("[a]\nx = 1\nz = 3\n# note\n\n[b]\n", removing(text) { document ->
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
        b.valueOrNull!!.inlineTableOrNull!!.remove(removed, force = true)
        val holder = path.dropLast(1).fold(doc["t"].assertSuccess()) { item, key -> item[key].assertSuccess() }
        holder.standardTableOrNull!![path.last()] = b
        return doc
    }

    @Test
    fun `free lines kept in a dotted table in braces stored into a table print on their own line`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1,\n  # about d\n\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        assertEquals("s = {\n  b.c = 1,\n  # about d\n}\n[t]\nb.c = 1\n# about d\nx = 0\n", doc.toString())
    }

    @Test
    fun `CRLF free lines kept in a dotted table in braces stored into a table print on their own line`() {
        val source = "s = {\r\n  b.c = 1,\r\n  # about d\r\n\r\n  b.d = 2,\r\n}\r\n"
        val doc = storedAfterRemoving("[t]\r\nx = 0\r\n", "d", source = source)
        assertEquals("[t]\r\nx = 0\r\nb.c = 1\r\n# about d\r\n", doc.toString())
    }

    @Test
    fun `free lines kept above a later key of a dotted table in braces stay above that key in a table`() {
        val text = "s = {\n  b.c = 1,\n  # about d\n\n  b.d = 2,\n  b.e = 3,\n}\n[t]\nx = 0\n"
        val doc = storedAfterRemoving(text, "d")
        assertEquals("[t]\nb.c = 1\n# about d\nb.e = 3\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `free lines kept from the only key of a dotted table in braces print above its empty table in a table`() {
        val doc = storedAfterRemoving("s = {\n  # about c\n\n  b.c = 1,\n}\n[t]\nx = 0\n", "c")
        assertEquals("[t]\n# about c\nb = {}\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a comment after a comma kept in a dotted table in braces ends the line before in a table`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1, # on c\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        assertEquals("[t]\nb.c = 1 # on c\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `free lines kept in a dotted table in braces stored under a dotted key of a table print on their own line`() {
        val text = "s = {\n  b.c = 1,\n  # about d\n\n  b.d = 2,\n}\n[t]\nx = 0\n[t.u]\ny = 1\n"
        val doc = storedAfterRemoving(text, "d", path = listOf("u", "b"))
        assertEquals("[t]\nx = 0\n[t.u]\nb.c = 1\n# about d\ny = 1\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `free lines kept in a stored dotted table stay on their own line when the table is put on one line`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1,\n  # about d\n\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        doc["t"]["b"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("[t]\nb = { c = 1 }\n# about d\nx = 0\n", doc.toString().substringAfter("}\n"))
    }

    @Test
    fun `a stored dotted table holding kept free lines prints them in braces when stored in braces again`() {
        val text = "s = {\n  b.c = 1,\n  # about d\n\n  b.d = 2,\n}\n[t]\nu = {\n  y = 1,\n}\n"
        val doc = storedAfterRemoving(text, "d")
        val u = doc["t"]["u"].assertSuccess().valueOrNull!!.inlineTableOrNull!!
        u["b"] = doc["t"]["b"].assertSuccess().valueOrNull!!
        val inBraces = "u = {\n  b.c = 1,\n  # about d\n  y = 1,\n}\n"
        assertEquals("[t]\nb.c = 1\n# about d\n$inBraces", doc.toString().substringAfter("}\n"))
    }

    private fun assertFormatsTo(
        expected: String,
        doc: TomlDocument,
    ) {
        doc.format()
        assertEquals(expected, doc.toString())
        doc.format()
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `a comment after a sibling's comma stays with that sibling when a dotted key in braces below it is removed`() {
        val text = "s = {\n  b.c = 1,\n  y = 5, # on y\n  # about d\n\n  b.d = 2,\n}\n[t]\nx = 0\n"
        val doc = storedAfterRemoving(text, "d")
        val s = "s = {\n  b.c = 1,\n  y = 5, # on y\n  # about d\n}\n"
        assertEquals("$s[t]\nb.c = 1\n# about d\nx = 0\n", doc.toString())
        assertFormatsTo("$s\n[t]\nb.c = 1\n# about d\nx = 0\n", doc)
    }

    @Test
    fun `a comment after a sibling's comma stays with that sibling in braces after the dotted key below it goes`() {
        val text = "s = {\n  b.c = 1,\n  y = 5, # on y\n  # about d\n\n  b.d = 2,\n}\n"
        val doc = TomlDocument.parse(text).assertSuccess()
        doc["s"]["b"].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove("d", force = true)
        val expected = "s = {\n  b.c = 1,\n  y = 5, # on y\n  # about d\n}\n"
        assertEquals(expected, doc.toString())
        assertFormatsTo(expected, doc)
    }

    @Test
    fun `a comment after a sibling's comma alone above a removed dotted key in braces stays with that sibling`() {
        val doc = storedAfterRemoving("s = {\n  b.c = 1,\n  y = 5, # on y\n  b.d = 2,\n}\n[t]\nx = 0\n", "d")
        assertEquals("[t]\nb.c = 1\nx = 0\n", doc.toString().substringAfter("}\n"))
        assertEquals("s = {\n  b.c = 1,\n  y = 5, # on y\n}\n", doc.toString().substringBefore("[t]"))
    }

    @Test
    fun `a comment after a sibling's comma in a CRLF document stays with that sibling when a dotted key is removed`() {
        val text = "s = {\r\n  b.c = 1,\r\n  y = 5, # on y\r\n  # about d\r\n\r\n  b.d = 2,\r\n}\r\n[t]\r\nx = 0\r\n"
        val doc = storedAfterRemoving(text, "d")
        val s = "s = {\r\n  b.c = 1,\r\n  y = 5, # on y\r\n  # about d\r\n}\r\n"
        assertEquals("$s[t]\r\nb.c = 1\r\n# about d\r\nx = 0\r\n", doc.toString())
        assertFormatsTo("$s\r\n[t]\r\nb.c = 1\r\n# about d\r\nx = 0\r\n", doc)
    }

    @Test
    fun `a comment after the comma of a key of the same dotted table prints once after that key when stored`() {
        val text = "s = {\n  b.c = 1, # on c\n  # about d\n\n  b.d = 2,\n}\n[t]\nx = 0\n"
        val doc = storedAfterRemoving(text, "d")
        val s = "s = {\n  b.c = 1, # on c\n  # about d\n}\n"
        assertEquals("$s[t]\nb.c = 1 # on c\n# about d\nx = 0\n", doc.toString())
        assertFormatsTo("$s\n[t]\nb.c = 1 # on c\n# about d\nx = 0\n", doc)
    }
}
