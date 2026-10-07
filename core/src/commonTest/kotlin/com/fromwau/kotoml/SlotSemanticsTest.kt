package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
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
        val shared = Value.Array(tomlArrayOf(Value.Integer(1L)).assertSuccess())
        val first = TomlArray()
        val second = TomlArray()
        first.add(shared)
        second.add(shared)
        first[0].assertSuccess().arrayOrNull!!.add(2L)
        assertEquals("[[1, 2]] [[1]]", "$first $second")
    }

    @Test
    fun `an array added into itself holds a copy and prints`() {
        val array = tomlArrayOf(Value.Integer(1L)).assertSuccess()
        array.add(Value.Array(array))
        assertEquals("[1, [1]]", array.toString())
    }

    @Test
    fun `a table added to an array of tables is a copy`() {
        val doc = TomlDocument.parse("[t]\nx = 1\n").assertSuccess()
        val tables = ArrayOfTables()
        tables.add(doc["t"].assertSuccess().standardTableOrNull!!)
        doc["t"]["x"] = 2L
        assertEquals(1L, tables[0].assertSuccess().getOrNull("x")?.longOrNull)
    }

    @Test
    fun `replacing an element keeps the decor on the stored copy and leaves the given value alone`() {
        val doc = TomlDocument.parse("a = [ 1 ]\n").assertSuccess()
        val given = Value.Integer(2L)
        doc["a"].assertSuccess().arrayOrNull!!.replace(0, given).assertSuccess()
        assertEquals("a = [ 2 ]\n", doc.toString())
        assertEquals(Decor(), given.decor)
    }

    @Test
    fun `assigning a key after copying it into another leaves the copy alone`() {
        val doc = TomlDocument.parse("a = 1\n").assertSuccess()
        doc["b"] = doc["a"].assertSuccess()
        doc["a"].assertSuccess().assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals(1L, doc["b"].assertSuccess().longOrNull)
    }

    @Test
    fun `editing an array in place after copying it leaves the copy alone`() {
        val doc = TomlDocument.parse("a = [1]\n").assertSuccess()
        doc["b"] = doc["a"].assertSuccess()
        doc["a"].assertSuccess().arrayOrNull!!.add(2L)
        assertEquals("a = [1, 2]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `editing a table in place after copying it leaves the copy alone`() {
        val doc = TomlDocument.parse("[a]\nx = 1\n").assertSuccess()
        doc["b"] = doc["a"].assertSuccess()
        doc["a"]["y"] = 2L
        assertEquals(null, doc["b"].assertSuccess().getOrNull("y"))
    }

    @Test
    fun `table put stores a copy of the item`() {
        val table = StandardTable()
        val item = Item(Value.Integer(1L))
        table.put("a", item)
        item.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals(1L, table["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `table put with a key stores a copy of the item`() {
        val table = StandardTable()
        val item = Item(Value.Integer(1L))
        table.put(Key("a"), item)
        item.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals(1L, table["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `table putAll stores copies of the items`() {
        val item = Item(Value.Integer(1L))
        val table = StandardTable()
        table.putAll(listOf("a" to item))
        item.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals(1L, table["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `a table built from pairs stores copies of the items`() {
        val item = Item(Value.Integer(1L))
        val table = standardTableOf("a" to item).assertSuccess()
        item.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals(1L, table["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `document put stores a copy of the item`() {
        val doc = TomlDocument()
        val item = Item(Value.Integer(1L))
        doc.put("a", item)
        item.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals(1L, doc["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `getOrPut on a missing key stores a copy and returns the stored slot`() {
        val doc = TomlDocument()
        val item = Item(Value.Integer(1L))
        val stored = doc.getOrPut("a") { item }.assertSuccess()
        item.assign(Item(Value.Integer(2L))).assertSuccess()
        stored.assign(Item(Value.Integer(3L))).assertSuccess()
        assertEquals(3L, doc["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `getOrPut on a present key returns its slot without calling default`() {
        val doc = TomlDocument.parse("a = 1\n").assertSuccess()
        val stored = doc.getOrPut("a") { error("default called") }.assertSuccess()
        stored.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals("a = 2\n", doc.toString())
    }

    @Test
    fun `put on a present key stores a copy and returns the old item`() {
        val doc = TomlDocument.parse("a = 1\n").assertSuccess()
        val item = Item(Value.Integer(2L))
        val old = doc.put("a", item).assertSuccess()
        item.assign(Item(Value.Integer(3L))).assertSuccess()
        assertEquals(1L, old?.longOrNull)
        assertEquals(2L, doc["a"].assertSuccess().longOrNull)
    }

    @Test
    fun `inserting an item into an inline table stores a copy`() {
        val doc = TomlDocument.parse("a = [1]\nt = {}\n").assertSuccess()
        doc["t"].assertSuccess().inlineTableOrNull!!.putItem("b", doc["a"].assertSuccess())
        doc["a"].assertSuccess().arrayOrNull!!.add(2L)
        assertEquals(1, doc["t"]["b"].assertSuccess().arrayOrNull!!.size)
    }

    @Test
    fun `item set by key stores a copy of the content`() {
        val doc = TomlDocument.parse("a = [1]\n[t]\n").assertSuccess()
        doc["t"].set("b", doc["a"].assertSuccess()).assertSuccess()
        doc["a"].assertSuccess().arrayOrNull!!.add(2L)
        assertEquals("a = [1, 2]\n[t]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `the assigned item keeps its own content`() {
        val source = Item(Value.Array(tomlArrayOf(Value.Integer(1L)).assertSuccess()))
        val slot = Item(Value.Integer(0L))
        slot.assign(source).assertSuccess()
        source.arrayOrNull!!.add(2L)
        assertEquals(1, slot.arrayOrNull!!.size)
    }

    @Test
    fun `a table assigned into an array slot fails`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").assertSuccess()
        val error = doc["a"][0].assertSuccess().assign(Item(StandardTable())).assertError<TomlEditError.NotAValue>()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("a = [1, 2]\n", doc.toString())
    }

    @Test
    fun `a value assigned into an array slot replaces the element`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").assertSuccess()
        doc["a"].set(1, Item(Value.Integer(3L))).assertSuccess()
        assertEquals("a = [1, 3]\n", doc.toString())
    }

    @Test
    fun `a value assigned into an array of tables slot fails`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").assertSuccess()
        val error = doc["a"][0].assertSuccess().assign(Item(Value.Integer(1L))).assertError<TomlEditError.NotATableElement>()
        assertEquals(TomlEditError.NotATableElement(TomlType.Integer), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
        assertEquals(1, doc["a"].assertSuccess().arrayOfTablesOrNull!!.size)
    }

    @Test
    fun `an array of tables slot refuses to become a value`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").assertSuccess()
        val error = doc["a"][0].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.NotATableElement>()
        assertEquals(TomlEditError.NotATableElement(TomlType.InlineTable), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table assigned into an array of tables slot replaces the element`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").assertSuccess()
        val replacement = StandardTable()
        replacement["y"] = 2L
        doc["a"].set(0, Item(replacement)).assertSuccess()
        assertEquals("[[a]]\ny = 2\n", doc.toString())
    }

    @Test
    fun `an array turned into an array of tables keeps its own slots`() {
        val doc = TomlDocument.parse("a = [{ x = 1 }]\n").assertSuccess()
        val tables = doc["a"].assertSuccess().copy().moveToArrayOfTables()!!
        val error = Item(tables)[0].assertSuccess().assign(Item(Value.Integer(1L))).assertError<TomlEditError.NotATableElement>()
        assertIs<TomlEditError.NotATableElement>(error)
        assertEquals("a = [{ x = 1 }]\n", doc.toString())
    }

    @Test
    fun `an array of tables turned into an array holds only values`() {
        val tables = ArrayOfTables()
        tables.add(StandardTable())
        val array = tables.toTomlArray().assertSuccess()
        val error = Item(Value.Array(array))[0].assertSuccess().assign(Item(StandardTable())).assertError<TomlEditError.NotAValue>()
        assertIs<TomlEditError.NotAValue>(error)
        assertEquals(1, array.size)
    }

    @Test
    fun `a table assigned into an inline table slot fails`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").assertSuccess()
        val error = doc["t"]["a"].assertSuccess().assign(Item(StandardTable())).assertError<TomlEditError.NotAValue>()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `an array of tables written to a new key of an inline table fails`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").assertSuccess()
        val error = doc["t"].set("b", Item(ArrayOfTables())).assertError<TomlEditError.NotAValue>()
        assertEquals(TomlEditError.NotAValue(TomlType.ArrayOfTables), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a value assigned into an inline table slot replaces it`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").assertSuccess()
        doc["t"]["a"].assertSuccess().assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals("t = { a = 2 }\n", doc.toString())
    }

    @Test
    fun `a table put into an inline table is NotAValue and stores nothing`() {
        val inline = InlineTable()
        val table = standardTableOf("x" to Item(Value.Integer(1L))).assertSuccess()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), inline.putItem("a", Item(table)).assertError<TomlEditError.NotAValue>())
        assertEquals("{}", inline.toString())
    }

    @Test
    fun `an array of tables put into an inline table is NotAValue`() {
        val inline = InlineTable()
        val tables = arrayOfTablesOf(StandardTable()).assertSuccess()
        assertEquals(
            TomlEditError.NotAValue(TomlType.ArrayOfTables),
            inline.putItem("a", Item(tables)).assertError<TomlEditError.NotAValue>(),
        )
    }

    @Test
    fun `a table turned into an inline table holds only values`() {
        val source = StandardTable()
        source["a"] = 1L
        val inline = source.toInlineTable().assertSuccess()
        val slot = Item(Value.InlineTable(inline))["a"]
        assertIs<TomlEditError.NotAValue>(slot.assertSuccess().assign(Item(StandardTable())).assertError<TomlEditError.NotAValue>())
    }

    @Test
    fun `an inline table turned into a table takes any item`() {
        val inline = InlineTable()
        inline["a"] = 1L
        val converted = inline.toStandardTable()
        val slot = converted.getOrNull("a")!!
        slot.assign(Item(StandardTable())).assertSuccess()
        assertEquals(TomlType.Table, converted.getOrNull("a")!!.type)
    }

    @Test
    fun `a copied inline table holds only values`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").assertSuccess()
        doc["u"] = doc["t"].assertSuccess()
        assertIs<TomlEditError.NotAValue>(doc["u"]["a"].assertSuccess().assign(Item(StandardTable())).assertError<TomlEditError.NotAValue>())
    }

    @Test
    fun `array length counts what iteration yields`() {
        val doc = TomlDocument.parse("a = [1, 2]\n").assertSuccess()
        doc["a"][0].assertSuccess().assign(Item(StandardTable()))
        val array = doc["a"].assertSuccess().arrayOrNull!!
        assertEquals(array.count(), array.size)
    }

    @Test
    fun `converting a table to an inline table leaves the table as it is`() {
        val doc = TomlDocument.parse("[t]\na = 1\n").assertSuccess()
        val inline = doc["t"].assertSuccess().standardTableOrNull!!.toInlineTable().assertSuccess()
        assertEquals("{ a = 1 }", inline.toString())
        assertEquals("[t]\na = 1\n", doc.toString())
    }

    @Test
    fun `converting an inline table to a table leaves the inline table as it is`() {
        val doc = TomlDocument.parse("t = { a = 1 }\n").assertSuccess()
        val table = doc["t"].assertSuccess().inlineTableOrNull!!.toStandardTable()
        assertEquals("a = 1\n", table.toString())
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `converting an array of tables to an array leaves the array of tables as it is`() {
        val doc = TomlDocument.parse("[[a]]\nx = 1\n").assertSuccess()
        val array = doc["a"].assertSuccess().arrayOfTablesOrNull!!.toTomlArray().assertSuccess()
        assertEquals("[{ x = 1 }]", array.toString())
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }
}
