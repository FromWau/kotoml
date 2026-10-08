package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Edits lay their text out the way the file around them already is. */
class EditLayoutTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

    private fun TomlDocument.array(key: String): TomlArray = this[key].assertSuccess().valueOrNull!!.arrayOrNull!!

    @Test
    fun `replacing a value keeps a missing final newline`() {
        val doc = parse("a = 1\nb = 2")
        doc.set("a", 5L).assertSuccess()
        assertEquals("a = 5\nb = 2", doc.toString())
    }

    @Test
    fun `adding a key keeps a missing final newline`() {
        val doc = parse("a = 1\nb = 2")
        doc.set("c", 3L).assertSuccess()
        assertEquals("a = 1\nb = 2\nc = 3", doc.toString())
    }

    @Test
    fun `replacing the last value of a CRLF file keeps its missing final newline`() {
        val doc = parse("a = 1\r\nb = 2")
        doc.set("b", 3L).assertSuccess()
        assertEquals("a = 1\r\nb = 3", doc.toString())
    }

    @Test
    fun `a table moved under another prints after its new parent`() {
        val doc = parse("[a]\nk = 1\n\n[b]\nm = 2\n\n[c]\nn = 3\n")
        val moved = doc.remove("a").assertSuccess()
        doc["b"].assertSuccess().set("a", moved).assertSuccess()
        assertEquals("[b]\nm = 2\n\n[b.a]\nk = 1\n\n[c]\nn = 3\n", doc.toString())
    }

    @Test
    fun `a table copied into an existing table prints after that table`() {
        val doc = parse(
            "[dependencies.reqwest]\nversion = \"0.11\"\n\n[dev-dependencies]\ncriterion = \"0.5\"\n\n[features]\nx = []\n",
        )
        doc["dev-dependencies"].assertSuccess().set("reqwest", doc["dependencies"]["reqwest"].assertSuccess()).assertSuccess()
        assertEquals(
            "[dependencies.reqwest]\nversion = \"0.11\"\n\n[dev-dependencies]\ncriterion = \"0.5\"\n\n" +
                "[dev-dependencies.reqwest]\nversion = \"0.11\"\n\n[features]\nx = []\n",
            doc.toString(),
        )
    }

    @Test
    fun `a table copied into a new table prints with it after the existing tables`() {
        val doc = parse("[dependencies.reqwest]\nversion = \"0.11\"\n\n[features]\nx = []\n")
        val copy = standardTableOf("reqwest" to doc["dependencies"]["reqwest"].assertSuccess()).assertSuccess()
        doc.set("build-dependencies", copy).assertSuccess()
        assertEquals(
            "[dependencies.reqwest]\nversion = \"0.11\"\n\n[features]\nx = []\n\n" +
                "[build-dependencies.reqwest]\nversion = \"0.11\"\n",
            doc.toString(),
        )
    }

    @Test
    fun `a table replaced by a copy from the same document prints where it stood`() {
        val doc = parse("[app]\nname = \"x\"\n\n[mid]\nm = 1\n\n[other]\nname = \"y\"\n")
        doc.set("app", doc["other"].assertSuccess()).assertSuccess()
        assertEquals("[app]\nname = \"y\"\n\n[mid]\nm = 1\n\n[other]\nname = \"y\"\n", doc.toString())
    }

    @Test
    fun `removing a table between blank lines leaves one blank line`() {
        val doc = parse("[a]\nx = 1\n\n[b]\ny = 2\n\n[c]\nz = 3\n")
        doc.remove("b").assertSuccess()
        assertEquals("[a]\nx = 1\n\n[c]\nz = 3\n", doc.toString())
    }

    @Test
    fun `removing the last table leaves no blank line at the end`() {
        val doc = parse("[a]\nx = 1\n\n[b]\ny = 2\n")
        doc.remove("b").assertSuccess()
        assertEquals("[a]\nx = 1\n", doc.toString())
    }

    @Test
    fun `removing the first table leaves no blank line at the start`() {
        val doc = parse("[a]\nx = 1\n\n[b]\ny = 2\n")
        doc.remove("a").assertSuccess()
        assertEquals("[b]\ny = 2\n", doc.toString())
    }

    @Test
    fun `removing a table keeps both blank lines around the free lines kept from it`() {
        val doc = parse("[a]\nx = 1\n\n# about b\n\n[b]\ny = 2\n\n[c]\nz = 3\n")
        doc.remove("b", force = true).assertSuccess()
        assertEquals("[a]\nx = 1\n\n# about b\n\n[c]\nz = 3\n", doc.toString())
    }

    @Test
    fun `removing a table takes the comment above its header and leaves one blank line`() {
        val doc = parse("[a]\nx = 1\n\n# about b\n[b]\ny = 2\n\n[c]\nz = 3\n")
        doc.remove("b", force = true).assertSuccess()
        assertEquals("[a]\nx = 1\n\n[c]\nz = 3\n", doc.toString())
    }

    @Test
    fun `adding to a one-line array keeps its spacing`() {
        val padded = parse("a = [ 1, 2 ]\n")
        padded.array("a").add(3L)
        assertEquals("a = [ 1, 2, 3 ]\n", padded.toString())
        val tight = parse("a = [1,2]\n")
        tight.array("a").add(3L)
        assertEquals("a = [1,2,3]\n", tight.toString())
    }

    @Test
    fun `adding to an array closed on its last element's line puts the element on a line of its own`() {
        val doc = parse("a = [\n  1,\n  2]\n")
        doc.array("a").add(3L)
        assertEquals("a = [\n  1,\n  2,\n  3]\n", doc.toString())
    }

    @Test
    fun `adding to a one-line inline table keeps its spacing`() {
        val doc = parse("t = {a=1}\n")
        doc["t"].assertSuccess().valueOrNull!!.inlineTableOrNull!!.set("b", Value.Integer(2L)).assertSuccess()
        assertEquals("t = {a=1, b=2}\n", doc.toString())
    }

    @Test
    fun `a key added to an indented table takes its indentation`() {
        val doc = parse("[server]\n  host = \"h\"\n  port = 1\n")
        doc["server"].assertSuccess().set("workers", Item(Value.Integer(4L))).assertSuccess()
        assertEquals("[server]\n  host = \"h\"\n  port = 1\n  workers = 4\n", doc.toString())
    }

    @Test
    fun `a table made by hand that holds only tables prints no header of its own`() {
        val doc = parse("[project]\nname = \"x\"\n")
        val ruff = standardTableOf("line-length" to Item(Value.Integer(100L))).assertSuccess()
        doc.set("tool", standardTableOf("ruff" to Item(ruff)).assertSuccess()).assertSuccess()
        assertEquals("[project]\nname = \"x\"\n\n[tool.ruff]\nline-length = 100\n", doc.toString())
    }

    @Test
    fun `sorting by key name moves header tables`() {
        val doc = parse("b = 1\n[z]\nq = 1\n[a]\nw = 1\n")
        doc.root.sortBy { it.first.name }
        assertEquals("b = 1\n[a]\nw = 1\n[z]\nq = 1\n", doc.toString())
    }

    @Test
    fun `sort moves each header with the comment above it and leaves the blank lines in place`() {
        val doc = parse("[z]\nq = 1\n\n# about a\n[a]\nw = 1\n")
        doc.root.sort()
        assertEquals("# about a\n[a]\nw = 1\n\n[z]\nq = 1\n", doc.toString())
    }

    @Test
    fun `sort orders keys by name in plain string order`() {
        val doc = parse("b = 1\nB = 2\n[z]\n[a]\n")
        doc.root.sort()
        assertEquals("B = 2\nb = 1\n[a]\n[z]\n", doc.toString())
    }

    @Test
    fun `sorting moves the tables under a header with it`() {
        val doc = parse("[b]\nx = 1\n[b.c]\ny = 2\n[a]\nz = 3\n")
        doc.root.sort()
        assertEquals("[a]\nz = 3\n[b]\nx = 1\n[b.c]\ny = 2\n", doc.toString())
    }

    @Test
    fun `a position set on a table sets where its header prints`() {
        val doc = parse("[b]\n[a]\n")
        doc["a"].assertSuccess().standardTableOrNull!!.position = 0
        assertEquals("[a]\n[b]\n", doc.toString())
    }

    @Test
    fun `a table made by hand with a decor holding only tables prints its header`() {
        val doc = TomlDocument()
        val tool = standardTableOf("ruff" to Item(standardTableOf("x" to Item(Value.Integer(1L))).assertSuccess()))
            .assertSuccess()
        tool.decor = Decor("# tools\n", null)
        doc.set("tool", tool).assertSuccess()
        assertEquals("# tools\n[tool]\n\n[tool.ruff]\nx = 1\n", doc.toString())
    }

    @Test
    fun `a table removed then stored under another key prints right after the tables before its key`() {
        val doc = parse("[a]\nk = 1\n\n[b]\nm = 2\n\n[b.x]\nn = 3\n\n[c]\no = 4\n")
        val moved = doc.remove("a").assertSuccess()
        doc["b"].assertSuccess().set("a", moved).assertSuccess()
        assertEquals("[b]\nm = 2\n\n[b.x]\nn = 3\n\n[b.a]\nk = 1\n\n[c]\no = 4\n", doc.toString())
    }
}
