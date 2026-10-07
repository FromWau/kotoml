package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replacing a key keeps the lines above it and detaches the slot handed out for it, a slot holds what the place it
 * is written in takes, an inline table stores copies, and an array sort keeps the text before `]` in place.
 */
class SlotFixesTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

    @Test
    fun `a table replacing a table keeps the comment above its header`() {
        val doc = parse("a = 1\n\n# about t\n[t]\nx = 1\n")
        doc["t"] = standardTableOf("y" to Item(Value.Integer(2L))).assertSuccess()
        assertEquals("a = 1\n\n# about t\n[t]\ny = 2\n", doc.toString())
    }

    @Test
    fun `a value replacing a table moves the comment above its header above the key`() {
        val doc = parse("a = 1\n\n# about t\n[t]\nx = 1\n")
        doc["t"] = Value.Integer(2L)
        assertEquals("a = 1\n\n# about t\nt = 2\n", doc.toString())
    }

    @Test
    fun `a value assigned to a table's slot moves the comment above its header above the key`() {
        val doc = parse("a = 1\n\n# about t\n[t]\nx = 1\n")
        doc["t"].assertSuccess().assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals("a = 1\n\n# about t\nt = 2\n", doc.toString())
    }

    @Test
    fun `a table made a value keeps the comment above its header`() {
        val doc = parse("a = 1\n\n# about t\n[t]\nx = 1\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("a = 1\n\n# about t\nt = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a value replacing a table leaves the blank line above its header behind`() {
        val doc = parse("a = 1\n\n[t]\nx = 1\n")
        doc["t"] = Value.Integer(2L)
        assertEquals("a = 1\nt = 2\n", doc.toString())
    }

    @Test
    fun `a table made a value prints the key with its default spacing`() {
        val doc = parse("[t]\nx = 1\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a table taken before it was made a value keeps its content and no longer writes into the document`() {
        val doc = parse("[t]\nx = 1\n")
        val taken = doc["t"].assertSuccess().standardTableOrNull!!
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        taken["y"] = 2L
        assertEquals(1L, taken.getOrNull("x")?.longOrNull)
        assertEquals("t = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a dotted table stored as an array of tables element is no longer dotted`() {
        val doc = parse("[[a]]\nx = 1\n")
        val dotted = parse("t.y = 2\n")["t"].assertSuccess()
        doc["a"].assertSuccess().set(0, dotted).assertSuccess()
        assertEquals(TableLayout.Header, doc["a"][0].assertSuccess().layout)
        assertEquals("[[a]]\ny = 2\n", doc.toString())
    }

    @Test
    fun `an inline table stores a copy of a value taken from another of its keys`() {
        val doc = parse("t = { a = [1] }\n")
        val t = doc["t"].assertSuccess().inlineTableOrNull!!
        t["b"] = t["a"].assertSuccess()
        t["a"].assertSuccess().arrayOrNull!!.add(2L)
        assertEquals("t = { a = [1, 2] , b = [1] }\n", doc.toString())
    }

    @Test
    fun `an inline table stored in itself holds a copy and prints`() {
        val t = InlineTable()
        t["a"] = 1L
        t["self"] = Value.InlineTable(t)
        assertEquals("{ a = 1, self = { a = 1 } }", t.toString())
    }

    @Test
    fun `an inline table built from pairs stores copies of the values`() {
        val value = Value.Array(tomlArrayOf(Value.Integer(1L)).assertSuccess())
        val t = inlineTableOf("a" to value).assertSuccess()
        value.arrayOrNull!!.add(2L)
        assertEquals(1, t["a"].assertSuccess().arrayOrNull!!.size)
    }

    @Test
    fun `getOrPut on an inline table stores a copy and returns the stored value`() {
        val t = InlineTable()
        val made = Value.Array(TomlArray())
        val stored = t.getOrPut("a") { made }.assertSuccess()
        stored.arrayOrNull!!.add(1L)
        made.arrayOrNull!!.add(2L)
        assertEquals("{ a = [1] }", t.toString())
    }

    @Test
    fun `a slot handed out for a key keeps the old value and leaves the key after put replaces it`() {
        val doc = parse("'k'   =   1\n")
        val slot = doc["k"].assertSuccess()
        doc["k"] = 2L
        assertEquals(1L, slot.longOrNull)
        slot.assign(Item(Value.Integer(3L))).assertSuccess()
        assertEquals("'k'   =   2\n", doc.toString())
    }

    @Test
    fun `writing past the end of an array through its index reports the index and the length`() {
        val doc = parse("a = [1]\n")
        val array = doc["a"].assertSuccess()
        val error = array.set(5, Item(Value.Integer(2L))).assertError<TomlEditError.IndexOutOfBounds>()
        assertEquals(TomlEditError.IndexOutOfBounds(5, 1), error)
    }

    @Test
    fun `writing through an index of a scalar reports that it is not an array`() {
        val doc = parse("a = 1\n")
        val error = doc["a"].assertSuccess().set(0, Item(Value.Integer(2L))).assertError<TomlEditError.NotAnArray>()
        assertEquals(TomlEditError.NotAnArray(0, TomlType.Integer), error)
    }

    @Test
    fun `a multi-line array without a trailing comma keeps its closing line break in place when sorted`() {
        val doc = parse("a = [\n    3,\n    1\n]\n")
        doc["a"].assertSuccess().arrayOrNull!!.sortBy { it.longOrNull }
        assertEquals("a = [\n    1,\n    3\n]\n", doc.toString())
    }

    @Test
    fun `a sorted multi-line array moves the comment after its last value behind that value's comma`() {
        val doc = parse("a = [\n    3,\n    1 # one\n]\n")
        doc["a"].assertSuccess().arrayOrNull!!.sortBy { it.longOrNull }
        assertEquals("a = [\n    1, # one\n    3\n]\n", doc.toString())
    }
}
