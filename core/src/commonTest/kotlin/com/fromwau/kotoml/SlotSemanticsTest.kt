package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Storing an item never makes two places share one slot or its content, and a slot in an array, inline table or
 * array of tables holds only its kind.
 */
class SlotSemanticsTest {

    @Test
    fun `assigning a key after copying it into another leaves the copy alone`() {
        val doc = TomlDocument.parse("a = 1\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"].assign(Item(Value.Integer(2L))).unwrap()
        assertEquals(1L, doc["b"].longOrNull)
    }

    @Test
    fun `editing an array in place after copying it leaves the copy alone`() {
        val doc = TomlDocument.parse("a = [1]\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"].arrayOrNull!!.add(2L)
        assertEquals("a = [1, 2]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `editing a table in place after copying it leaves the copy alone`() {
        val doc = TomlDocument.parse("[a]\nx = 1\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"]["y"] = 2L
        assertEquals(null, doc["b"].getOrNull("y"))
    }

    @Test
    fun `table put stores a copy of the item`() {
        val table = Table()
        val item = Item(Value.Integer(1L))
        table.put("a", item)
        item.assign(Item(Value.Integer(2L))).unwrap()
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `table put with a key stores a copy of the item`() {
        val table = Table()
        val item = Item(Value.Integer(1L))
        table.put(Key("a"), item)
        item.assign(Item(Value.Integer(2L))).unwrap()
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `table putAll stores copies of the items`() {
        val item = Item(Value.Integer(1L))
        val table = Table()
        table.putAll(listOf("a" to item))
        item.assign(Item(Value.Integer(2L))).unwrap()
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `a table built from pairs stores copies of the items`() {
        val item = Item(Value.Integer(1L))
        val table = tableOf("a" to item)
        item.assign(Item(Value.Integer(2L))).unwrap()
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `document put stores a copy of the item`() {
        val doc = TomlDocument()
        val item = Item(Value.Integer(1L))
        doc.put("a", item)
        item.assign(Item(Value.Integer(2L))).unwrap()
        assertEquals(1L, doc["a"].longOrNull)
    }

    @Test
    fun `getOrPut on a missing key stores a copy and returns the stored slot`() {
        val doc = TomlDocument()
        val item = Item(Value.Integer(1L))
        val stored = doc.getOrPut("a") { item }
        item.assign(Item(Value.Integer(2L))).unwrap()
        stored.assign(Item(Value.Integer(3L))).unwrap()
        assertEquals(3L, doc["a"].longOrNull)
    }

    @Test
    fun `getOrPut on a present key returns its slot without calling default`() {
        val doc = TomlDocument.parse("a = 1\n").unwrap()
        val stored = doc.getOrPut("a") { error("default called") }
        stored.assign(Item(Value.Integer(2L))).unwrap()
        assertEquals("a = 2\n", doc.toString())
    }

    @Test
    fun `put on a present key stores a copy and returns the old item`() {
        val doc = TomlDocument.parse("a = 1\n").unwrap()
        val item = Item(Value.Integer(2L))
        val old = doc.put("a", item)
        item.assign(Item(Value.Integer(3L))).unwrap()
        assertEquals(1L, old?.longOrNull)
        assertEquals(2L, doc["a"].longOrNull)
    }

    @Test
    fun `inserting an item into an inline table stores a copy`() {
        val doc = TomlDocument.parse("a = [1]\nt = {}\n").unwrap()
        doc["t"].inlineTableOrNull!!.putItem("b", doc["a"]).unwrap()
        doc["a"].arrayOrNull!!.add(2L)
        assertEquals(1, doc["t"]["b"].arrayOrNull!!.size)
    }

    @Test
    fun `item set by key stores a copy of the content`() {
        val doc = TomlDocument.parse("a = [1]\n[t]\n").unwrap()
        doc["t"].set("b", doc["a"]).unwrap()
        doc["a"].arrayOrNull!!.add(2L)
        assertEquals("a = [1, 2]\n[t]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `the assigned item keeps its own content`() {
        val source = Item(Value.Array(tomlArrayOf(Value.Integer(1L))))
        val slot = Item()
        slot.assign(source).unwrap()
        source.arrayOrNull!!.add(2L)
        assertEquals(1, slot.arrayOrNull!!.size)
    }

    @Test
    fun `a table assigned into an array slot fails`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").unwrap()
        val error = doc["a"][0].assign(Item(Table())).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("a = [1, 2]\n", doc.toString())
    }

    @Test
    fun `an empty item assigned into an array slot fails`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").unwrap()
        val error = doc["a"].set(1, Item()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.None), error)
        assertEquals(2, doc["a"].arrayOrNull!!.size)
    }

    @Test
    fun `a value assigned into an array slot replaces the element`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").unwrap()
        doc["a"].set(1, Item(Value.Integer(3L))).unwrap()
        assertEquals("a = [1, 3]\n", doc.toString())
    }

    @Test
    fun `a value assigned into an array of tables slot fails`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").unwrap()
        val error = doc["a"][0].assign(Item(Value.Integer(1L))).unwrapErr()
        assertEquals(TomlEditError.NotATableElement(TomlType.Integer), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
        assertEquals(1, doc["a"].arrayOfTablesOrNull!!.size)
    }

    @Test
    fun `an array of tables slot refuses to become a value`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").unwrap()
        val error = doc["a"][0].makeValue().unwrapErr()
        assertEquals(TomlEditError.NotATableElement(TomlType.InlineTable), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table assigned into an array of tables slot replaces the element`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").unwrap()
        val replacement = Table()
        replacement["y"] = 2L
        doc["a"].set(0, Item(replacement)).unwrap()
        assertEquals("[[a]]\ny = 2\n", doc.toString())
    }

    @Test
    fun `an array turned into an array of tables keeps its own slots`() {
        val doc = TomlDocument.parse("a = [{ x = 1 }]\n").unwrap()
        val tables = doc["a"].copy().intoArrayOfTables()!!
        assertIs<TomlEditError.NotATableElement>(Item(tables)[0].assign(Item(Value.Integer(1L))).unwrapErr())
        assertEquals("a = [{ x = 1 }]\n", doc.toString())
    }

    @Test
    fun `an array of tables turned into an array holds only values`() {
        val tables = ArrayOfTables()
        tables.add(Table())
        val array = tables.toArray()
        assertIs<TomlEditError.NotAValue>(Item(Value.Array(array))[0].assign(Item(Table())).unwrapErr())
        assertEquals(1, array.size)
    }

    @Test
    fun `a table assigned into an inline table slot fails`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").unwrap()
        val error = doc["t"]["a"].assign(Item(Table())).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `an array of tables written to a new key of an inline table fails`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").unwrap()
        val error = doc["t"].set("b", Item(ArrayOfTables())).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.ArrayOfTables), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a value assigned into an inline table slot replaces it`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").unwrap()
        doc["t"]["a"].assign(Item(Value.Integer(2L))).unwrap()
        assertEquals("t = { a = 2 }\n", doc.toString())
    }

    @Test
    fun `an inline table refuses an empty item put at a new key`() {
        val inline = InlineTable()
        val error = inline.putItem("a", Item()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.None), error)
        assertEquals(0, inline.size)
    }

    @Test
    fun `an inline table refuses an empty item put at a present key`() {
        val inline = InlineTable()
        inline["a"] = 1L
        val error = inline.putItem("a", Item()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.None), error)
        assertEquals(1L, inline["a"]?.longOrNull)
    }

