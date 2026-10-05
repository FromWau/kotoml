package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** Comments stay with the content they describe when a table or inline table stops printing its header or braces. */
class HeaderCommentsTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).getOrFail()

    private fun TomlDocument.table(key: String): StandardTable = this[key].getOrFail().standardTableOrNull!!

    @Test
    fun `a table made dotted takes its header comments onto its first line`() {
        val doc = parse("# about x\n[x] # x header\na = 1\n")
        doc.table("x").isDotted = true
        assertEquals("# about x\nx.a = 1 # x header\n", doc.toString())
    }

    @Test
    fun `a table made dotted whose first line has a comment takes the header comment on a line above it`() {
        val doc = parse("# about x\n[x] # x header\na = 1 # one\n")
        doc.table("x").isDotted = true
        assertEquals("# about x\n# x header\nx.a = 1 # one\n", doc.toString())
    }

    @Test
    fun `a table made dotted that holds only tables keeps its header comments above the first of them`() {
        val doc = parse("a = 1\n\n# about x\n[x] # x header\n[x.y]\nb = 2\n")
        doc.table("x").isDotted = true
        assertEquals("a = 1\n\n# about x\n# x header\n[x.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `a header hidden as implicit leaves its comments where it stood`() {
        val doc = parse("a = 1\n\n# about x\n[x] # x header\n[x.y]\nb = 2\n")
        doc.table("x").isImplicit = true
        assertEquals("a = 1\n\n# about x\n# x header\n[x.y]\nb = 2\n", doc.toString())
    }

    @Test
    fun `an inline table made dotted ends its last line with the comment after its closing brace`() {
        val doc = parse("t = { a = 1, b = 2 } # note\n")
        doc["t"].getOrFail().inlineTableOrNull!!.isDotted = true
        assertEquals("t.a = 1\nt.b = 2 # note\n", doc.toString())
    }

    @Test
    fun `a multi-line inline table made dotted keeps every comment in it on a line of its own or its line`() {
        val text = "# about t\nt = { # open\n  # about c\n  c = 3, # three\n  d = 4, # four\n  # end\n} # close\n"
        val doc = TomlDocument.parse(text, TomlVersion.V1_1).getOrFail()
        val table = doc["t"].getOrFail().inlineTableOrNull!!
        table.isDotted = true
        assertEquals(
            "# about t\n# open\n  # about c\nt.c = 3 # three\nt.d = 4 # four\n  # end\n# close\n",
            doc.toString(),
        )
        table.remove("c")
        assertEquals("# about t\n# open\n  # about c\nt.d = 4 # four\n  # end\n# close\n", doc.toString())
    }
}
