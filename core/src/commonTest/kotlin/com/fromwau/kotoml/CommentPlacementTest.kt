package com.fromwau.kotoml

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where comments land when tables are stored again, copied, converted or given a comment by the caller. */
class CommentPlacementTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    @Ignore
    @Test
    fun `a removed table stored again prints the comment above its header once`() {
        val text = "x = 1\n\n# about t\n[t]\nk = 1\n"
        val doc = parse(text)
        repeat(3) {
            val table = doc.remove("t")!!
            doc.set("t", table).getOrFail()
        }
        assertEquals(text, doc.toString())
    }

    @Ignore
    @Test
    fun `a table copied from another document leaves its banner and header comment behind`() {
        val source = parse("# Generated, do not edit\n\n[db] # db hdr\nurl = 1\n")
        val doc = parse("[app]\nname = 1\n")
        doc.set("db", source["db"].getOrFail()).getOrFail()
        assertEquals("[app]\nname = 1\n\n[db]\nurl = 1\n", doc.toString())
    }

    @Ignore
    @Test
    fun `a table replaced by one from another document keeps its header comment`() {
        val source = parse("[app] # bh\ny = 2\n")
        val doc = parse("[app] # ah\nx = 1\n")
        doc.set("app", source["app"].getOrFail()).getOrFail()
        assertEquals("[app] # ah\ny = 2\n", doc.toString())
    }

    @Ignore
    @Test
    fun `an inline array of tables made an array of tables prints the comment above its key once`() {
        val doc = parse("name = 1\n# bins\nbin = [{ name = \"a\" }, { name = \"b\" }] # bc\nafter = 1\n")
        val tables = doc["bin"].getOrFail().valueOrNull!!.arrayOrNull!!
            .map { it.inlineTableOrNull!!.toStandardTable() }
            .toArrayOfTables()
            .getOrFail()
        doc.set("bin", tables).getOrFail()
        assertEquals(
            "name = 1\nafter = 1\n\n# bins\n[[bin]]\nname = \"a\"\n\n[[bin]]\nname = \"b\"\n",
            doc.toString(),
        )
    }

    @Ignore
    @Test
    fun `a key added after the last table is removed prints below the document's head`() {
        val doc = parse("# banner\n\n[db]\nurl = 1\n")
        doc.remove("db")
        doc["x"] = 1L
        assertEquals("# banner\n\nx = 1\n", doc.toString())
    }

    @Ignore
    @Test
    fun `a comment given to an element puts a one-line array on lines of its own`() {
        val doc = parse("a = [1, 2]\n")
        val element = Value.Integer(3L).also { it.decor = Decor(suffix = " # c") }
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(element).getOrFail()
        assertEquals("a = [\n  1,\n  2,\n  3, # c\n]\n", doc.toString())
    }

    @Ignore
    @Test
    fun `a comment given to a value puts a one-line inline table on lines of its own`() {
        val doc = parse("t = { a = 1 }\n")
        val value = Value.Integer(2L).also { it.decor = Decor(suffix = " # c") }
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("b", value).getOrFail()
        assertEquals("t = {\n  a = 1,\n  b = 2 # c\n}\n", doc.toString())
    }

    @Ignore
    @Test
    fun `an inline table made a standard table keeps its comments`() {
        val doc = parse("srv = {\n  host = \"h\", # host c\n  # above port\n  port = 1,\n}\n")
        doc.set("srv", doc["srv"].getOrFail().valueOrNull!!.inlineTableOrNull!!.toStandardTable()).getOrFail()
        assertEquals("[srv]\nhost = \"h\" # host c\n# above port\nport = 1\n", doc.toString())
    }

    @Ignore
    @Test
    fun `an array of tables made a value keeps its comments in TOML 1_1`() {
        val doc = parse("# first\n[[s]] # ac\nn = 1 # one\n\n[[s]]\nn = 2\n")
        doc["s"].getOrFail().makeValue().getOrFail()
        assertEquals("# first\ns = [\n  { # ac\n    n = 1 # one\n  },\n  { n = 2 },\n]\n", doc.toString())
    }
}
