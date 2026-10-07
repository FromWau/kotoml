package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** A table or inline table given a new layout keeps the lines above its key and its values' same-line comments. */
class HeaderCommentsTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).getOrFail()

    private fun TomlDocument.table(key: String): StandardTable = this[key].getOrFail().standardTableOrNull!!

    @Test
    fun `a table made dotted keeps the lines above its header and drops the header comment`() {
        val doc = parse("# about x\n[x] # x header\na = 1\n")
        doc.table("x").setDotted(true, force = true).getOrFail()
        assertEquals("# about x\nx.a = 1\n", doc.toString())
    }

    @Test
    fun `a table made dotted without force is refused naming every comment it would drop`() {
        val text = "x = 1\n\n# about t\n[t] # tee\n# k\nk = 1 # kay\n# m\nm = 2\n"
        val doc = parse(text)
        val error = doc.table("t").setDotted(true).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 3), error)
        assertEquals(text, doc.toString())
        assertEquals(false, doc.table("t").isDotted)
    }

    @Test
    fun `a table with only the lines above its key and its values' comments is made dotted without force`() {
        val doc = parse("# about t\n[t]\nk = 1 # kay\n")
        doc.table("t").setDotted(true).getOrFail()
        assertEquals("# about t\nt.k = 1 # kay\n", doc.toString())
    }

    @Test
    fun `an inline table made dotted without force is refused while the comment after its braces would go`() {
        val text = "s.t = { a = 1 } # note\n"
        val doc = parse(text)
        val inline = doc["s"]["t"].getOrFail().inlineTableOrNull!!
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("s", "t"), 1), inline.setDotted(true).errorOrFail())
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a table made dotted keeps its values' same-line comments and drops the comment lines inside it`() {
        val doc = parse("x = 1\n\n# about t\n[t] # tee\n# k\nk = 1 # kay\n")
        doc.table("t").setDotted(true, force = true).getOrFail()
        assertEquals("x = 1\n\n# about t\nt.k = 1 # kay\n", doc.toString())
    }

    @Test
    fun `a table made dotted that holds only tables keeps the lines above its header above the first of them`() {
        val doc = parse("a = 1\n\n# about x\n[x] # x header\n[x.y]\nb = 2\n")
        doc.table("x").setDotted(true, force = true).getOrFail()
        assertEquals("a = 1\n\n# about x\n[x.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a header hidden as implicit leaves its comments where it stood`() {
        val doc = parse("a = 1\n\n# about x\n[x] # x header\n[x.y]\nb = 2\n")
        doc.table("x").setImplicit(true)
        assertEquals("a = 1\n\n# about x\n# x header\n[x.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `an inline table made dotted drops the comment after its closing brace`() {
        val doc = parse("t = { a = 1, b = 2 } # note\n")
        doc["t"].getOrFail().inlineTableOrNull!!.setDotted(true, force = true).getOrFail()
        assertEquals("t.a = 1\nt.b = 2\n", doc.toString())
    }

    @Test
    fun `a multi-line inline table made dotted keeps its values' same-line comments and drops its other comments`() {
        val text = "# about t\nt = { # open\n  # about c\n  c = 3, # three\n  d = 4, # four\n  # end\n} # close\n"
        val doc = TomlDocument.parse(text, TomlVersion.V1_1).getOrFail()
        val table = doc["t"].getOrFail().inlineTableOrNull!!
        table.setDotted(true, force = true).getOrFail()
        assertEquals("# about t\nt.c = 3 # three\nt.d = 4 # four\n", doc.toString())
        table.remove("c")
        assertEquals("# about t\nt.d = 4 # four\n", doc.toString())
    }

    @Test
    fun `the comment above a removed key prints once above the emptied dotted table after it`() {
        val doc = parse("# about b\nb = 2\n\n# about t\n[t] # t\nc = 3\n")
        val table = doc.table("t")
        table.setDotted(true, force = true).getOrFail()
        table.remove("c")
        doc.remove("b")
        assertEquals("# about b\n\n# about t\nt = {}\n", doc.toString())
        table.setDotted(false, force = true).getOrFail()
        assertEquals("# about b\n\n# about t\n[t]\n", doc.toString())
    }

    @Test
    fun `an inline table made dotted inside a dotted one keeps the same-line comment of its value`() {
        val text = "t = { u = { # open u\n  k = 1, # one\n} # after u\n}\n"
        val doc = TomlDocument.parse(text, TomlVersion.V1_1).getOrFail()
        val outer = doc["t"].getOrFail().inlineTableOrNull!!
        outer.setDotted(true, force = true).getOrFail()
        outer["u"].getOrFail().inlineTableOrNull!!.setDotted(true, force = true).getOrFail()
        assertEquals("t.u.k = 1 # one\n", doc.toString())
    }
}
