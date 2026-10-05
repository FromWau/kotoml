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
        doc.table("x").isDotted = true
        assertEquals("# about x\nx.a = 1\n", doc.toString())
    }

    @Test
    fun `a table made dotted keeps its values' same-line comments and drops the comment lines inside it`() {
        val doc = parse("x = 1\n\n# about t\n[t] # tee\n# k\nk = 1 # kay\n")
        doc.table("t").isDotted = true
        assertEquals("x = 1\n\n# about t\nt.k = 1 # kay\n", doc.toString())
    }

    @Test
    fun `a table made dotted that holds only tables keeps the lines above its header above the first of them`() {
        val doc = parse("a = 1\n\n# about x\n[x] # x header\n[x.y]\nb = 2\n")
        doc.table("x").isDotted = true
        assertEquals("a = 1\n\n# about x\n[x.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a header hidden as implicit leaves its comments where it stood`() {
        val doc = parse("a = 1\n\n# about x\n[x] # x header\n[x.y]\nb = 2\n")
        doc.table("x").isImplicit = true
        assertEquals("a = 1\n\n# about x\n# x header\n[x.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `an inline table made dotted drops the comment after its closing brace`() {
        val doc = parse("t = { a = 1, b = 2 } # note\n")
        doc["t"].getOrFail().inlineTableOrNull!!.isDotted = true
        assertEquals("t.a = 1\nt.b = 2\n", doc.toString())
    }

    @Test
    fun `a multi-line inline table made dotted keeps its values' same-line comments and drops its other comments`() {
        val text = "# about t\nt = { # open\n  # about c\n  c = 3, # three\n  d = 4, # four\n  # end\n} # close\n"
        val doc = TomlDocument.parse(text, TomlVersion.V1_1).getOrFail()
        val table = doc["t"].getOrFail().inlineTableOrNull!!
        table.isDotted = true
        assertEquals("# about t\nt.c = 3 # three\nt.d = 4 # four\n", doc.toString())
        table.remove("c")
        assertEquals("# about t\nt.d = 4 # four\n", doc.toString())
    }

    @Test
    fun `the comment above a removed key prints once above the emptied dotted table after it`() {
        val doc = parse("# about b\nb = 2\n\n# about t\n[t] # t\nc = 3\n")
        val table = doc.table("t")
        table.isDotted = true
        table.remove("c")
        doc.remove("b")
        assertEquals("# about b\n\n# about t\nt = {}\n", doc.toString())
        table.isDotted = false
        assertEquals("# about b\n\n# about t\n[t]\n", doc.toString())
    }

    @Test
    fun `an inline table made dotted inside a dotted one keeps the same-line comment of its value`() {
        val text = "t = { u = { # open u\n  k = 1, # one\n} # after u\n}\n"
        val doc = TomlDocument.parse(text, TomlVersion.V1_1).getOrFail()
        val outer = doc["t"].getOrFail().inlineTableOrNull!!
        outer.isDotted = true
        outer["u"]!!.inlineTableOrNull!!.isDotted = true
        assertEquals("t.u.k = 1 # one\n", doc.toString())
    }
}
