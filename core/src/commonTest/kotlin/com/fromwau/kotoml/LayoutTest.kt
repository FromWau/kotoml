package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [TableLayout], [Table.layout], [Item.layout] and [Item.setLayout]: every change between the four layouts, with and
 * without comments, how far a change reaches into the tables inside, and the slots that refuse one.
 */
class LayoutTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    // The texts here hold no `#` but in comments.
    private fun commentCount(text: String): Int = text.count { it == '#' }

    private val layouts = listOf(
        TableLayout.Header,
        TableLayout.Dotted,
        TableLayout.Inline,
        TableLayout.MultilineInline,
    )

    private val plain = mapOf(
        TableLayout.Header to "x = 1\n\n[t]\na = 1\nb = 2\n",
        TableLayout.Dotted to "x = 1\nt.a = 1\nt.b = 2\n",
        TableLayout.Inline to "x = 1\nt = { a = 1, b = 2 }\n",
        TableLayout.MultilineInline to "x = 1\nt = {\n  a = 1,\n  b = 2,\n}\n",
    )

    private val commented = mapOf(
        TableLayout.Header to "x = 1\n\n# above t\n[t] # header\n# inside\na = 1 # a\nb = 2\n",
        TableLayout.Dotted to "x = 1\n# above t\nt.a = 1 # a\n# inside\nt.b = 2 # b\n",
        TableLayout.Inline to "x = 1\n# above t\nt = { a = 1, b = 2 } # after\n",
        TableLayout.MultilineInline to
            "x = 1\n# above t\nt = { # open\n  # inside\n  a = 1, # a\n  b = 2,\n} # after\n",
    )

    private class Change(val text: String, val lost: Int = 0)

    // What each change of a commented table prints, and how many comments it drops, which takes force.
    private val commentedChanges = mapOf(
        (TableLayout.Header to TableLayout.Dotted) to Change("x = 1\n\n# above t\nt.a = 1 # a\nt.b = 2\n", lost = 2),
        (TableLayout.Header to TableLayout.Inline) to
            Change("x = 1\n\n# above t\nt = { a = 1, b = 2 } # header\n", lost = 2),
        (TableLayout.Header to TableLayout.MultilineInline) to
            Change("x = 1\n\n# above t\nt = { # header\n  # inside\n  a = 1, # a\n  b = 2,\n}\n"),
        (TableLayout.Dotted to TableLayout.Header) to
            Change("x = 1\n\n# above t\n[t]\na = 1 # a\nb = 2 # b\n", lost = 1),
        (TableLayout.Dotted to TableLayout.Inline) to
            Change("x = 1\n# above t\nt = { a = 1, b = 2 }\n# inside\n", lost = 2),
        (TableLayout.Dotted to TableLayout.MultilineInline) to
            Change("x = 1\n# above t\nt = {\n  a = 1, # a\n  # inside\n  b = 2, # b\n}\n"),
        (TableLayout.Inline to TableLayout.Header) to Change("x = 1\n\n# above t\n[t] # after\na = 1\nb = 2\n"),
        (TableLayout.Inline to TableLayout.Dotted) to Change("x = 1\n# above t\nt.a = 1\nt.b = 2\n", lost = 1),
        (TableLayout.Inline to TableLayout.MultilineInline) to
            Change("x = 1\n# above t\nt = {\n  a = 1,\n  b = 2,\n} # after\n"),
        (TableLayout.MultilineInline to TableLayout.Header) to
            Change("x = 1\n\n# above t\n[t] # open\n# inside\na = 1 # a\nb = 2\n# after\n"),
        (TableLayout.MultilineInline to TableLayout.Dotted) to
            Change("x = 1\n# above t\nt.a = 1 # a\nt.b = 2\n", lost = 3),
        (TableLayout.MultilineInline to TableLayout.Inline) to
            Change("x = 1\n# above t\nt = { a = 1, b = 2 } # after\n", lost = 3),
    )

    @Test
    fun `each layout reads as what it is written as`() {
        for (layout in layouts) {
            for (text in listOf(plain.getValue(layout), commented.getValue(layout))) {
                val doc = parse(text)
                assertEquals(layout, doc["t"].getOrFail().layout, text)
                assertEquals(layout, doc["t"].getOrFail().tableOrNull!!.layout, text)
            }
        }
    }

    @Test
    fun `a table without comments takes every layout without force`() {
        for (from in layouts) {
            for (to in layouts) {
                val doc = parse(plain.getValue(from))
                doc["t"].getOrFail().setLayout(to).getOrFail()
                assertEquals(plain.getValue(to), doc.toString(), "$from to $to")
                assertEquals(to, doc["t"].getOrFail().layout, "$from to $to")
            }
        }
    }

    @Test
    fun `a table with comments takes each layout keeping every comment or is refused until forced`() {
        for (from in layouts) {
            for (to in layouts) {
                val text = commented.getValue(from)
                val doc = parse(text)
                if (from == to) {
                    doc["t"].getOrFail().setLayout(to).getOrFail()
                    assertEquals(text, doc.toString())
                    continue
                }
                val change = commentedChanges.getValue(from to to)
                if (change.lost > 0) {
                    val error = doc["t"].getOrFail().setLayout(to).errorOrFail()
                    assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), change.lost), error, "$from to $to")
                    assertEquals(text, doc.toString(), "$from to $to")
                    doc["t"].getOrFail().setLayout(to, force = true).getOrFail()
                } else {
                    doc["t"].getOrFail().setLayout(to).getOrFail()
                }
                assertEquals(change.text, doc.toString(), "$from to $to")
                assertEquals(commentCount(text) - change.lost, commentCount(doc.toString()), "$from to $to")
                assertEquals(to, doc["t"].getOrFail().layout, "$from to $to")
            }
        }
    }

    @Test
    fun `a table made inline on one line keeps its header comment after the braces`() {
        val doc = parse("[t] # t header\na = 1\n")
        doc["t"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("t = { a = 1 } # t header\n", doc.toString())
    }

    @Test
    fun `a table under a header made inline across lines takes the tables inside across lines`() {
        val doc = parse("[t]\na = 1\n\n[t.sub]\nx = 1\n\n[[t.list]]\ny = 2\n")
        doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        val expected = "t = {\n  a = 1,\n  sub = {\n    x = 1,\n  },\n" +
            "  list = [\n    {\n      y = 2,\n    },\n  ],\n}\n"
        assertEquals(expected, doc.toString())
        assertEquals(TableLayout.MultilineInline, doc["t"]["sub"].getOrFail().layout)
        assertEquals(TableLayout.MultilineInline, doc["t"]["list"].getOrFail().layout)
    }

    @Test
    fun `a table under a header made inline on one line takes the tables inside on its line`() {
        val doc = parse("[t]\na = 1\nb.c = 2\n\n[t.sub] # sub\nx = 1\n\n[[t.list]]\ny = 2\n")
        doc["t"].getOrFail().setLayout(TableLayout.Inline, force = true).getOrFail()
        assertEquals("t = { a = 1, b = { c = 2 }, sub = { x = 1 }, list = [{ y = 2 }] }\n", doc.toString())
    }

    @Test
    fun `an inline table made multi-line takes the inline tables inside across lines`() {
        val doc = parse("t = { a = 1, u = { x = 1 }, l = [{ y = 2 }] }\n")
        doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n  },\n  l = [{\n    y = 2,\n  }],\n}\n", doc.toString())
    }

    @Test
    fun `an inline table across lines keeps those inside it already across lines as they are`() {
        val text = "t = { a = 1, u = {\n    x = 1, # x\n  } }\n"
        val doc = parse(text)
        doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1, # x\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table put on one line takes the inline tables inside on its line`() {
        val doc = parse("t = {\n  a = 1,\n  u = {\n    x = 1, # x\n  },\n}\n")
        val error = doc["t"].getOrFail().setLayout(TableLayout.Inline).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc["t"].getOrFail().setLayout(TableLayout.Inline, force = true).getOrFail()
        assertEquals("t = { a = 1, u = { x = 1 } }\n", doc.toString())
    }

    @Test
    fun `dotted keys made a header take the inline tables under them under headers`() {
        val doc = parse("x = 1\nt.a = 1\nt.u = { b = 2 }\n")
        doc["t"].getOrFail().setLayout(TableLayout.Header).getOrFail()
        assertEquals("x = 1\n\n[t]\na = 1\n\n[t.u]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a table made dotted keeps the tables under it under their headers`() {
        val doc = parse("[t]\na = 1\n\n[t.u]\nb = 2\n")
        doc["t"].getOrFail().setLayout(TableLayout.Dotted).getOrFail()
        assertEquals("t.a = 1\n\n[t.u]\nb = 2\n", doc.toString())
        assertEquals(TableLayout.Header, doc["t"]["u"].getOrFail().layout)
    }

    @Test
    fun `a dotted table holding lines kept from removed keys goes across lines keeping every comment once`() {
        val doc = parse("# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n")
        val a = doc["a"].getOrFail()
        a.standardTableOrNull!!.remove("y")
        a.setLayout(TableLayout.Header, force = true).getOrFail()
        a.standardTableOrNull!!.setImplicit(true)
        a.setLayout(TableLayout.Dotted, force = true).getOrFail()
        doc.remove("b")
        val before = doc.toString()
        doc["a"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("# about a.x\na = {\n  x = 1, # x\n  # about b\n  # about a.y\n}\n", doc.toString())
        assertEquals(commentCount(before), commentCount(doc.toString()))
    }

    @Test
    fun `a dotted table holding lines kept from removed keys put on one line keeps them below it`() {
        val doc = parse("# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n")
        val a = doc["a"].getOrFail()
        a.standardTableOrNull!!.remove("y")
        a.setLayout(TableLayout.Header, force = true).getOrFail()
        a.standardTableOrNull!!.setImplicit(true)
        a.setLayout(TableLayout.Dotted, force = true).getOrFail()
        doc.remove("b")
        val error = doc["a"].getOrFail().setLayout(TableLayout.Inline).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), error)
        doc["a"].getOrFail().setLayout(TableLayout.Inline, force = true).getOrFail()
        assertEquals("# about a.x\na = { x = 1 }\n# about b\n# about a.y\n", doc.toString())
    }

    @Test
    fun `a table printing no header made inline keeps the lines above the first header under it above its key`() {
        for (layout in listOf(TableLayout.Inline, TableLayout.MultilineInline)) {
            val doc = parse("x = 1\n\n# about sub\n[t.sub]\nb = 1\n")
            doc["t"].getOrFail().setLayout(layout).getOrFail()
            val inline = when (layout) {
                TableLayout.Inline -> "{ sub = { b = 1 } }"
                else -> "{\n  sub = {\n    b = 1,\n  },\n}"
            }
            assertEquals("x = 1\n\n# about sub\nt = $inline\n", doc.toString(), layout.toString())
        }
    }

    @Test
    fun `a table made inline across lines in a TOML 1_0 document is refused`() {
        val doc = parse("[t] # t\na = 1 # a\n", TomlVersion.V1_0)
        val error = doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
        assertEquals("[t] # t\na = 1 # a\n", doc.toString())
    }

    @Test
    fun `a table made inline on one line in a TOML 1_0 document is written`() {
        val doc = parse("[t] # t\na = 1\n", TomlVersion.V1_0)
        doc["t"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("t = { a = 1 } # t\n", doc.toString())
        doc.writeTo(Buffer()).getOrFail()
    }

    @Test
    fun `the tables stay the same objects within their kind`() {
        val doc = parse("[t]\na = 1\nu = { b = 2 }\n")
        val table = doc["t"].getOrFail().standardTableOrNull!!
        doc["t"].getOrFail().setLayout(TableLayout.Dotted).getOrFail()
        assertSame(table, doc["t"].getOrFail().standardTableOrNull)
        assertEquals(TableLayout.Dotted, table.layout)
        val inline = doc["t"]["u"].getOrFail().inlineTableOrNull!!
        doc["t"]["u"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertSame(inline, doc["t"]["u"].getOrFail().inlineTableOrNull)
        assertEquals(TableLayout.MultilineInline, inline.layout)
        inline["c"] = 3L
        assertEquals("t.a = 1\nt.u = {\n  b = 2,\n  c = 3,\n}\n", doc.toString())
    }

    @Test
    fun `an array of tables reads as headers and takes the inline layouts as a whole`() {
        val doc = parse("[[s]]\nn = 1\n\n[[s]]\nn = 2\n")
        assertEquals(TableLayout.Header, doc["s"].getOrFail().layout)
        doc["s"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("s = [{ n = 1 }, { n = 2 }]\n", doc.toString())
        assertEquals(TableLayout.Inline, doc["s"].getOrFail().layout)
        doc["s"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("s = [{\n  n = 1,\n}, {\n  n = 2,\n}]\n", doc.toString())
        assertEquals(TableLayout.MultilineInline, doc["s"].getOrFail().layout)
        doc["s"].getOrFail().setLayout(TableLayout.Header).getOrFail()
        assertEquals("[[s]]\nn = 1\n\n[[s]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `an array of tables made inline across lines puts each table across lines`() {
        val doc = parse("# first\n[[s]] # one\nn = 1 # n\n\n[[s]]\nn = 2\n")
        doc["s"].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        val expected = "# first\ns = [\n  { # one\n    n = 1, # n\n  },\n  {\n    n = 2,\n  },\n]\n"
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `an array of inline tables laid out otherwise reads inline and takes a layout table by table`() {
        val doc = parse("s = [{ n = 1 }, {\n  n = 2,\n}]\n")
        assertEquals(TableLayout.Inline, doc["s"].getOrFail().layout)
        doc["s"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("s = [{ n = 1 }, { n = 2 }]\n", doc.toString())
    }

    @Test
    fun `a slot holding no table reads no layout and is NotExpandable`() {
        val text = "a = 1\nb = []\nc = [1, { x = 1 }]\nd = [{ x = 1 }]\n"
        val doc = parse(text)
        for (key in listOf("a", "b", "c")) {
            assertNull(doc[key].getOrFail().layout, key)
            for (layout in layouts) {
                val error = doc[key].getOrFail().setLayout(layout).errorOrFail()
                assertEquals(TomlEditError.NotExpandable(doc[key].getOrFail().type), error, key)
            }
        }
        val dotted = doc["d"].getOrFail().setLayout(TableLayout.Dotted).errorOrFail()
        assertEquals(TomlEditError.NotExpandable(TomlType.Array), dotted)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table in an array of tables takes no inline or dotted layout`() {
        val text = "[[a]]\nx = 1\n"
        val doc = parse(text)
        val element = doc["a"][0].getOrFail()
        assertEquals(TableLayout.Header, element.layout)
        element.setLayout(TableLayout.Header).getOrFail()
        val inline = TomlEditError.NotATableElement(TomlType.InlineTable)
        assertEquals(inline, element.setLayout(TableLayout.Inline).errorOrFail())
        assertEquals(inline, element.setLayout(TableLayout.MultilineInline).errorOrFail())
        assertEquals(TomlEditError.NotExpandable(TomlType.Table), element.setLayout(TableLayout.Dotted).errorOrFail())
        val array = doc["a"].getOrFail().setLayout(TableLayout.Dotted).errorOrFail()
        assertEquals(TomlEditError.NotExpandable(TomlType.ArrayOfTables), array)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table in an array takes no dotted layout`() {
        val text = "b = [{ x = 1 }]\n"
        val doc = parse(text)
        val dotted = doc["b"][0].getOrFail().setLayout(TableLayout.Dotted).errorOrFail()
        assertEquals(TomlEditError.NotExpandable(TomlType.InlineTable), dotted)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table in an inline table takes the dotted layout and back`() {
        val doc = parse("t = { u = { x = 1, y = 2 } }\n")
        doc["t"]["u"].getOrFail().setLayout(TableLayout.Dotted).getOrFail()
        assertEquals("t = { u.x = 1, u.y = 2 }\n", doc.toString())
        assertEquals(TableLayout.Dotted, doc["t"]["u"].getOrFail().layout)
        doc["t"]["u"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("t = { u = { x = 1, y = 2 } }\n", doc.toString())
    }

    @Test
    fun `an inline table in an array takes the inline layouts`() {
        val doc = parse("a = [\n  { x = 1, y = 2 },\n]\n")
        doc["a"][0].getOrFail().setLayout(TableLayout.MultilineInline).getOrFail()
        assertEquals("a = [\n  {\n    x = 1,\n    y = 2,\n  },\n]\n", doc.toString())
        doc["a"][0].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("a = [\n  { x = 1, y = 2 },\n]\n", doc.toString())
    }

    @Test
    fun `a table stored nowhere takes a layout`() {
        val table = StandardTable()
        table["a"] = 1L
        val item = Item(table)
        item.setLayout(TableLayout.Dotted).getOrFail()
        assertEquals(TableLayout.Dotted, table.layout)
        item.setLayout(TableLayout.Inline).getOrFail()
        assertEquals("{ a = 1 }", item.toString())
    }
}
