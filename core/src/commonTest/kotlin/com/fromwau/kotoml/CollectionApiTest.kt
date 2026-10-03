package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The containers' map and list surface: `put`, `retainAll`, `removeAll`, `keys`, `contains` and their siblings. */
class CollectionApiTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).unwrap()

    @Test
    fun `put with a string keeps the spelling and comment of a quoted key`() {
        val doc = parse("# about it\n'my key' = 1\n")
        val old = doc.root.put("my key", Item(Value.Integer(2L)))
        assertEquals(1L, old?.longOrNull)
        assertEquals("# about it\n'my key' = 2\n", doc.toString())
    }

    @Test
    fun `put with a key replaces the spelling and comment of a quoted key`() {
        val doc = parse("# about it\n'my key' = 1\n")
        val old = doc.root.put(Key("my key"), Item(Value.Integer(2L)))
        assertEquals(1L, old?.longOrNull)
        assertEquals("\"my key\" = 2\n", doc.toString())
    }

    @Test
    fun `put with a string on a document keeps the spelling and comment of a quoted key`() {
        val doc = parse("# about it\n'my key' = 1\n")
        doc.put("my key", Item(Value.Integer(2L)))
        assertEquals("# about it\n'my key' = 2\n", doc.toString())
    }

    @Test
    fun `put with a string on an inline table keeps the spelling of a quoted key`() {
        val doc = parse("t = { 'my key' = 1 }\n")
        val table = doc["t"].inlineTableOrNull!!
        val old = table.put("my key", Value.Integer(2L))
        assertEquals(1L, old?.longOrNull)
        assertEquals("t = { 'my key' = 2 }\n", doc.toString())
    }

    @Test
    fun `put with a key on an inline table replaces the spelling of a quoted key`() {
        val doc = parse("t = { 'my key' = 1 }\n")
        doc["t"].inlineTableOrNull!!.put(Key("my key"), Value.Integer(2L))
        assertEquals("t = { \"my key\" = 2 }\n", doc.toString())
    }

    @Test
    fun `a value key replaced by a table prints a clean header with its spelling and the comment above it`() {
        val doc = parse("b = 2\n# about it\n'my key'   =   1\n")
        doc.put("my key", Item(tableOf("x" to Item(Value.Integer(1L)))))
        assertEquals("b = 2\n\n# about it\n['my key']\nx = 1\n", doc.toString())
    }

    @Test
    fun `a value key replaced by a table without a comment above it prints a blank line and then the header`() {
        val doc = parse("b = 2\n'my key'   =   1\n")
        doc.put("my key", Item(tableOf("x" to Item(Value.Integer(1L)))))
        assertEquals("b = 2\n\n['my key']\nx = 1\n", doc.toString())
    }

    @Test
    fun `a value key set to a table through an item prints a clean header keeping the comment above it`() {
        val doc = parse("[s]\nb = 2\n# about it\n'my key'   =   1\n")
        doc["s"]["my key"] = tableOf("x" to Item(Value.Integer(1L)))
        assertEquals("[s]\nb = 2\n\n# about it\n[s.'my key']\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table key replaced by a value prints a key and value line with default spacing`() {
        val doc = parse("a = 1\n[ 'my key' ]\nx = 1\n")
        doc.put("my key", Item(Value.Integer(2L)))
        assertEquals("a = 1\n'my key' = 2\n", doc.toString())
    }

    @Test
    fun `a value replaced by another value keeps the key's spacing`() {
        val doc = parse("'my key'   =   1\n")
        doc.put("my key", Item(Value.Integer(2L)))
        assertEquals("'my key'   = 2\n", doc.toString())
    }

    @Test
    fun `put with a string at a new key returns null`() {
        val table = Table()
        assertNull(table.put("a", Item(Value.Integer(1L))))
        assertNull(InlineTable().put("a", Value.Integer(1L)))
    }

    @Test
    fun `removeAll on a table removes the pairs it accepts and keeps the comment above them`() {
        val doc = parse("# about a\na = 1 # one\nb = 2\n")
        assertTrue(doc.root.removeAll { (key, _) -> key == "a" })
        assertEquals("# about a\nb = 2\n", doc.toString())
    }

    @Test
    fun `removeAll on a table that accepts nothing returns false and changes nothing`() {
        val text = "# about a\na = 1 # one\nb = 2\n"
        val doc = parse(text)
        assertFalse(doc.root.removeAll { false })
        assertEquals(text, doc.toString())
    }

    @Test
    fun `retainAll on a table returns true when it removes a pair`() {
        val doc = parse("a = 1\n# about b\nb = 2 # two\n")
        assertTrue(doc.root.retainAll { (key, _) -> key == "a" })
        assertEquals("a = 1\n# about b\n", doc.toString())
    }

    @Test
    fun `retainAll on a table that keeps every pair returns false`() {
        val doc = parse("a = 1\nb = 2\n")
        assertFalse(doc.root.retainAll { true })
        assertEquals("a = 1\nb = 2\n", doc.toString())
    }

    @Test
    fun `retainAll on a table visits each pair of key and item once in order`() {
        val doc = parse("b = 1\na = 'x'\n")
        val visited = mutableListOf<Pair<String, Long?>>()
        doc.root.retainAll { (key, item) ->
            visited += key to item.longOrNull
            true
        }
        assertEquals(listOf("b" to 1L, "a" to null), visited)
    }

    @Test
    fun `removeAll on an inline table removes the entries it accepts and keeps the comment above them`() {
        val doc = parse("t = {\n  # about a\n  a = 1, # one\n  b = 2,\n}\n")
        val table = doc["t"].inlineTableOrNull!!
        assertTrue(table.removeAll { (key, _) -> key == "a" })
        assertFalse(table.removeAll { (_, value) -> value.longOrNull == 9L })
        assertEquals("t = {\n  # about a\n  b = 2,\n}\n", doc.toString())
    }

    @Test
    fun `retainAll on an inline table visits each pair of key and value once in order`() {
        val doc = parse("t = { b = 1, a = 2 }\n")
        val table = doc["t"].inlineTableOrNull!!
        val visited = mutableListOf<String>()
        assertTrue(
            table.retainAll { (key, value) ->
                visited += key
                value.longOrNull == 2L
            },
        )
        assertFalse(table.retainAll { true })
        assertEquals(listOf("b", "a"), visited)
        assertEquals("t = { a = 2 }\n", doc.toString())
    }

    @Test
    fun `removeAll on an array removes the values it accepts and keeps the comment above them`() {
        val doc = parse("ports = [\n  # plain\n  80, # http\n  443,\n]\n")
        val array = doc["ports"].arrayOrNull!!
        assertTrue(array.removeAll { it.longOrNull == 80L })
        assertFalse(array.removeAll { it.longOrNull == 80L })
        assertEquals("ports = [\n  # plain\n  443,\n]\n", doc.toString())
    }

    @Test
    fun `retainAll on an array returns whether it removed a value`() {
        val array = tomlArrayOf(Value.Integer(1L), Value.Integer(2L), Value.Integer(3L))
        assertFalse(array.retainAll { true })
        assertTrue(array.retainAll { it.longOrNull != 2L })
        assertEquals("[1, 3]", array.toString())
    }

    @Test
    fun `removeAll on an array of tables removes the tables it accepts and keeps the comment above them`() {
        val doc = parse("# first\n[[x]]\nn = 1\n# second\n[[x]]\nn = 2\n")
        val tables = doc["x"].arrayOfTablesOrNull!!
        assertTrue(tables.removeAll { it["n"].longOrNull == 1L })
        assertFalse(tables.removeAll { it["n"].longOrNull == 1L })
        assertEquals("# first\n# second\n[[x]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `retainAll on an array of tables returns whether it removed a table`() {
        val doc = parse("[[x]]\nn = 1\n[[x]]\nn = 2\n")
        val tables = doc["x"].arrayOfTablesOrNull!!
        assertFalse(tables.retainAll { true })
        assertTrue(tables.retainAll { it["n"].longOrNull == 2L })
        assertEquals("[[x]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `every container is not empty once it holds something`() {
        val doc = parse("a = [1]\nb = { c = 1 }\n[[d]]\n")
        assertTrue(doc.isNotEmpty())
        assertTrue(doc.root.isNotEmpty())
        assertTrue(doc["a"].arrayOrNull!!.isNotEmpty())
        assertTrue(doc["b"].inlineTableOrNull!!.isNotEmpty())
        assertTrue(doc["d"].arrayOfTablesOrNull!!.isNotEmpty())
        val tableLike: TableLike = doc.root
        assertTrue(tableLike.isNotEmpty())
    }

    @Test
    fun `every container is empty while it holds nothing`() {
        assertFalse(TomlDocument().isNotEmpty())
        assertFalse(Table().isNotEmpty())
        assertFalse(InlineTable().isNotEmpty())
        assertFalse(TomlArray().isNotEmpty())
        assertFalse(ArrayOfTables().isNotEmpty())
    }

    @Test
    fun `a key is in a table like when it holds an item`() {
        val doc = parse("a = 1\nt = { b = 2 }\n")
        val table: TableLike = doc.root
        val inline: TableLike = doc["t"].inlineTableOrNull!!
        assertTrue("a" in table)
        assertFalse("b" in table)
        assertTrue("b" in inline)
        assertFalse("a" in inline)
    }

    @Test
    fun `keys lists the keys of a table in order`() {
        val doc = parse("b = 1\na = 2\n[c]\n")
        assertEquals(listOf("b", "a", "c"), doc.root.keys.toList())
        assertEquals(listOf("b", "a", "c"), doc.keys.toList())
    }

    @Test
    fun `keys leaves out a key whose slot is empty`() {
        val table = tableOf("a" to Item(Value.Integer(1L)))
        table["b"] = Item()
        assertEquals(setOf("a"), table.keys)
        assertEquals(1, table.size)
    }

    @Test
    fun `keys is a copy that later changes to the table leave as it is`() {
        val table = tableOf("a" to Item(Value.Integer(1L)))
        val keys = table.keys
        table["b"] = Value.Integer(2L)
        table.remove("a")
        assertEquals(setOf("a"), keys)
    }

    @Test
    fun `keys lists the keys of an inline table in order`() {
        val doc = parse("t = { b = 1, a = 2 }\n")
        val table: TableLike = doc["t"].inlineTableOrNull!!
        assertEquals(listOf("b", "a"), table.keys.toList())
    }

    @Test
    fun `a document puts a parsed key with its spelling at the root`() {
        val doc = parse("'a' = 1\n")
        val old = doc.put(Key.parse("\"a\"").unwrap(), Item(Value.Integer(2L)))
        assertEquals(1L, old?.longOrNull)
        assertEquals("\"a\" = 2\n", doc.toString())
    }

    @Test
    fun `a document stores a parsed key with its spelling through getOrPut`() {
        val doc = TomlDocument()
        val stored = doc.getOrPut(Key.parse("'a'").unwrap()) { Item(Value.Integer(1L)) }
        assertEquals(1L, stored.longOrNull)
        assertEquals(1L, doc.getOrPut(Key("a")) { Item(Value.Integer(9L)) }.longOrNull)
        assertEquals("'a' = 1\n", doc.toString())
    }

    @Test
    fun `a document stores every pair of putAll at the root`() {
        val doc = TomlDocument()
        doc.putAll(listOf("a" to Item(Value.Integer(1L)), "b" to Item(Value.Integer(2L))))
        assertEquals("a = 1\nb = 2\n", doc.toString())
    }

    @Test
    fun `a document cleared keeps the comment above each key`() {
        val doc = parse("# about a\na = 1\n# about b\nb = 2\n")
        doc.clear()
        assertTrue(doc.isEmpty())
        assertEquals("# about a\n# about b\n", doc.toString())
    }

    @Test
    fun `a document retains and removes root pairs by a predicate`() {
        val doc = parse("a = 1\nb = 2\nc = 3\n")
        assertTrue(doc.retainAll { (key, _) -> key != "a" })
        assertTrue(doc.removeAll { (_, item) -> item.longOrNull == 3L })
        assertFalse(doc.removeAll { false })
        assertEquals("b = 2\n", doc.toString())
    }

    @Test
    fun `toTomlArray converts a copy of an array of tables and leaves it as it was`() {
        val doc = parse("[[x]]\nn = 1\n")
        val tables = doc["x"].arrayOfTablesOrNull!!
        assertEquals("[{ n = 1 }]", tables.toTomlArray().toString())
        assertEquals(1, tables.size)
        assertEquals("[[x]]\nn = 1\n", doc.toString())
    }

    @Test
    fun `getItem reads the item at a key of a table like`() {
        val doc = parse("a = 1\nt = { b = 'x' }\n")
        val table: TableLike = doc.root
        val inline: TableLike = doc["t"].inlineTableOrNull!!
        assertEquals(1L, table.getItem("a")?.longOrNull)
        assertEquals("x", inline.getItem("b")?.stringOrNull)
        assertNull(table.getItem("missing"))
    }

    @Test
    fun `setting an array of tables element keeps the comments around the old header`() {
        val doc = parse("[[x]]\nn = 1\n\n# second\n[[x]] # two\nn = 2\n")
        val tables = doc["x"].arrayOfTablesOrNull!!
        tables[1] = tableOf("n" to Item(Value.Integer(3L)))
        assertEquals("[[x]]\nn = 1\n\n# second\n[[x]] # two\nn = 3\n", doc.toString())
    }

    @Test
    fun `replacing an array of tables element without its decor prints the new table's own`() {
        val doc = parse("[[x]]\nn = 1\n\n# second\n[[x]] # two\nn = 2\n")
        val tables = doc["x"].arrayOfTablesOrNull!!
        val old = tables.replace(1, tableOf("n" to Item(Value.Integer(3L))), keepDecor = false).unwrap()
        assertEquals(2L, old["n"].longOrNull)
        assertEquals("[[x]]\nn = 1\n\n[[x]]\nn = 3\n", doc.toString())
    }

    @Test
    fun `setting an element past the end through the index form leaves the containers as they were`() {
        val array = tomlArrayOf(Value.Integer(1L))
        array[3] = Value.Integer(2L)
        val tables = arrayOfTablesOf(Table())
        tables[3] = Table()
        assertEquals("[1]", array.toString())
        assertEquals(1, tables.size)
    }

    @Test
    fun `an array finds a value by what it holds and not by an equal new value`() {
        val array = tomlArrayOf(Value.String("a"))
        assertFalse(array.contains(Value.String("a")))
        assertTrue(array.any { it.stringOrNull == "a" })
        assertEquals(0, array.indexOfFirst { it.stringOrNull == "a" })
    }
}
