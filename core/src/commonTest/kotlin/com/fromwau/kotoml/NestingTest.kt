package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** Every table and array knows where it stands in the text it prints, counted as the parser counts nesting. */
class NestingTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).getOrFail()

    private fun TomlDocument.item(vararg keys: String): Item =
        keys.drop(1).fold(this[keys.first()]) { item, key -> item[key] }.getOrFail()

    @Test
    fun `the root stands as a header with no parts`() {
        assertEquals(Nesting.InTable(parts = 0, dots = 0), nestingOf(parse("a = 1\n").root))
    }

    @Test
    fun `a table under a header stands at the header's parts`() {
        val doc = parse("[a.b]\nx = 1\n")
        assertEquals(Nesting.InTable(parts = 2, dots = 0), nestingOf(doc.item("a", "b").standardTableOrNull!!))
    }

    @Test
    fun `a dotted table counts its dots`() {
        val doc = parse("[h]\na.b.c = 1\n")
        assertEquals(Nesting.InTable(parts = 3, dots = 2), nestingOf(doc.item("h", "a", "b").standardTableOrNull!!))
    }

    @Test
    fun `an array counts the dots of its line and the brackets around it`() {
        val doc = parse("x.y = [[1]]\n")
        val outer = doc.item("x", "y").arrayOrNull!!
        assertEquals(Nesting.InLine(level = 2), nestingOf(outer))
        assertEquals(Nesting.InLine(level = 3), nestingOf(outer[0].getOrFail().arrayOrNull!!))
    }

    @Test
    fun `an inline table in an inline table stands one level deeper`() {
        val doc = parse("t = { a = { b = 1 } }\n")
        assertEquals(Nesting.InLine(level = 2), nestingOf(doc.item("t", "a").inlineTableOrNull!!))
    }

    @Test
    fun `an array of tables and its tables stand at its header's parts`() {
        val doc = parse("[[s.t]]\nx = 1\n")
        val tables = doc.item("s", "t").arrayOfTablesOrNull!!
        assertEquals(Nesting.InTables(parts = 2), nestingOf(tables))
        assertEquals(Nesting.InTable(parts = 2, dots = 0), nestingOf(tables[0].getOrFail()))
    }

    @Test
    fun `containers stored nowhere stand at the top`() {
        assertEquals(Nesting.InTable(parts = 0, dots = 0), nestingOf(StandardTable()))
        assertEquals(Nesting.InTables(parts = 1), nestingOf(ArrayOfTables()))
        assertEquals(Nesting.InLine(level = 1), nestingOf(TomlArray()))
        assertEquals(Nesting.InLine(level = 1), nestingOf(InlineTable()))
    }

    @Test
    fun `a wrapper made from a stored table leaves the table where it stands`() {
        val doc = parse("[a.b]\nx = 1\n")
        val table = doc.item("a", "b").standardTableOrNull!!
        Item(table)
        assertEquals(Nesting.InTable(parts = 2, dots = 0), nestingOf(table))
    }

    @Test
    fun `a removed table stands at the top`() {
        val doc = parse("[a.b]\nx = 1\n")
        val table = doc.item("a", "b").standardTableOrNull!!
        doc.item("a").standardTableOrNull!!.remove("b")
        assertEquals(Nesting.InTable(parts = 0, dots = 0), nestingOf(table))
    }

    @Test
    fun `a replaced table stands at the top and its replacement where it stood`() {
        val doc = parse("[a.b]\nx = 1\n")
        val old = doc.item("a", "b").standardTableOrNull!!
        doc.item("a")["b"] = StandardTable()
        assertEquals(Nesting.InTable(parts = 0, dots = 0), nestingOf(old))
        assertEquals(Nesting.InTable(parts = 2, dots = 0), nestingOf(doc.item("a", "b").standardTableOrNull!!))
    }

    @Test
    fun `a removed array element stands at the top`() {
        val doc = parse("a = [[1], [2]]\n")
        val array = doc.item("a").arrayOrNull!!
        val first = array[0].getOrFail().arrayOrNull!!
        array.removeAt(0)
        assertEquals(Nesting.InLine(level = 1), nestingOf(first))
        assertEquals(Nesting.InLine(level = 2), nestingOf(array[0].getOrFail().arrayOrNull!!))
    }

    @Test
    fun `a table made a value stands as an inline table on its line`() {
        val doc = parse("[a.b]\nx = 1\n")
        val item = doc.item("a", "b")
        item.makeValue().getOrFail()
        assertEquals(Nesting.InLine(level = 1), nestingOf(item.inlineTableOrNull!!))
    }

    @Test
    fun `the tables of a copied document stand where the originals do`() {
        val doc = parse("[a]\n[a.b]\nx = 1\n")
        val copy = doc.copy()
        assertEquals(Nesting.InTable(parts = 2, dots = 0), nestingOf(copy.item("a", "b").standardTableOrNull!!))
    }
}
