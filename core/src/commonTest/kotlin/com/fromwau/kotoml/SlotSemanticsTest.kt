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
        val doc = DocumentMut.parse("a = 1\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"].assign(value(2L)).unwrap()
        assertEquals(1L, doc["b"].longOrNull)
    }

    @Test
    fun `editing an array in place after copying it leaves the copy alone`() {
        val doc = DocumentMut.parse("a = [1]\n").unwrap()
        doc["b"] = doc["a"]
        doc["a"].arrayOrNull!!.push(2L)
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
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `table insertFormatted stores a copy of the item`() {
        val table = Table()
        val item = value(1L)
        table.insertFormatted(Key("a"), item)
        item.assign(value(2L)).unwrap()
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `table extend stores copies of the items`() {
        val item = value(1L)
        val table = Table.fromIter(listOf("a" to item))
        item.assign(value(2L)).unwrap()
        assertEquals(1L, table["a"].longOrNull)
    }

    @Test
    fun `document insert stores a copy of the item`() {
        val doc = DocumentMut()
        val item = value(1L)
        doc.insert("a", item)
        item.assign(value(2L)).unwrap()
        assertEquals(1L, doc["a"].longOrNull)
    }

    @Test
    fun `a vacant entry stores a copy and returns the stored slot`() {
        val doc = DocumentMut()
        val item = value(1L)
        val stored = doc
            .entry("a")
            .orInsert(item)
            .unwrap()
        item.assign(value(2L)).unwrap()
        stored.assign(value(3L)).unwrap()
        assertEquals(3L, doc["a"].longOrNull)
    }

    @Test
    fun `an occupied entry stores a copy of the item`() {
        val doc = DocumentMut.parse("a = 1\n").unwrap()
        val item = value(2L)
        val entry = doc.entry("a") as OccupiedEntry
        entry.insert(item).unwrap()
        item.assign(value(3L)).unwrap()
        assertEquals(2L, doc["a"].longOrNull)
    }

    @Test
    fun `an inline table entry stores a copy of the item`() {
        val inline = InlineTable()
        val item = value(1L)
        inline
            .itemEntry("a")
            .orInsert(item)
            .unwrap()
        item.assign(value(2L)).unwrap()
        assertEquals(1L, inline["a"]?.longOrNull)
    }

    @Test
    fun `inserting an item into an inline table stores a copy`() {
        val doc = DocumentMut.parse("a = [1]\nt = {}\n").unwrap()
        doc["t"].inlineTableOrNull!!.insertItem("b", doc["a"]).unwrap()
        doc["a"].arrayOrNull!!.push(2L)
        assertEquals(1, doc["t"]["b"].arrayOrNull!!.len())
    }

    @Test
    fun `item set by key stores a copy of the content`() {
        val doc = DocumentMut.parse("a = [1]\n[t]\n").unwrap()
        doc["t"].set("b", doc["a"]).unwrap()
        doc["a"].arrayOrNull!!.push(2L)
        assertEquals("a = [1, 2]\n[t]\nb = [1]\n", doc.toString())
    }

    @Test
    fun `the assigned item keeps its own content`() {
        val source = value(TomlArray.fromIter(listOf(Value.Integer(1L))))
        val slot = Item.none()
        slot.assign(source).unwrap()
        source.arrayOrNull!!.push(2L)
        assertEquals(1, slot.arrayOrNull!!.len())
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
        assertEquals(2, doc["a"].arrayOrNull!!.len())
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
    fun `a table assigned into an inline table slot fails`() {
        val doc = DocumentMut.parse("t = { a = 1 }\n").unwrap()
        val error = doc["t"]["a"].assign(table()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `an array of tables written to a new key of an inline table fails`() {
        val doc = DocumentMut.parse("t = { a = 1 }\n").unwrap()
        val error = doc["t"].set("b", array()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.ArrayOfTables), error)
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `a value assigned into an inline table slot replaces it`() {
        val doc = DocumentMut.parse("t = { a = 1 }\n").unwrap()
        doc["t"]["a"].assign(value(2L)).unwrap()
        assertEquals("t = { a = 2 }\n", doc.toString())
    }

    @Test
    fun `a vacant inline table entry refuses a table`() {
        val inline = InlineTable()
        val error = inline
            .itemEntry("a")
            .orInsert(table())
            .unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals(0, inline.len())
    }

    @Test
    fun `an occupied inline table entry refuses a table`() {
        val inline = InlineTable()
        inline.insert("a", Value.Integer(1L))
        val entry = inline.itemEntry("a") as OccupiedEntry
        val error = entry.insert(table()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals(1L, inline["a"]?.longOrNull)
    }

    @Test
    fun `a table written below an empty item fails because the item becomes an inline table`() {
        val doc = DocumentMut()
        val error = doc["a"].set("b", table()).unwrapErr()
        assertEquals(TomlEditError.NotAValue(TomlType.Table), error)
        assertEquals("", doc.toString())
    }

    @Test
    fun `a table turned into an inline table holds only values`() {
        val source = Table()
        source["a"] = value(1L)
        val inline = source.intoInlineTable()
        val slot = Item.from(inline)["a"]
        assertIs<TomlEditError.NotAValue>(slot.assign(table()).unwrapErr())
    }

    @Test
    fun `an inline table turned into a table takes any item`() {
        val inline = InlineTable()
        inline.insert("a", Value.Integer(1L))
        val converted = inline.intoTable()
        val slot = converted.getOrNull("a")!!
        slot.assign(table()).unwrap()
        assertEquals(TomlType.Table, converted.getOrNull("a")!!.type)
    }

    @Test
    fun `a copied inline table holds only values`() {
        val doc = DocumentMut.parse("t = { a = 1 }\n").unwrap()
        doc["u"] = doc["t"]
        assertIs<TomlEditError.NotAValue>(doc["u"]["a"].assign(table()).unwrapErr())
    }

    @Test
    fun `array length counts what iteration yields`() {
        val doc = DocumentMut.parse("a = [1, 2]\n").unwrap()
        doc["a"][0].assign(table())
        val array = doc["a"].arrayOrNull!!
        assertEquals(array.iter().asSequence().count(), array.len())
    }
}
