package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Storing an item never makes two places share one slot or its content, and an array slot holds only its kind. */
class SlotSemanticsTest {

    @Test
    fun `assigning a key after copying it into another leaves the copy alone`() {
        val doc = DocumentMut.parse("a = 1\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"].assign(value(2L)).unwrap()
        assertEquals(1L, doc["b"].asInteger())
    }

    @Test
    fun `editing an array in place after copying it leaves the copy alone`() {
        val doc = DocumentMut.parse("a = [1]\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"].asArrayMut()!!.push(2L)
        assertEquals("a = [1, 2]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `editing a table in place after copying it leaves the copy alone`() {
        val doc = DocumentMut.parse("[a]\nx = 1\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"]["y"] = value(2L)
        assertEquals(null, doc["b"].getOrNull("y"))
    }

    @Test
    fun `table insert stores a copy of the item`() {
        val table = Table()
        val item = value(1L)
        table.insert("a", item)
        item.assign(value(2L)).unwrap()
        assertEquals(1L, table["a"].asInteger())
    }

    @Test
    fun `table insertFormatted stores a copy of the item`() {
        val table = Table()
        val item = value(1L)
        table.insertFormatted(Key("a"), item)
        item.assign(value(2L)).unwrap()
        assertEquals(1L, table["a"].asInteger())
    }

    @Test
    fun `table extend stores copies of the items`() {
        val item = value(1L)
        val table = Table.fromIter(listOf("a" to item))
        item.assign(value(2L)).unwrap()
        assertEquals(1L, table["a"].asInteger())
    }

    @Test
    fun `document insert stores a copy of the item`() {
        val doc = DocumentMut()
        val item = value(1L)
        doc.insert("a", item)
        item.assign(value(2L)).unwrap()
        assertEquals(1L, doc["a"].asInteger())
    }

    @Test
    fun `a vacant entry stores a copy and returns the stored slot`() {
        val doc = DocumentMut()
        val item = value(1L)
        val stored = doc.entry("a").orInsert(item)
        item.assign(value(2L)).unwrap()
        stored.assign(value(3L)).unwrap()
        assertEquals(3L, doc["a"].asInteger())
    }

    @Test
    fun `an occupied entry stores a copy of the item`() {
        val doc = DocumentMut.parse("a = 1\n").unwrap()
        val item = value(2L)
        (doc.entry("a") as OccupiedEntry).insert(item)
        item.assign(value(3L)).unwrap()
        assertEquals(2L, doc["a"].asInteger())
    }

    @Test
    fun `an inline table entry stores a copy of the item`() {
        val inline = InlineTable()
        val item = value(1L)
        inline.itemEntry("a").orInsert(item)
        item.assign(value(2L)).unwrap()
        assertEquals(1L, inline["a"]?.asInteger())
    }

    @Test
    fun `inserting an item into an inline table stores a copy`() {
        val doc = DocumentMut.parse("a = [1]\nt = {}\n").unwrap()
        doc["t"].asInlineTableMut()!!.insertItem("b", doc["a"]).unwrap()
        doc["a"].asArrayMut()!!.push(2L)
        assertEquals(1, doc["t"]["b"].asArray()!!.len())
    }

    @Test
    fun `item set by key stores a copy of the content`() {
        val doc = DocumentMut.parse("a = [1]\n[t]\n").unwrap()
        doc["t"].set("b", doc["a"]).unwrap()
        doc["a"].asArrayMut()!!.push(2L)
        assertEquals("a = [1, 2]\n[t]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `the assigned item keeps its own content`() {
        val source = value(TomlArray.fromIter(listOf(Value.from(1L))))
        val slot = Item.none()
        slot.assign(source).unwrap()
        source.asArrayMut()!!.push(2L)
        assertEquals(1, slot.asArray()!!.len())
    }

    @Test
    fun `a table assigned into an array slot fails`() {
        val doc = DocumentMut.parse("a = [1, 2]\n").unwrap()
        val error = doc["a"][0].assign(table()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("a = [1, 2]\n", doc.toString())
    }

    @Test
    fun `an empty item assigned into an array slot fails`() {
        val doc = DocumentMut.parse("a = [1, 2]\n").unwrap()
        val error = doc["a"].set(1, Item.none()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.None), error)
        assertEquals(2, doc["a"].asArray()!!.len())
    }

    @Test
    fun `a value assigned into an array slot replaces the element`() {
        val doc = DocumentMut.parse("a = [1, 2]\n").unwrap()
        doc["a"].set(1, value(3L)).unwrap()
        assertEquals("a = [1, 3]\n", doc.toString())
    }

    @Test
    fun `a value assigned into an array of tables slot fails`() {
        val doc = DocumentMut.parse("[[a]]\nx = 1\n").unwrap()
        val error = doc["a"][0].assign(value(1L)).unwrapErr()
        assertEquals(TomlEditError.NotATableElement(TomlType.Integer), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
        assertEquals(1, doc["a"].asArrayOfTables()!!.len())
    }

    @Test
    fun `an array of tables slot refuses to become a value`() {
        val doc = DocumentMut.parse("[[a]]\nx = 1\n").unwrap()
        val error = doc["a"][0].makeValue().unwrapErr()
        assertEquals(TomlEditError.NotATableElement(TomlType.InlineTable), error)
        assertEquals("[[a]]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table assigned into an array of tables slot replaces the element`() {
        val doc = DocumentMut.parse("[[a]]\nx = 1\n").unwrap()
        val replacement = Table()
        replacement["y"] = value(2L)
        doc["a"].set(0, Item.from(replacement)).unwrap()
        assertEquals("[[a]]\ny = 2\n", doc.toString())
    }

    @Test
    fun `an array turned into an array of tables keeps its own slots`() {
        val doc = DocumentMut.parse("a = [{ x = 1 }]\n").unwrap()
        val tables = doc["a"].clone().intoArrayOfTables()!!
        assertIs<TomlEditError.NotATableElement>(Item.from(tables)[0].assign(value(1L)).unwrapErr())
        assertEquals("a = [{ x = 1 }]\n", doc.toString())
    }

    @Test
    fun `an array of tables turned into an array holds only values`() {
        val tables = ArrayOfTables()
        tables.push(Table())
        val array = tables.intoArray()
        assertIs<TomlEditError.NotAValue>(Item.from(array)[0].assign(table()).unwrapErr())
        assertEquals(1, array.len())
    }

    @Test
    fun `array length counts what iteration yields`() {
        val doc = DocumentMut.parse("a = [1, 2]\n").unwrap()
        doc["a"][0].assign(table())
        val array = doc["a"].asArray()!!
        assertEquals(array.iter().asSequence().count(), array.len())
    }
}
