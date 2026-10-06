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
    fun `a value added to two arrays is two values`() {
        val shared = Value.Array(tomlArrayOf(Value.Integer(1L)).getOrFail())
        val first = TomlArray()
        val second = TomlArray()
        first.add(shared)
        second.add(shared)
        first[0].getOrFail().arrayOrNull!!.add(2L)
        assertEquals("[[1, 2]] [[1]]", "$first $second")
    }

    @Test
    fun `an array added into itself holds a copy and prints`() {
        val array = tomlArrayOf(Value.Integer(1L)).getOrFail()
        array.add(Value.Array(array))
        assertEquals("[1, [1]]", array.toString())
    }

    @Test
    fun `a table added to an array of tables is a copy`() {
        val doc = TomlDocument.parse("[t]\nx = 1\n").getOrFail()
        val tables = ArrayOfTables()
        tables.add(doc["t"].getOrFail().standardTableOrNull!!)
        doc["t"]["x"] = 2L
        assertEquals(1L, tables[0].getOrFail().getOrNull("x")?.longOrNull)
    }

    @Test
    fun `replacing an element keeps the decor on the stored copy and leaves the given value alone`() {
        val doc = TomlDocument.parse("a = [ 1 ]\n").getOrFail()
        val given = Value.Integer(2L)
        doc["a"].getOrFail().arrayOrNull!!.replace(0, given).getOrFail()
        assertEquals("a = [ 2 ]\n", doc.toString())
        assertEquals(Decor(), given.decor)
    }

    @Test
    fun `assigning a key after copying it into another leaves the copy alone`() {
        val doc = TomlDocument.parse("a = 1\n").getOrFail()
        doc["b"] = doc["a"].getOrFail()
        doc["a"].getOrFail().assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals(1L, doc["b"].getOrFail().longOrNull)
    }

    @Test
    fun `editing an array in place after copying it leaves the copy alone`() {
        val doc = TomlDocument.parse("a = [1]\n").getOrFail()
        doc["b"] = doc["a"].getOrFail()
        doc["a"].getOrFail().arrayOrNull!!.add(2L)
        assertEquals("a = [1, 2]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `editing a table in place after copying it leaves the copy alone`() {
        val doc = TomlDocument.parse("[a]\nx = 1\n").getOrFail()
        doc["b"] = doc["a"].getOrFail()
        doc["a"]["y"] = 2L
        assertEquals(null, doc["b"].getOrFail().getOrNull("y"))
    }

    @Test
    fun `table put stores a copy of the item`() {
        val table = StandardTable()
        val item = Item(Value.Integer(1L))
        table.put("a", item)
        item.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals(1L, table["a"].getOrFail().longOrNull)
    }

    @Test
    fun `table put with a key stores a copy of the item`() {
        val table = StandardTable()
        val item = Item(Value.Integer(1L))
        table.put(Key("a"), item)
        item.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals(1L, table["a"].getOrFail().longOrNull)
    }

    @Test
    fun `table putAll stores copies of the items`() {
        val item = Item(Value.Integer(1L))
        val table = StandardTable()
        table.putAll(listOf("a" to item))
        item.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals(1L, table["a"].getOrFail().longOrNull)
    }

    @Test
    fun `a table built from pairs stores copies of the items`() {
        val item = Item(Value.Integer(1L))
        val table = standardTableOf("a" to item).getOrFail()
        item.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals(1L, table["a"].getOrFail().longOrNull)
    }

    @Test
    fun `document put stores a copy of the item`() {
        val doc = TomlDocument()
        val item = Item(Value.Integer(1L))
        doc.put("a", item)
        item.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals(1L, doc["a"].getOrFail().longOrNull)
    }

    @Test
    fun `getOrPut on a missing key stores a copy and returns the stored slot`() {
        val doc = TomlDocument()
        val item = Item(Value.Integer(1L))
        val stored = doc.getOrPut("a") { item }.getOrFail()
        item.assign(Item(Value.Integer(2L))).getOrFail()
        stored.assign(Item(Value.Integer(3L))).getOrFail()
        assertEquals(3L, doc["a"].getOrFail().longOrNull)
    }

    @Test
    fun `getOrPut on a present key returns its slot without calling default`() {
        val doc = TomlDocument.parse("a = 1\n").getOrFail()
        val stored = doc.getOrPut("a") { error("default called") }.getOrFail()
        stored.assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals("a = 2\n", doc.toString())
    }

    @Test
    fun `put on a present key stores a copy and returns the old item`() {
        val doc = TomlDocument.parse("a = 1\n").getOrFail()
        val item = Item(Value.Integer(2L))
        val old = doc.put("a", item).getOrFail()
        item.assign(Item(Value.Integer(3L))).getOrFail()
        assertEquals(1L, old?.longOrNull)
        assertEquals(2L, doc["a"].getOrFail().longOrNull)
    }

    @Test
    fun `inserting an item into an inline table stores a copy`() {
        val doc = TomlDocument.parse("a = [1]\nt = {}\n").getOrFail()
        doc["t"].getOrFail().inlineTableOrNull!!.putItem("b", doc["a"].getOrFail())
        doc["a"].getOrFail().arrayOrNull!!.add(2L)
        assertEquals(1, doc["t"]["b"].getOrFail().arrayOrNull!!.size)
    }

    @Test
    fun `item set by key stores a copy of the content`() {
        val doc = TomlDocument.parse("a = [1]\n[t]\n").getOrFail()
        doc["t"].set("b", doc["a"].getOrFail()).getOrFail()
        doc["a"].getOrFail().arrayOrNull!!.add(2L)
        assertEquals("a = [1, 2]\n[t]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `the assigned item keeps its own content`() {
        val source = Item(Value.Array(tomlArrayOf(Value.Integer(1L)).getOrFail()))
        val slot = Item(Value.Integer(0L))
        slot.assign(source).getOrFail()
        source.arrayOrNull!!.add(2L)
        assertEquals(1, slot.arrayOrNull!!.size)
    }

    @Test
    fun `a table assigned into an array slot fails`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").getOrFail()
        val error = doc["a"][0].getOrFail().assign(Item(StandardTable())).errorOrFail()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("a = [1, 2]\n", doc.toString())
    }

    @Test
    fun `a value assigned into an array slot replaces the element`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").getOrFail()
        doc["a"].set(1, Item(Value.Integer(3L))).getOrFail()
        assertEquals("a = [1, 3]\n", doc.toString())
    }

    @Test
    fun `a value assigned into an array of tables slot fails`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").getOrFail()
        val error = doc["a"][0].getOrFail().assign(Item(Value.Integer(1L))).errorOrFail()
        assertEquals(TomlEditError.NotATableElement(TomlType.Integer), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
        assertEquals(1, doc["a"].getOrFail().arrayOfTablesOrNull!!.size)
    }

    @Test
    fun `an array of tables slot refuses to become a value`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").getOrFail()
        val error = doc["a"][0].getOrFail().inline().errorOrFail()
        assertEquals(TomlEditError.NotATableElement(TomlType.InlineTable), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table assigned into an array of tables slot replaces the element`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").getOrFail()
        val replacement = StandardTable()
        replacement["y"] = 2L
        doc["a"].set(0, Item(replacement)).getOrFail()
        assertEquals("[[a]]\ny = 2\n", doc.toString())
    }

    @Test
    fun `an array turned into an array of tables keeps its own slots`() {
        val doc = TomlDocument.parse("a = [{ x = 1 }]\n").getOrFail()
        val tables = doc["a"].getOrFail().copy().moveToArrayOfTables()!!
        val error = Item(tables)[0].getOrFail().assign(Item(Value.Integer(1L))).errorOrFail()
        assertIs<TomlEditError.NotATableElement>(error)
        assertEquals("a = [{ x = 1 }]\n", doc.toString())
    }

    @Test
    fun `an array of tables turned into an array holds only values`() {
        val tables = ArrayOfTables()
        tables.add(StandardTable())
        val array = tables.toTomlArray().getOrFail()
        val error = Item(Value.Array(array))[0].getOrFail().assign(Item(StandardTable())).errorOrFail()
        assertIs<TomlEditError.NotAValue>(error)
        assertEquals(1, array.size)
    }

    @Test
    fun `a table assigned into an inline table slot fails`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").getOrFail()
        val error = doc["t"]["a"].getOrFail().assign(Item(StandardTable())).errorOrFail()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `an array of tables written to a new key of an inline table fails`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").getOrFail()
        val error = doc["t"].set("b", Item(ArrayOfTables())).errorOrFail()
        assertEquals(TomlEditError.NotAValue(TomlType.ArrayOfTables), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a value assigned into an inline table slot replaces it`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").getOrFail()
        doc["t"]["a"].getOrFail().assign(Item(Value.Integer(2L))).getOrFail()
        assertEquals("t = { a = 2 }\n", doc.toString())
    }

    @Test
    fun `a table put into an inline table is NotAValue and stores nothing`() {
        val inline = InlineTable()
        val table = standardTableOf("x" to Item(Value.Integer(1L))).getOrFail()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), inline.putItem("a", Item(table)).errorOrFail())
        assertEquals("{}", inline.toString())
    }

    @Test
    fun `an array of tables put into an inline table is NotAValue`() {
        val inline = InlineTable()
        val tables = arrayOfTablesOf(StandardTable()).getOrFail()
        assertEquals(
            TomlEditError.NotAValue(TomlType.ArrayOfTables),
            inline.putItem("a", Item(tables)).errorOrFail(),
        )
    }

    @Test
    fun `a table turned into an inline table holds only values`() {
        val source = StandardTable()
        source["a"] = 1L
        val inline = source.toInlineTable().getOrFail()
        val slot = Item(Value.InlineTable(inline))["a"]
        assertIs<TomlEditError.NotAValue>(slot.getOrFail().assign(Item(StandardTable())).errorOrFail())
    }

    @Test
    fun `an inline table turned into a table takes any item`() {
        val inline = InlineTable()
        inline["a"] = 1L
        val converted = inline.toStandardTable()
        val slot = converted.getOrNull("a")!!
        slot.assign(Item(StandardTable())).getOrFail()
        assertEquals(TomlType.Table, converted.getOrNull("a")!!.type)
    }

    @Test
    fun `a copied inline table holds only values`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").getOrFail()
        doc["u"] = doc["t"].getOrFail()
        assertIs<TomlEditError.NotAValue>(doc["u"]["a"].getOrFail().assign(Item(StandardTable())).errorOrFail())
    }

    @Test
    fun `array length counts what iteration yields`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").getOrFail()
        doc["a"][0].getOrFail().assign(Item(StandardTable()))
        val array = doc["a"].getOrFail().arrayOrNull!!
        assertEquals(array.count(), array.size)
    }

    @Test
    fun `converting a table to an inline table leaves the table as it is`() {
        val doc = TomlDocument.parse("[t]\na = 1\n").getOrFail()
        val inline = doc["t"].getOrFail().standardTableOrNull!!.toInlineTable().getOrFail()
        assertEquals("{ a = 1 }", inline.toString())
        assertEquals("[t]\na = 1\n", doc.toString())
    }

    @Test
    fun `converting an inline table to a table leaves the inline table as it is`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").getOrFail()
        val table = doc["t"].getOrFail().inlineTableOrNull!!.toStandardTable()
        assertEquals("a = 1\n", table.toString())
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `converting an array of tables to an array leaves the array of tables as it is`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").getOrFail()
        val array = doc["a"].getOrFail().arrayOfTablesOrNull!!.toTomlArray().getOrFail()
        assertEquals("[{ x = 1 }]", array.toString())
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }
}