    @Test
    fun `a table put into an inline table becomes an inline table`() {
        val inline = InlineTable()
        inline.putItem("a", Item(tableOf("x" to Item(Value.Integer(1L))))).unwrap()
        assertEquals("{ a = { x = 1 } }", inline.toString())
    }

    @Test
    fun `a table written below an empty item fails because the item becomes an inline table`() {
        val doc = TomlDocument()
        val error = doc["a"].set("b", Item(Table())).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("", doc.toString())
    }

    @Test
    fun `a table turned into an inline table holds only values`() {
        val source = Table()
        source["a"] = 1L
        val inline = source.toInlineTable()
        val slot = Item(Value.InlineTable(inline))["a"]
        assertIs<TomlEditError.NotAValue>(slot.assign(Item(Table())).unwrapErr())
    }

    @Test
    fun `an inline table turned into a table takes any item`() {
        val inline = InlineTable()
        inline["a"] = 1L
        val converted = inline.toTable()
        val slot = converted.getOrNull("a")!!
        slot.assign(Item(Table())).unwrap()
        assertEquals(TomlType.Table, converted.getOrNull("a")!!.type)
    }

    @Test
    fun `a copied inline table holds only values`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").unwrap()
        doc["u"] = doc["t"]
        assertIs<TomlEditError.NotAValue>(doc["u"]["a"].assign(Item(Table())).unwrapErr())
    }

    @Test
    fun `array length counts what iteration yields`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").unwrap()
        doc["a"][0].assign(Item(Table()))
        val array = doc["a"].arrayOrNull!!
        assertEquals(array.count(), array.size)
    }

    @Test
    fun `converting a table to an inline table leaves the table as it is`() {
        val doc = TomlDocument.parse("[t]\na = 1\n").unwrap()
        val inline = doc["t"].tableOrNull!!.toInlineTable()
        assertEquals("{ a = 1 }", inline.toString())
        assertEquals("[t]\na = 1\n", doc.toString())
    }

    @Test
    fun `converting an inline table to a table leaves the inline table as it is`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").unwrap()
        val table = doc["t"].inlineTableOrNull!!.toTable()
        assertEquals("a = 1\n", table.toString())
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `converting an array of tables to an array leaves the array of tables as it is`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").unwrap()
        val array = doc["a"].arrayOfTablesOrNull!!.toArray()
        assertEquals("[{ x = 1 }]", array.toString())
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }
}
