package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reordering, sorting and layout changes move each item with its comments; free comments and blank lines stay. */
class MoveWithCommentsTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text, TomlVersion.V1_1).assertSuccess()

    private fun TomlDocument.reverse(key: String) {
        this[key] = this[key].assertSuccess().arrayOfTablesOrNull!!.reversed().toArrayOfTables().assertSuccess()
    }

    private fun TomlDocument.sortArray(key: String) {
        this[key].assertSuccess().arrayOrNull!!.sortBy { it.longOrNull }
    }

    private fun TomlDocument.sortInline(key: String) {
        this[key].assertSuccess().inlineTableOrNull!!.sort()
    }

    @Test
    fun `reversed tables move with the comment lines above their headers`() {
        val doc = parse("# first\n[[a]] # h1\nn = 1\n\n# second\n[[a]] # h2\nn = 2\n")
        doc.reverse("a")
        assertEquals("# second\n[[a]] # h2\nn = 2\n\n# first\n[[a]] # h1\nn = 1\n", doc.toString())
    }

    @Test
    fun `a free comment above reordered tables stays at its place`() {
        val doc = parse("# free\n\n# first\n[[a]]\nn = 1\n\n# second\n[[a]]\nn = 2\n")
        doc.reverse("a")
        assertEquals("# free\n\n# second\n[[a]]\nn = 2\n\n# first\n[[a]]\nn = 1\n", doc.toString())
    }

    @Test
    fun `sorting moves each key with its comments while a free comment and the blank lines stay`() {
        val doc = parse("b = 1\n\n# free\n\n# about a\na = 2 # two\nc = 3\n")
        doc.root.sort()
        assertEquals("# about a\na = 2 # two\n\n# free\n\nb = 1\nc = 3\n", doc.toString())
    }

    @Test
    fun `sorting leaves a free comment above a header at its place`() {
        val doc = parse("[z]\nq = 1\n\n# free\n\n# about a\n[a]\nw = 1\n")
        doc.root.sort()
        assertEquals("# about a\n[a]\nw = 1\n\n# free\n\n[z]\nq = 1\n", doc.toString())
    }

    @Test
    fun `sorting leaves a free comment above dotted keys at its place`() {
        val doc = parse("b = 1\n\n# free\n\na.c = 2\na.b = 1\n")
        doc.root.sort()
        assertEquals("a.b = 1\na.c = 2\n\n# free\n\nb = 1\n", doc.toString())
    }

    @Test
    fun `sorting leaves the lines kept from a removed key at their place`() {
        val doc = parse("c = 1\n# kept\n\nb = 2\na = 3\n")
        doc.remove("b", force = true).assertSuccess()
        doc.root.sort()
        assertEquals("a = 3\n# kept\n\nc = 1\n", doc.toString())
    }

    @Test
    fun `sorting keeps the comment above a dotted group's first line above its new first line`() {
        val doc = parse("[t]\nz = 3 # z\n# about b\nb.d = 1 # d\nb.c = 2 # c\n")
        doc["t"].assertSuccess().standardTableOrNull!!.sortWith(compareBy { it.first })
        assertEquals("[t]\n# about b\nb.c = 2 # c\nb.d = 1 # d\nz = 3 # z\n", doc.toString())
    }

    @Test
    fun `sorting moves the comment above a later line of a dotted group with that line`() {
        val doc = parse("[t]\n# about b\nb.e = 1\n# about d\nb.d = 2\nb.c = 3\n")
        doc["t"].assertSuccess().standardTableOrNull!!.sort()
        assertEquals("[t]\n# about b\nb.c = 3\n# about d\nb.d = 2\nb.e = 1\n", doc.toString())
    }

    @Test
    fun `sorting keeps the comments above the first lines of nested dotted groups above their new first lines`() {
        val doc = parse("[t]\nz = 3\n# about b\nb.y = 0\n# about x\nb.x.d = 1\nb.x.c = 2\n")
        doc["t"].assertSuccess().standardTableOrNull!!.sort()
        assertEquals("[t]\n# about b\n# about x\nb.x.c = 2\nb.x.d = 1\nb.y = 0\nz = 3\n", doc.toString())
    }

    @Test
    fun `sorting leaves a free comment above a dotted group at its place`() {
        val doc = parse("[t]\nz = 3\n\n# free\n\n# about b\nb.d = 1\nb.c = 2\n")
        doc["t"].assertSuccess().standardTableOrNull!!.sort()
        assertEquals("[t]\n# about b\nb.c = 2\nb.d = 1\n\n# free\n\nz = 3\n", doc.toString())
    }

    @Test
    fun `a sorted dotted table keeps the comment above its first line above its new first line`() {
        val doc = parse("# about t\nt.z = 1\nt.a = 2\n")
        doc["t"].assertSuccess().standardTableOrNull!!.sort()
        assertEquals("# about t\nt.a = 2\nt.z = 1\n", doc.toString())
    }

    @Test
    fun `a sorted inline table across lines keeps the comment above a dotted group's first line above it`() {
        val doc = parse("t = {\n  z = 3, # z\n  # about b\n  b.d = 1, # d\n  b.c = 2, # c\n}\n")
        doc.sortInline("t")
        assertEquals("t = {\n  # about b\n  b.c = 2, # c\n  b.d = 1, # d\n  z = 3, # z\n}\n", doc.toString())
    }

    @Test
    fun `a sorted inline table across lines keeps the comments above nested dotted groups above their first lines`() {
        val doc = parse("t = {\n  z = 3,\n  # about b\n  b.y = 0,\n  # about x\n  b.x.d = 1,\n  b.x.c = 2,\n}\n")
        doc.sortInline("t")
        assertEquals(
            "t = {\n  # about b\n  # about x\n  b.x.c = 2,\n  b.x.d = 1,\n  b.y = 0,\n  z = 3,\n}\n",
            doc.toString(),
        )
    }

    @Test
    fun `a sorted inline table across lines moves the comment above a later line of a dotted group with it`() {
        val doc = parse("t = {\n  z = 3,\n\n  # free\n\n  # about b\n  b.d = 1,\n  # about c\n  b.c = 2,\n}\n")
        doc.sortInline("t")
        assertEquals(
            "t = {\n  # about b\n  # about c\n  b.c = 2,\n\n  # free\n\n  b.d = 1,\n  z = 3,\n}\n",
            doc.toString(),
        )
    }

    @Test
    fun `a sorted array across lines moves each value with the comment after its comma`() {
        val doc = parse("a = [\n  3, # three\n  1, # one\n  2, # two\n]\n")
        doc.sortArray("a")
        assertEquals("a = [\n  1, # one\n  2, # two\n  3, # three\n]\n", doc.toString())
    }

    @Test
    fun `a sorted array across lines without a trailing comma moves the comment after its last value`() {
        val doc = parse("a = [\n  3, # three\n  1 # one\n]\n")
        doc.sortArray("a")
        assertEquals("a = [\n  1, # one\n  3 # three\n]\n", doc.toString())
    }

    @Test
    fun `a sorted array across lines leaves a free comment and the blank lines at their place`() {
        val doc = parse("a = [\n  3,\n\n  # free\n\n  # one\n  1,\n  2,\n]\n")
        doc.sortArray("a")
        assertEquals("a = [\n  # one\n  1,\n\n  # free\n\n  2,\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `a sorted array keeps the comment lines between a value and its comma with that value`() {
        val doc = parse("a = [\n  3 # on 3\n  # more\n  , # comma\n  1,\n]\n")
        doc.sortArray("a")
        assertEquals("a = [\n  1,\n  3 # on 3\n  # more\n  , # comma\n]\n", doc.toString())
    }

    @Test
    fun `a sorted array keeps the comment after its opening bracket in place`() {
        val doc = parse("a = [ # open\n  3,\n  1,\n]\n")
        doc.sortArray("a")
        assertEquals("a = [ # open\n  1,\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `a sorted CRLF array moves each value with its comments`() {
        val doc = parse("a = [\r\n  3, # three\r\n  # one\r\n  1,\r\n]\r\n")
        doc.sortArray("a")
        assertEquals("a = [\r\n  # one\r\n  1,\r\n  3, # three\r\n]\r\n", doc.toString())
    }

    @Test
    fun `a sorted inline table across lines moves each entry with the comment after its comma`() {
        val doc = parse("t = {\n  c = 3, # three\n  a = 1, # one\n  b = 2, # two\n}\n")
        doc.sortInline("t")
        assertEquals("t = {\n  a = 1, # one\n  b = 2, # two\n  c = 3, # three\n}\n", doc.toString())
    }

    @Test
    fun `a sorted inline table across lines leaves a free comment at its place`() {
        val doc = parse("t = {\n  c = 3,\n\n  # free\n\n  # about a\n  a = 1,\n}\n")
        doc.sortInline("t")
        assertEquals("t = {\n  # about a\n  a = 1,\n\n  # free\n\n  c = 3,\n}\n", doc.toString())
    }

    @Test
    fun `braces made a header keep the blank lines between entries`() {
        val doc = parse("t = {\n  a = 1,\n\n  # about b\n  b = 2, # two\n\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t]\na = 1\n\n# about b\nb = 2 # two\n", doc.toString())
    }

    @Test
    fun `braces made dotted keys keep the blank lines between entries`() {
        val doc = parse("t = {\n  a = 1,\n\n  b = 2,\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals("t.a = 1\n\nt.b = 2\n", doc.toString())
    }

    @Test
    fun `a header made inline across lines keeps the blank lines between its lines`() {
        val doc = parse("[t]\na = 1\n\n# about b\nb = 2\n\nc = 3\n")
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n\n  # about b\n  b = 2,\n\n  c = 3,\n}\n", doc.toString())
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t]\na = 1\n\n# about b\nb = 2\n\nc = 3\n", doc.toString())
    }

    @Test
    fun `dotted keys made inline across lines keep the blank lines between their lines`() {
        val doc = parse("t.a = 1\n\nt.b = 2\n")
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n\n  b = 2,\n}\n", doc.toString())
    }

    @Test
    fun `a free comment inside a copied dotted table stays below its line when the table is put on one line`() {
        val doc = parse("[s]\nb.c = 1\n# about d\n\nb.d = 2\n[t]\nx = 0\n")
        doc["s"]["b"].assertSuccess().standardTableOrNull!!.remove("d", force = true).assertSuccess()
        doc["t"]["b"] = doc["s"]["b"].assertSuccess()
        doc["t"]["y"] = 5L
        doc["t"]["b"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("[s]\nb.c = 1\n# about d\n\n[t]\nx = 0\nb = { c = 1 }\n# about d\n\ny = 5\n", doc.toString())
    }

    @Test
    fun `free lines between the lines of a copied dotted table stay below it on one line`() {
        val doc = parse("[s]\nb.c = 1\n\n# free\n\nb.d = 2\n[t]\nx = 0\n")
        doc["t"]["b"] = doc["s"]["b"].assertSuccess()
        doc["t"]["b"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("[t]\nx = 0\nb = { c = 1, d = 2 }\n\n# free\n", doc.toString().substringAfter("b.d = 2\n"))
    }

    @Test
    fun `dotted keys put on one line leave no blank line at the end of the file below a free comment`() {
        val doc = parse("[t]\nb.c = 1\n\n# free\n\nb.d = 2\n")
        doc["t"]["b"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("[t]\nb = { c = 1, d = 2 }\n\n# free\n", doc.toString())
    }

    @Test
    fun `dotted keys at the top put on one line leave no blank line at the end of the file`() {
        val doc = parse("b.c = 1\n\n# free\n\nb.d = 2\n")
        doc["b"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("b = { c = 1, d = 2 }\n\n# free\n", doc.toString())
    }

    @Test
    fun `dotted keys put on one line keep the blank line between a free comment and the line after`() {
        val doc = parse("[t]\nb.c = 1\n\n# free\n\nb.d = 2\nx = 1\n")
        doc["t"]["b"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("[t]\nb = { c = 1, d = 2 }\n\n# free\n\nx = 1\n", doc.toString())
    }
}
