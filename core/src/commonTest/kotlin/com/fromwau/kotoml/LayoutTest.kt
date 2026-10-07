package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.io.Buffer

/**
 * [TableLayout], [Table.layout], [Item.layout] and [Item.setLayout]: every change between the four layouts, with and
 * without comments, how far a change reaches into the tables inside, and the slots that refuse one.
 */
class LayoutTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

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
                assertEquals(layout, doc["t"].assertSuccess().layout, text)
                assertEquals(layout, doc["t"].assertSuccess().tableOrNull!!.layout, text)
            }
        }
    }

    @Test
    fun `a table without comments takes every layout without force`() {
        for (from in layouts) {
            for (to in layouts) {
                val doc = parse(plain.getValue(from))
                doc["t"].assertSuccess().setLayout(to).assertSuccess()
                assertEquals(plain.getValue(to), doc.toString(), "$from to $to")
                assertEquals(to, doc["t"].assertSuccess().layout, "$from to $to")
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
                    doc["t"].assertSuccess().setLayout(to).assertSuccess()
                    assertEquals(text, doc.toString())
                    continue
                }
                val change = commentedChanges.getValue(from to to)
                if (change.lost > 0) {
                    val error = doc["t"].assertSuccess().setLayout(to).assertError<TomlEditError.CommentsWouldBeLost>()
                    assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), change.lost), error, "$from to $to")
                    assertEquals(text, doc.toString(), "$from to $to")
                    doc["t"].assertSuccess().setLayout(to, force = true).assertSuccess()
                } else {
                    doc["t"].assertSuccess().setLayout(to).assertSuccess()
                }
                assertEquals(change.text, doc.toString(), "$from to $to")
                assertEquals(commentCount(text) - change.lost, commentCount(doc.toString()), "$from to $to")
                assertEquals(to, doc["t"].assertSuccess().layout, "$from to $to")
            }
        }
    }

    @Test
    fun `a table made inline on one line keeps its header comment after the braces`() {
        val doc = parse("[t] # t header\na = 1\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { a = 1 } # t header\n", doc.toString())
    }

    @Test
    fun `a table under a header made inline across lines takes the tables inside across lines`() {
        val doc = parse("[t]\na = 1\n\n[t.sub]\nx = 1\n\n[[t.list]]\ny = 2\n")
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        val expected = "t = {\n  a = 1,\n  sub = {\n    x = 1,\n  },\n" +
            "  list = [\n    {\n      y = 2,\n    },\n  ],\n}\n"
        assertEquals(expected, doc.toString())
        assertEquals(TableLayout.MultilineInline, doc["t"]["sub"].assertSuccess().layout)
        assertEquals(TableLayout.MultilineInline, doc["t"]["list"].assertSuccess().layout)
    }

    @Test
    fun `a table under a header made inline on one line takes the tables inside on its line`() {
        val doc = parse("[t]\na = 1\nb.c = 2\n\n[t.sub] # sub\nx = 1\n\n[[t.list]]\ny = 2\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { a = 1, b = { c = 2 }, sub = { x = 1 }, list = [{ y = 2 }] }\n", doc.toString())
    }

    @Test
    fun `an inline table made multi-line takes the inline tables inside across lines`() {
        val doc = parse("t = { a = 1, u = { x = 1 }, l = [{ y = 2 }] }\n")
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1,\n  },\n  l = [{\n    y = 2,\n  }],\n}\n", doc.toString())
    }

    @Test
    fun `an inline table across lines keeps those inside it already across lines as they are`() {
        val text = "t = { a = 1, u = {\n    x = 1, # x\n  } }\n"
        val doc = parse(text)
        doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = {\n  a = 1,\n  u = {\n    x = 1, # x\n  },\n}\n", doc.toString())
    }

    @Test
    fun `an inline table put on one line takes the inline tables inside on its line`() {
        val doc = parse("t = {\n  a = 1,\n  u = {\n    x = 1, # x\n  },\n}\n")
        val error = doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { a = 1, u = { x = 1 } }\n", doc.toString())
    }

    @Test
    fun `an inline table across lines put on one line keeps its header comment after the braces`() {
        val text = "t = { # t header\n  a = 1, # one\n}\n"
        val doc = parse(text)
        val error = doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        assertEquals(text, doc.toString())
        doc["t"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { a = 1 } # t header\n", doc.toString())
    }

    @Test
    fun `an inline table across lines with only a header comment goes on one line without force`() {
        val doc = parse("t = { # t header\n  a = 1,\n}\nu = { # u header\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        doc["u"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { a = 1 } # t header\nu = {} # u header\n", doc.toString())
    }

    @Test
    fun `an inline table across lines put on one line keeps the comment after the braces over its header comment`() {
        val doc = parse("t = { # t header\n  a = 1,\n} # after\n")
        val error = doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { a = 1 } # after\n", doc.toString())
    }

    @Test
    fun `an inline table put on one line drops the header comments of the inline tables inside`() {
        val doc = parse("t = { # t header\n  u = { # u header\n    x = 1,\n  },\n}\n")
        val error = doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { u = { x = 1 } } # t header\n", doc.toString())
    }

    @Test
    fun `an inline table inside one across lines put on one line keeps its header comment after its comma`() {
        val doc = parse("p = {\n  t = { # t header\n    a = 1,\n  },\n  b = 2,\n}\n")
        doc["p"]["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("p = {\n  t = { a = 1 }, # t header\n  b = 2,\n}\n", doc.toString())
    }

    @Test
    fun `inline tables in an array across lines put on one line keep their header comments after their commas`() {
        val doc = parse("a = [\n  { # one\n    x = 1,\n  },\n  { # two\n    y = 2,\n  },\n]\n")
        doc["a"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("a = [\n  { x = 1 }, # one\n  { y = 2 }, # two\n]\n", doc.toString())
    }

    @Test
    fun `the last inline table in an array without a trailing comma keeps its header comment after its braces`() {
        val doc = parse("a = [\n  { # one\n    x = 1,\n  }\n]\n")
        doc["a"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("a = [\n  { x = 1 } # one\n]\n", doc.toString())
    }

    @Test
    fun `an inline table in an array put on one line keeps the comment after its comma over its header comment`() {
        val doc = parse("a = [\n  { # one\n    x = 1,\n  }, # after\n]\n")
        val error = doc["a"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), error)
        doc["a"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("a = [\n  { x = 1 }, # after\n]\n", doc.toString())
    }

    @Test
    fun `an inline table in an array on one line put on one line drops its header comment`() {
        val doc = parse("a = [{ # one\n  x = 1,\n}]\n")
        val error = doc["a"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), error)
        doc["a"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("a = [{ x = 1 }]\n", doc.toString())
    }

    @Test
    fun `dotted keys made a header take the inline tables under them under headers`() {
        val doc = parse("x = 1\nt.a = 1\nt.u = { b = 2 }\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("x = 1\n\n[t]\na = 1\n\n[t.u]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a table made dotted keeps the tables under it under their headers`() {
        val doc = parse("[t]\na = 1\n\n[t.u]\nb = 2\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals("t.a = 1\n\n[t.u]\nb = 2\n", doc.toString())
        assertEquals(TableLayout.Header, doc["t"]["u"].assertSuccess().layout)
    }

    @Test
    fun `a dotted table holding lines kept from removed keys goes across lines keeping every comment once`() {
        val doc = parse("# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n")
        val a = doc["a"].assertSuccess()
        a.standardTableOrNull!!.remove("y")
        a.setLayout(TableLayout.Header, force = true).assertSuccess()
        a.standardTableOrNull!!.setImplicit(true)
        a.setLayout(TableLayout.Dotted, force = true).assertSuccess()
        doc.remove("b")
        val before = doc.toString()
        doc["a"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("# about a.x\na = {\n  x = 1, # x\n  # about b\n  # about a.y\n}\n", doc.toString())
        assertEquals(commentCount(before), commentCount(doc.toString()))
    }

    @Test
    fun `a dotted table holding lines kept from removed keys put on one line keeps them below it`() {
        val doc = parse("# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n")
        val a = doc["a"].assertSuccess()
        a.standardTableOrNull!!.remove("y")
        a.setLayout(TableLayout.Header, force = true).assertSuccess()
        a.standardTableOrNull!!.setImplicit(true)
        a.setLayout(TableLayout.Dotted, force = true).assertSuccess()
        doc.remove("b")
        val error = doc["a"].assertSuccess().setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), error)
        doc["a"].assertSuccess().setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("# about a.x\na = { x = 1 }\n# about b\n# about a.y\n", doc.toString())
    }

    @Test
    fun `a table printing no header made inline keeps the lines above the first header under it above its key`() {
        for (layout in listOf(TableLayout.Inline, TableLayout.MultilineInline)) {
            val doc = parse("x = 1\n\n# about sub\n[t.sub]\nb = 1\n")
            doc["t"].assertSuccess().setLayout(layout).assertSuccess()
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
        val error = doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
        assertEquals("[t] # t\na = 1 # a\n", doc.toString())
    }

    @Test
    fun `a table made inline on one line in a TOML 1_0 document is written`() {
        val doc = parse("[t] # t\na = 1\n", TomlVersion.V1_0)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { a = 1 } # t\n", doc.toString())
        doc.writeTo(Buffer()).assertSuccess()
    }

    @Test
    fun `the tables stay the same objects within their kind`() {
        val doc = parse("[t]\na = 1\nu = { b = 2 }\n")
        val table = doc["t"].assertSuccess().standardTableOrNull!!
        doc["t"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        assertSame(table, doc["t"].assertSuccess().standardTableOrNull)
        assertEquals(TableLayout.Dotted, table.layout)
        val inline = doc["t"]["u"].assertSuccess().inlineTableOrNull!!
        doc["t"]["u"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertSame(inline, doc["t"]["u"].assertSuccess().inlineTableOrNull)
        assertEquals(TableLayout.MultilineInline, inline.layout)
        inline["c"] = 3L
        assertEquals("t.a = 1\nt.u = {\n  b = 2,\n  c = 3,\n}\n", doc.toString())
    }

    @Test
    fun `an array of tables reads as headers and takes the inline layouts as a whole`() {
        val doc = parse("[[s]]\nn = 1\n\n[[s]]\nn = 2\n")
        assertEquals(TableLayout.Header, doc["s"].assertSuccess().layout)
        doc["s"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("s = [{ n = 1 }, { n = 2 }]\n", doc.toString())
        assertEquals(TableLayout.Inline, doc["s"].assertSuccess().layout)
        doc["s"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("s = [{\n  n = 1,\n}, {\n  n = 2,\n}]\n", doc.toString())
        assertEquals(TableLayout.MultilineInline, doc["s"].assertSuccess().layout)
        doc["s"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[[s]]\nn = 1\n\n[[s]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `an array of tables made inline across lines puts each table across lines`() {
        val doc = parse("# first\n[[s]] # one\nn = 1 # n\n\n[[s]]\nn = 2\n")
        doc["s"].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        val expected = "# first\ns = [\n  { # one\n    n = 1, # n\n  },\n  {\n    n = 2,\n  },\n]\n"
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `an array of inline tables laid out otherwise reads inline and takes a layout table by table`() {
        val doc = parse("s = [{ n = 1 }, {\n  n = 2,\n}]\n")
        assertEquals(TableLayout.Inline, doc["s"].assertSuccess().layout)
        doc["s"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("s = [{ n = 1 }, { n = 2 }]\n", doc.toString())
    }

    @Test
    fun `a slot holding no table reads no layout and is NotExpandable`() {
        val text = "a = 1\nb = []\nc = [1, { x = 1 }]\nd = [{ x = 1 }]\n"
        val doc = parse(text)
        for (key in listOf("a", "b", "c")) {
            assertNull(doc[key].assertSuccess().layout, key)
            for (layout in layouts) {
                val error = doc[key].assertSuccess().setLayout(layout).assertError<TomlEditError.NotExpandable>()
                assertEquals(TomlEditError.NotExpandable(doc[key].assertSuccess().type), error, key)
            }
        }
        val dotted = doc["d"].assertSuccess().setLayout(TableLayout.Dotted).assertError<TomlEditError.NotExpandable>()
        assertEquals(TomlEditError.NotExpandable(TomlType.Array), dotted)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table in an array of tables takes no inline or dotted layout`() {
        val text = "[[a]]\nx = 1\n"
        val doc = parse(text)
        val element = doc["a"][0].assertSuccess()
        assertEquals(TableLayout.Header, element.layout)
        element.setLayout(TableLayout.Header).assertSuccess()
        val inline = TomlEditError.NotATableElement(TomlType.InlineTable)
        assertEquals(inline, element.setLayout(TableLayout.Inline).assertError<TomlEditError.NotATableElement>())
        assertEquals(inline, element.setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotATableElement>())
        assertEquals(TomlEditError.NotExpandable(TomlType.Table), element.setLayout(TableLayout.Dotted).assertError<TomlEditError.NotExpandable>())
        val array = doc["a"].assertSuccess().setLayout(TableLayout.Dotted).assertError<TomlEditError.NotExpandable>()
        assertEquals(TomlEditError.NotExpandable(TomlType.ArrayOfTables), array)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table in an array takes no dotted layout`() {
        val text = "b = [{ x = 1 }]\n"
        val doc = parse(text)
        val dotted = doc["b"][0].assertSuccess().setLayout(TableLayout.Dotted).assertError<TomlEditError.NotExpandable>()
        assertEquals(TomlEditError.NotExpandable(TomlType.InlineTable), dotted)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table in an inline table takes the dotted layout and back`() {
        val doc = parse("t = { u = { x = 1, y = 2 } }\n")
        doc["t"]["u"].assertSuccess().setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals("t = { u.x = 1, u.y = 2 }\n", doc.toString())
        assertEquals(TableLayout.Dotted, doc["t"]["u"].assertSuccess().layout)
        doc["t"]["u"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { u = { x = 1, y = 2 } }\n", doc.toString())
    }

    @Test
    fun `an inline table in an array takes the inline layouts`() {
        val doc = parse("a = [\n  { x = 1, y = 2 },\n]\n")
        doc["a"][0].assertSuccess().setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("a = [\n  {\n    x = 1,\n    y = 2,\n  },\n]\n", doc.toString())
        doc["a"][0].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("a = [\n  { x = 1, y = 2 },\n]\n", doc.toString())
    }

    @Test
    fun `a table stored nowhere takes a layout`() {
        val table = StandardTable()
        table["a"] = 1L
        val item = Item(table)
        item.setLayout(TableLayout.Dotted).assertSuccess()
        assertEquals(TableLayout.Dotted, table.layout)
        item.setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("{ a = 1 }", item.toString())
    }
}
