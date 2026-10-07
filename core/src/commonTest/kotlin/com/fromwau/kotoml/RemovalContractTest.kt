package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A removal that would take comments out of the document is refused without force and changes nothing; with force
 * it takes the comments that belong to what it removes, and a removal of what holds no comment needs no force.
 */
class RemovalContractTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text, TomlVersion.V1_1).assertSuccess()

    private val owned = "# free\n\n# about x\nx = 1 # one\ny = 2\n"

    @Test
    fun `removing a key that holds comments without force fails and changes nothing`() {
        val doc = parse(owned)
        val error = doc.remove("x").assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x"), 2), error)
        assertEquals(owned, doc.toString())
    }

    @Test
    fun `removing a key with force takes its comments and leaves the free lines`() {
        val doc = parse(owned)
        val item = doc.remove("x", force = true).assertSuccess()
        assertEquals("# free\n\ny = 2\n", doc.toString())
        assertEquals(" # one", item.valueOrNull!!.decor.suffix)
    }

    @Test
    fun `an array element that holds comments is refused without force and goes with them forced`() {
        val doc = parse("a = [\n  # one\n  1,\n  2,\n]\n")
        val array = doc["a"].assertSuccess().arrayOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), array.removeAt(0).assertError())
        assertEquals(1L, array.removeAt(0, force = true).assertSuccess().longOrNull)
        assertEquals("a = [\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a comment on a line holding only a comma goes with the value before the comma`() {
        val doc = parse("arr = [\n  1,\n  2 # on 2\n  , # comma\n  3,\n]\n")
        val array = doc["arr"].assertSuccess().arrayOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("arr"), 2), array.removeAt(1).assertError())
        array.removeAt(1, force = true).assertSuccess()
        assertEquals("arr = [\n  1,\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `clearing without force fails when comments would go and changes nothing`() {
        val text = "[a]\n# one\nx = 1\ny = 2 # two\n"
        val doc = parse(text)
        val table = doc["a"].assertSuccess().standardTableOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a", "x"), 2), table.clear().assertError())
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 2), doc.clear().assertError())
        assertEquals(text, doc.toString())
        table.clear(force = true).assertSuccess()
        assertEquals("[a]\n", doc.toString())
    }

    @Test
    fun `clearing an array or an inline table without force fails when comments would go`() {
        val doc = parse("a = [ # open\n  1, # one\n  2,\n]\ni = { x = 1, y = 2 } # i\n")
        val array = doc["a"].assertSuccess().arrayOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), array.clear().assertError())
        array.clear(force = true).assertSuccess()
        doc["i"].assertSuccess().inlineTableOrNull!!.clear().assertSuccess()
        assertEquals("a = [ # open\n]\ni = {} # i\n", doc.toString())
    }

    @Test
    fun `an emptied array of tables keeps the comment above its first header and needs no force for it`() {
        val doc = parse("a = 1\n# bins\n[[bin]]\nn = 1\n")
        doc["bin"].assertSuccess().arrayOfTablesOrNull!!.removeAt(0).assertSuccess()
        assertEquals("a = 1\n# bins\nbin = []\n", doc.toString())
    }

    @Test
    fun `an array of tables emptied one table at a time keeps only the comment above its first header`() {
        val doc = parse("a = 1\n\n# xs\n[[x]] # one\nk = 1\n# between\n[[x]]\nk = 2\n")
        val tables = doc["x"].assertSuccess().arrayOfTablesOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x"), 1), tables.removeAt(1).assertError())
        tables.removeAt(1, force = true).assertSuccess()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x"), 1), tables.removeAt(0).assertError())
        tables.removeAt(0, force = true).assertSuccess()
        assertEquals("a = 1\n\n# xs\nx = []\n", doc.toString())
    }

    @Test
    fun `clearing an array of tables keeps the comment above its first header and takes the others`() {
        val doc = parse("# all\n[[x]]\nk = 1\n# second\n[[x]]\nk = 2\n")
        val tables = doc["x"].assertSuccess().arrayOfTablesOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x"), 1), tables.clear().assertError())
        tables.clear(force = true).assertSuccess()
        assertEquals("# all\nx = []\n", doc.toString())
    }

    @Test
    fun `removing a key that holds no comment needs no force`() {
        val doc = parse("# free\n\nx = 1\ny = 2 # two\n")
        assertEquals(1L, doc.remove("x").assertSuccess().longOrNull)
        assertEquals("# free\n\ny = 2 # two\n", doc.toString())
    }

    @Test
    fun `removing a key that is not there fails with NotFound`() {
        val doc = parse("[t]\nx = 1\ni = { a = 1 }\n")
        assertEquals(TomlEditError.NotFound("z"), doc.remove("z").assertError<TomlEditError.NotFound>())
        val table = doc["t"].assertSuccess().standardTableOrNull!!
        assertEquals(TomlEditError.NotFound("z"), table.remove("z", force = true).assertError<TomlEditError.NotFound>())
        assertEquals(TomlEditError.NotFound("z"), table.removeItem("z").assertError<TomlEditError.NotFound>())
        val inline = doc["t"]["i"].assertSuccess().inlineTableOrNull!!
        assertEquals(TomlEditError.NotFound("z"), inline.remove("z").assertError<TomlEditError.NotFound>())
        assertEquals(TomlEditError.NotFound("z"), inline.removeItem("z").assertError<TomlEditError.NotFound>())
    }

    @Test
    fun `removing an index that is not there fails with IndexOutOfBounds`() {
        val doc = parse("a = [1]\n[[t]]\n")
        val error = doc["a"].assertSuccess().arrayOrNull!!.removeAt(1).assertError<TomlEditError.IndexOutOfBounds>()
        assertEquals(TomlEditError.IndexOutOfBounds(1, 1), error)
        doc["t"].assertSuccess().arrayOfTablesOrNull!!.removeAt(1).assertError<TomlEditError.IndexOutOfBounds>()
    }

    @Test
    fun `comments inside a removed table count with the comment above its header`() {
        val text = "# about t\n[t] # head\n# about x\nx = 1 # one\n\n[t.u] # u\n"
        val doc = parse(text)
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 5), doc.remove("t").assertError())
        assertEquals(text, doc.toString())
        doc.remove("t", force = true).assertSuccess()
        assertEquals("", doc.toString())
    }

    @Test
    fun `a comment right above a header belongs to that header`() {
        val text = "[a]\nx = 1\n# end of a\n[b]\ny = 2\n"
        assertEquals("# end of a\n[b]\ny = 2\n", parse(text).also { it.remove("a").assertSuccess() }.toString())
        val doc = parse(text)
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("b"), 1), doc.remove("b").assertError())
        doc.remove("b", force = true).assertSuccess()
        assertEquals("[a]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a comment after an opening bracket belongs to the array and not to its first element`() {
        val doc = parse("arr = [ # open\n  1,\n  2,\n]\n")
        doc["arr"].assertSuccess().arrayOrNull!!.removeAt(0).assertSuccess()
        assertEquals("arr = [ # open\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `the last element on a line owns the comment ending that line`() {
        val text = "arr = [\n  1, 2, # pair\n  3,\n]\n"
        val first = parse(text)
        first["arr"].assertSuccess().arrayOrNull!!.removeAt(0).assertSuccess()
        assertEquals("arr = [\n  2, # pair\n  3,\n]\n", first.toString())
        val last = parse(text)
        val array = last["arr"].assertSuccess().arrayOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("arr"), 1), array.removeAt(1).assertError())
        array.removeAt(1, force = true).assertSuccess()
        assertEquals("arr = [\n  1,\n  3,\n]\n", last.toString())
    }

    @Test
    fun `a comment before the closing bracket belongs to no element and goes with the array`() {
        val doc = parse("arr = [\n  1,\n  # last note\n]\n")
        doc["arr"].assertSuccess().arrayOrNull!!.removeAt(0).assertSuccess()
        assertEquals("arr = [\n  # last note\n]\n", doc.toString())
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("arr"), 1), doc.remove("arr").assertError())
    }

    @Test
    fun `a comment between a value and its comma on a later line goes with the value`() {
        val doc = parse("arr = [\n  1,\n  2 # on 2\n  # more\n  , 3,\n]\n")
        val array = doc["arr"].assertSuccess().arrayOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("arr"), 2), array.removeAt(1).assertError())
        array.removeAt(1, force = true).assertSuccess()
        assertEquals("arr = [\n  1,\n  3,\n]\n", doc.toString())
    }

    @Test
    fun `a comment above a dotted key belongs to the key and to its table`() {
        val text = "# about a.b\na.b = 1\nc = 2\n"
        val doc = parse(text)
        val a = doc["a"].assertSuccess().standardTableOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a", "b"), 1), a.remove("b").assertError())
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), doc.remove("a").assertError())
        assertEquals(text, doc.toString())
        a.remove("b", force = true).assertSuccess()
        assertEquals("a = {}\nc = 2\n", doc.toString())
    }

    @Test
    fun `an inline table entry that holds comments is refused without force`() {
        val doc = parse("t = {\n  a = 1, # one\n  b = 2,\n}\n")
        val inline = doc["t"].assertSuccess().inlineTableOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "a"), 1), inline.remove("a").assertError())
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "a"), 1), inline.removeItem("a").assertError())
        assertEquals(2L, inline.remove("b").assertSuccess().longOrNull)
        assertEquals("t = {\n  a = 1, # one\n}\n", doc.toString())
    }

    @Test
    fun `an array of tables element that holds comments is refused without force`() {
        val doc = parse("[[x]]\nn = 1\n# second\n[[x]]\nn = 2\n")
        val tables = doc["x"].assertSuccess().arrayOfTablesOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x"), 1), tables.removeAt(1).assertError())
        tables.removeAt(0).assertSuccess()
        assertEquals("# second\n[[x]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `removeAll without force removes none when one pair holds comments and names the first`() {
        val text = "a = 1\n# about b\nb = 2\nc = 3 # three\n"
        val doc = parse(text)
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("b"), 2), doc.removeAll { true }.assertError())
        assertEquals(text, doc.toString())
        assertTrue(doc.removeAll(force = true) { true }.assertSuccess())
        assertEquals("", doc.toString())
    }

    @Test
    fun `retainAll without force removes none when one value holds comments`() {
        val text = "arr = [\n  1,\n  # two\n  2,\n  3, # three\n]\n"
        val doc = parse(text)
        val array = doc["arr"].assertSuccess().arrayOrNull!!
        val error = array.retainAll { it.longOrNull == 1L }.assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("arr"), 2), error)
        assertEquals(text, doc.toString())
        assertFalse(array.retainAll { true }.assertSuccess())
    }

    @Test
    fun `removeAll on an array of tables and an inline table refuses as remove does`() {
        val doc = parse("i = {\n  a = 1, # one\n  b = 2,\n}\n[[x]]\nn = 1\n# second\n[[x]]\nn = 2\n")
        val inline = doc["i"].assertSuccess().inlineTableOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("i", "a"), 1), inline.removeAll { true }.assertError())
        val tables = doc["x"].assertSuccess().arrayOfTablesOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x"), 1), tables.retainAll { false }.assertError())
        assertTrue(inline.retainAll { (key, _) -> key == "a" }.assertSuccess())
    }

    @Test
    fun `a removed table holds the comments that went with it and prints them where it is stored`() {
        val source = parse("x = 1\n\n# about t\n[t] # head\nk = 1\n")
        val table = source.remove("t", force = true).assertSuccess()
        assertEquals("x = 1\n", source.toString())
        val doc = parse("y = 2\n")
        doc.set("t", table).assertSuccess()
        assertEquals("y = 2\n# about t\n[t] # head\nk = 1\n", doc.toString())
    }
}
