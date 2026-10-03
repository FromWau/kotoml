package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sorting the key/value lines of a table or an inline table by a selector or a comparator. */
class TableSortTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).unwrap()

    @Test
    fun `a table sorts by a selector over its values`() {
        val doc = parse("a = 3\nb = 1\nc = 2\n")
        doc.root.sortBy { (_, item) -> item.longOrNull }
        assertEquals("b = 1\nc = 2\na = 3\n", doc.toString())
    }

    @Test
    fun `pairs with equal selector values keep their relative order`() {
        val doc = parse("d = 2\nc = 1\nb = 2\na = 1\n")
        doc.root.sortBy { (_, item) -> item.longOrNull }
        assertEquals("c = 1\na = 1\nd = 2\nb = 2\n", doc.toString())
    }

    @Test
    fun `a selector returning null sorts that pair first`() {
        val doc = parse("a = 1\nb = \"text\"\nc = 0\n")
        doc.root.sortBy { (_, item) -> item.longOrNull }
        assertEquals("b = \"text\"\nc = 0\na = 1\n", doc.toString())
    }

    @Test
    fun `a table sorts with a comparator`() {
        val doc = parse("a = 1\nc = 3\nb = 2\n")
        doc.root.sortWith { (left, _), (right, _) -> right.compareTo(left) }
        assertEquals("c = 3\nb = 2\na = 1\n", doc.toString())
    }

    @Test
    fun `sorting a table leaves its sub-tables and arrays of tables in their order`() {
        val input = """
            b = 2
            a = 1

            [z]
            y = 1

            [[x]]
            w = 1

            [m]
            n = 1
        """.trimIndent() + "\n"
        val doc = parse(input)
        doc.root.sortBy { (key, _) -> key }
        val expected = """
            a = 1
            b = 2

            [z]
            y = 1

            [[x]]
            w = 1

            [m]
            n = 1
        """.trimIndent() + "\n"
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `an inline table sorts by a selector over its keys`() {
        val table = parse("t = { c = 3, a = 1, b = 2 }\n")["t"].inlineTableOrNull!!
        table.sortBy { (key, _) -> key }
        assertEquals(listOf("a", "b", "c"), table.map { (key, _) -> key })
    }

    @Test
    fun `an inline table sorts stably by a selector over its values`() {
        val table = parse("t = { d = 2, c = 1, b = 2, a = 1 }\n")["t"].inlineTableOrNull!!
        table.sortBy { (_, value) -> value.longOrNull }
        assertEquals(listOf("c", "a", "d", "b"), table.map { (key, _) -> key })
    }

    @Test
    fun `an inline table sorts with a comparator`() {
        val table = parse("t = { a = 1, c = 3, b = 2 }\n")["t"].inlineTableOrNull!!
        table.sortWith { (left, _), (right, _) -> right.compareTo(left) }
        assertEquals(listOf("c", "b", "a"), table.map { (key, _) -> key })
    }
}
