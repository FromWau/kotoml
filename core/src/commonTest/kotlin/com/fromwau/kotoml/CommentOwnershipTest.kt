package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** Which item each comment of a document belongs to, and which comment lines belong to none. */
class CommentOwnershipTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text, TomlVersion.V1_1).assertSuccess()

    private fun TomlDocument.entry(vararg path: String): KeyValue {
        var pairs = root.items
        var entry: KeyValue? = null
        for (name in path) {
            entry?.let { pairs = it.item.pairs() }
            entry = pairs.getEntry(name) ?: fail("no entry $name")
        }
        return entry ?: fail("empty path")
    }

    private fun Item.pairs(): KeyValuePairs =
        standardTableOrNull?.items ?: inlineTableOrNull?.items ?: fail("not a table")

    private fun TomlDocument.header(vararg path: String): StandardTable =
        entry(*path).item.standardTableOrNull ?: fail("not a table")

    private fun TomlDocument.element(
        index: Int,
        vararg path: String,
    ): ItemComments {
        val array = entry(*path).item.valueOrNull?.arrayOrNull ?: fail("not an array")
        return array.elementComments(index)
    }

    @Test
    fun `a whitespace-only line sets comment lines apart`() {
        assertEquals(LinesAbove("# a\n   \n", "# b\n"), splitLinesAbove("# a\n   \n# b\n"))
    }

    @Test
    fun `lines with no blank line among them are all attached`() {
        assertEquals(LinesAbove("", "# a\n# b\n"), splitLinesAbove("# a\n# b\n"))
    }

    @Test
    fun `the head comment set apart by a blank line is free`() {
        val doc = parse("# head\n\n# about x\nx = 1 # one\n")
        assertEquals(
            ItemComments(freeAbove = "# head\n\n", attachedAbove = "# about x\n", sameLine = " # one"),
            doc.entry("x").itemComments(),
        )
    }

    @Test
    fun `free lines end at the last blank line above an item`() {
        val doc = parse("# f1\n\n# f2\n\n# a1\n# a2\nx = 1\n")
        assertEquals(
            ItemComments(freeAbove = "# f1\n\n# f2\n\n", attachedAbove = "# a1\n# a2\n"),
            doc.entry("x").itemComments(),
        )
    }

    @Test
    fun `a block between two items belongs to the one below`() {
        val doc = parse("a = 1\n# about b\nb = 2\n")
        assertEquals(ItemComments.NONE, doc.entry("a").itemComments())
        assertEquals(ItemComments(attachedAbove = "# about b\n"), doc.entry("b").itemComments())
    }

    @Test
    fun `a comment after the last item of the document belongs to no item`() {
        val doc = parse("x = 1\n# tail\n")
        assertEquals(ItemComments.NONE, doc.entry("x").itemComments())
    }

    @Test
    fun `a block under a header belongs to the first key`() {
        val doc = parse("# about db\n[db] # primary\n# about url\nurl = \"x\"\n")
        assertEquals(
            ItemComments(attachedAbove = "# about db\n", sameLine = " # primary"),
            doc.header("db").headerComments(),
        )
        assertEquals(doc.header("db").headerComments(), doc.entry("db").itemComments())
        assertEquals(ItemComments(attachedAbove = "# about url\n"), doc.entry("db", "url").itemComments())
    }

    @Test
    fun `a comment ending a table above a blank line is free above the next header`() {
        val doc = parse("[a]\nx = 1\n# end of a\n\n[b]\n")
        assertEquals(ItemComments.NONE, doc.entry("a", "x").itemComments())
        assertEquals(ItemComments(freeAbove = "# end of a\n\n"), doc.header("b").headerComments())
    }

    @Test
    fun `a comment ending a table right above the next header belongs to that header`() {
        val doc = parse("[a]\nx = 1\n# end of a\n[b]\n")
        assertEquals(ItemComments.NONE, doc.entry("a", "x").itemComments())
        assertEquals(ItemComments(attachedAbove = "# end of a\n"), doc.header("b").headerComments())
    }

    @Test
    fun `a table that prints no header owns no comments`() {
        val doc = parse("# about y\n[x.y]\n")
        assertEquals(ItemComments.NONE, doc.entry("x").itemComments())
        assertEquals(ItemComments(attachedAbove = "# about y\n"), doc.header("x", "y").headerComments())
    }

    @Test
    fun `each array of tables element owns the comments of its header`() {
        val doc = parse("# above a1\n[[a]] # h1\n# above k\nk = 1\n\n# free\n\n# above a2\n[[a]]\nk = 2\n")
        val tables = doc.entry("a").item.arrayOfTablesOrNull ?: fail("not an array of tables")
        val first = ItemComments(attachedAbove = "# above a1\n", sameLine = " # h1")
        assertEquals(first, tables.elementAt(0).headerComments())
        assertEquals(
            ItemComments(freeAbove = "\n# free\n\n", attachedAbove = "# above a2\n"),
            tables.elementAt(1).headerComments(),
        )
        assertEquals(first, doc.entry("a").itemComments())
        val k = tables.elementAt(0).items.getEntry("k") ?: fail("no entry k")
        assertEquals(ItemComments(attachedAbove = "# above k\n"), k.itemComments())
    }

    @Test
    fun `dotted keys on table lines own their own lines`() {
        val doc = parse("a.b = 1 # on ab\n# above ac\na.c = 2\n[t]\n# above x.y\nx.y = 1\nz = 2\n")
        assertEquals(ItemComments(sameLine = " # on ab"), doc.entry("a", "b").itemComments())
        assertEquals(ItemComments(attachedAbove = "# above ac\n"), doc.entry("a", "c").itemComments())
        assertEquals(ItemComments.NONE, doc.entry("a").itemComments())
        assertEquals(ItemComments(attachedAbove = "# above x.y\n"), doc.entry("t", "x").itemComments())
        assertEquals(ItemComments(attachedAbove = "# above x.y\n"), doc.entry("t", "x", "y").itemComments())
        assertEquals(ItemComments.NONE, doc.entry("t", "z").itemComments())
    }

    @Test
    fun `a dotted table owns the comments above its first line`() {
        val doc = parse("# about a\na.b = 1 # on ab\nx = 0\na.c = 2\n")
        assertEquals(ItemComments(attachedAbove = "# about a\n"), doc.entry("a").itemComments())
        assertEquals(
            ItemComments(attachedAbove = "# about a\n", sameLine = " # on ab"),
            doc.entry("a", "b").itemComments(),
        )
    }

    @Test
    fun `an array across lines gives each comment to the element on its line or below it`() {
        val doc = parse(
            "arr = [ # open\n  # above 1\n  1, # on 1\n\n  # free\n\n" +
                "  # above 2\n  2 # on 2\n  , 3\n  # end\n] # after\n",
        )
        assertEquals(ItemComments(afterOpening = " # open", sameLine = " # after"), doc.entry("arr").itemComments())
        assertEquals(ItemComments(attachedAbove = "  # above 1\n", sameLine = " # on 1"), doc.element(0, "arr"))
        assertEquals(
            ItemComments(freeAbove = "\n  # free\n\n", attachedAbove = "  # above 2\n", sameLine = " # on 2"),
            doc.element(1, "arr"),
        )
        assertEquals(ItemComments(freeBelow = "  # end\n"), doc.element(2, "arr"))
    }

    @Test
    fun `an array on one line gives its comment to the key holding it`() {
        val doc = parse("arr = [1, 2] # on arr\n")
        assertEquals(ItemComments(sameLine = " # on arr"), doc.entry("arr").itemComments())
        assertEquals(ItemComments.NONE, doc.element(0, "arr"))
        assertEquals(ItemComments.NONE, doc.element(1, "arr"))
    }

    @Test
    fun `a comment after the last element before its bracket belongs to that element`() {
        val doc = parse("arr = [1, 2 # two\n]\n")
        assertEquals(ItemComments(sameLine = " # two"), doc.element(1, "arr"))
    }

    @Test
    fun `a comment on a line of several elements belongs to the last of them`() {
        val doc = parse("arr = [\n  1, 2, # pair\n  3,\n]\n")
        assertEquals(ItemComments.NONE, doc.element(0, "arr"))
        assertEquals(ItemComments(sameLine = " # pair"), doc.element(1, "arr"))
        assertEquals(ItemComments.NONE, doc.element(2, "arr"))
    }

    @Test
    fun `an empty array owns the comment after its opening bracket`() {
        val doc = parse("arr = [ # none yet\n]\n")
        assertEquals(ItemComments(afterOpening = " # none yet"), doc.entry("arr").itemComments())
    }

    @Test
    fun `an element holding an array owns the comment after its opening bracket`() {
        val doc = parse("arr = [\n  [ # inner\n    1,\n  ], # outer\n]\n")
        assertEquals(ItemComments(afterOpening = " # inner", sameLine = " # outer"), doc.element(0, "arr"))
    }

    @Test
    fun `an inline table across lines gives the comment after a comma to the entry on its line`() {
        val doc = parse(
            "s = { # after brace\n  # above b\n  b.c = 1, # on c\n  y = 5, # on y\n" +
                "  # about d\n  b.d = 2,\n  # end\n} # after\n",
        )
        assertEquals(
            ItemComments(afterOpening = " # after brace", sameLine = " # after"),
            doc.entry("s").itemComments(),
        )
        assertEquals(
            ItemComments(attachedAbove = "  # above b\n", sameLine = " # on c"),
            doc.entry("s", "b", "c").itemComments(),
        )
        assertEquals(ItemComments(sameLine = " # on y"), doc.entry("s", "y").itemComments())
        assertEquals(
            ItemComments(attachedAbove = "  # about d\n", freeBelow = "  # end\n"),
            doc.entry("s", "b", "d").itemComments(),
        )
    }

    @Test
    fun `a dotted key in braces owns the comments above its first line`() {
        val doc = parse("s = {\n  # above b\n  b.c = 1, # on c\n  y = 5,\n  b.d = 2,\n}\n")
        assertEquals(ItemComments(attachedAbove = "  # above b\n"), doc.entry("s", "b").itemComments())
    }

    @Test
    fun `an inline table on one line gives its comment to the key holding it`() {
        val doc = parse("s = { b.c = 1, y = 5 } # on s\n")
        assertEquals(ItemComments(sameLine = " # on s"), doc.entry("s").itemComments())
        assertEquals(ItemComments.NONE, doc.entry("s", "y").itemComments())
        assertEquals(ItemComments.NONE, doc.entry("s", "b", "c").itemComments())
    }

    @Test
    fun `CRLF lines split as LF lines do`() {
        val doc = parse("# c\r\nx = 1 # cx\r\n\r\n# free\r\n\r\ny = 2\r\n")
        assertEquals(ItemComments(attachedAbove = "# c\r\n", sameLine = " # cx"), doc.entry("x").itemComments())
        assertEquals(ItemComments(freeAbove = "\r\n# free\r\n\r\n"), doc.entry("y").itemComments())
    }

    @Test
    fun `CRLF lines in brackets split as LF lines do`() {
        val doc = parse("arr = [\r\n  1, # one\r\n  # above 2\r\n  2,\r\n  # end\r\n]\r\n")
        assertEquals(ItemComments(sameLine = " # one"), doc.element(0, "arr"))
        assertEquals(ItemComments(attachedAbove = "  # above 2\r\n", freeBelow = "  # end\r\n"), doc.element(1, "arr"))
    }
}
