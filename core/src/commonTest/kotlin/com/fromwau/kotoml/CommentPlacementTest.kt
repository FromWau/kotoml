package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where comments land when tables are stored again, copied, converted or given a comment by the caller. */
class CommentPlacementTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

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

    @Test
    fun `a table copied from another document leaves its banner and header comment behind`() {
        val source = parse("# Generated, do not edit\n\n[db] # db hdr\nurl = 1\n")
        val doc = parse("[app]\nname = 1\n")
        doc.set("db", source["db"].getOrFail()).getOrFail()
        assertEquals("[app]\nname = 1\n\n[db]\nurl = 1\n", doc.toString())
    }

    @Test
    fun `a value copied from another document leaves its same-line comment behind`() {
        val source = parse("a = 1 # from source\n")
        val doc = parse("x = 1\n")
        doc.set("a", source["a"].getOrFail()).getOrFail()
        assertEquals("x = 1\na = 1\n", doc.toString())
    }

    @Test
    fun `a table replaced by one from another document keeps its header comment`() {
        val source = parse("[app] # bh\ny = 2\n")
        val doc = parse("[app] # ah\nx = 1\n")
        doc.set("app", source["app"].getOrFail()).getOrFail()
        assertEquals("[app] # ah\ny = 2\n", doc.toString())
    }

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

    @Test
    fun `a key added after the last table is removed prints below the document's head`() {
        val doc = parse("# banner\n\n[db]\nurl = 1\n")
        doc.remove("db")
        doc["x"] = 1L
        assertEquals("# banner\n\nx = 1\n", doc.toString())
    }

    @Test
    fun `a comment given to an element puts a one-line array on lines of its own`() {
        val doc = parse("a = [1, 2]\n")
        val element = Value.Integer(3L).also { it.decor = Decor(suffix = " # c") }
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(element).getOrFail()
        assertEquals("a = [\n  1,\n  2,\n  3, # c\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to a value puts a one-line inline table on lines of its own`() {
        val doc = parse("t = { a = 1 }\n")
        val value = Value.Integer(2L).also { it.decor = Decor(suffix = " # c") }
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("b", value).getOrFail()
        assertEquals("t = {\n  a = 1,\n  b = 2, # c\n}\n", doc.toString())
    }

    @Test
    fun `a comment given to a value in a TOML 1_0 document leaves the inline table on one line`() {
        val doc = parse("t = { a = 1 }\n", TomlVersion.V1_0)
        val value = Value.Integer(2L).also { it.decor = Decor(suffix = " # c") }
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("b", value).getOrFail()
        assertEquals("t = { a = 1, b = 2 }\n", doc.toString())
    }

    @Test
    fun `a comment given to an element of an empty array puts it on a line of its own`() {
        val doc = parse("a = []\n")
        val element = Value.Integer(1L).also { it.decor = Decor(suffix = " # c") }
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(element).getOrFail()
        assertEquals("a = [\n  1, # c\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to an element of an array closed on its last line ends that line`() {
        val doc = parse("a = [\n  1,\n  2]\n")
        val element = Value.Integer(3L).also { it.decor = Decor(suffix = " # c") }
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(element).getOrFail()
        assertEquals("a = [\n  1,\n  2,\n  3, # c\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to a value set at a key in the middle prints after its comma`() {
        val doc = parse("t = { a = 1, b = 2 }\n")
        val value = Value.Integer(5L).also { it.decor = Decor(suffix = " # c") }
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("a", value).getOrFail()
        assertEquals("t = {\n  a = 5, # c\n  b = 2,\n}\n", doc.toString())
    }

    @Test
    fun `a comment given to an element in a document with CRLF line breaks spreads the array over CRLF lines`() {
        val doc = parse("a = [1, 2]\r\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(commented(3L)).getOrFail()
        assertEquals("a = [\r\n  1,\r\n  2,\r\n  3, # c\r\n]\r\n", doc.toString())
    }

    @Test
    fun `a comment given to an element of an empty array in a document with CRLF line breaks ends its line in CRLF`() {
        val doc = parse("a = []\r\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(commented(1L)).getOrFail()
        assertEquals("a = [\r\n  1, # c\r\n]\r\n", doc.toString())
    }

    @Test
    fun `a comment given to an element of an array in an inline table spreads it over CRLF lines`() {
        val doc = parse("t = { a = [1] }\r\n")
        val array = doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!["a"].getOrFail().arrayOrNull!!
        array.add(commented(2L)).getOrFail()
        assertEquals("t = { a = [\r\n  1,\r\n  2, # c\r\n] }\r\n", doc.toString())
    }

    @Test
    fun `a comment given to a value in a document with CRLF line breaks spreads the inline table over CRLF lines`() {
        val doc = parse("t = { a = 1 }\r\n")
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("b", commented(2L)).getOrFail()
        assertEquals("t = {\r\n  a = 1,\r\n  b = 2, # c\r\n}\r\n", doc.toString())
    }

    @Test
    fun `a comment given to a value set at a key in the middle of a CRLF document ends its line in CRLF`() {
        val doc = parse("t = { a = 1, b = 2 }\r\n")
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("a", commented(5L)).getOrFail()
        assertEquals("t = {\r\n  a = 5, # c\r\n  b = 2,\r\n}\r\n", doc.toString())
    }

    @Test
    fun `a table built in code made a value in a document with CRLF line breaks prints across CRLF lines`() {
        val doc = parse("x = 1\r\n")
        val table = StandardTable()
        table.set("k", commented(1L)).getOrFail()
        doc.set("t", table).getOrFail()
        doc["t"].getOrFail().setInline(true).getOrFail()
        assertEquals("x = 1\r\nt = {\r\n  k = 1, # c\r\n}\r\n", doc.toString())
    }

    @Test
    fun `an array of tables built in code made a value in a document with CRLF line breaks prints across CRLF lines`() {
        val doc = parse("x = 1\r\n")
        val first = StandardTable()
        first.set("k", commented(1L)).getOrFail()
        doc.set("s", listOf(first, StandardTable()).toArrayOfTables().getOrFail()).getOrFail()
        doc["s"].getOrFail().setInline(true).getOrFail()
        assertEquals("x = 1\r\ns = [\r\n  {\r\n    k = 1, # c\r\n  },\r\n  {},\r\n]\r\n", doc.toString())
    }

    @Test
    fun `a comment given to an element added at an index puts a one-line array on lines of its own`() {
        val doc = parse("a = [1, 2]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(0, commented(0L)).getOrFail()
        assertEquals("a = [\n  0, # c\n  1,\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to an element added in the middle of a multi-line array prints after its comma`() {
        val doc = parse("a = [\n  1,\n  2,\n]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(1, commented(5L)).getOrFail()
        assertEquals("a = [\n  1,\n  5, # c\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to an element added at an index in a document with CRLF line breaks ends its line in CRLF`() {
        val doc = parse("a = [1, 2]\r\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(1, commented(5L)).getOrFail()
        assertEquals("a = [\r\n  1,\r\n  5, # c\r\n  2,\r\n]\r\n", doc.toString())
    }

    @Test
    fun `a comment given to a replacing element puts a one-line array on lines of its own`() {
        val doc = parse("a = [1, 2]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.replace(0, commented(9L)).getOrFail()
        assertEquals("a = [\n  9, # c\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to an element replacing the last one prints after its comma`() {
        val doc = parse("a = [1, 2]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.replace(1, commented(9L)).getOrFail()
        assertEquals("a = [\n  1,\n  9, # c\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to an element replacing the last of a multi-line array without a trailing comma ends its line`() {
        val doc = parse("a = [\n  1,\n  2\n]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.replace(1, commented(9L)).getOrFail()
        assertEquals("a = [\n  1,\n  9, # c\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to a replacing element that keeps its own decor prints after its comma`() {
        val doc = parse("a = [1, 2]\n")
        val array = doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!
        array.replace(0, commented(9L), keepDecor = false).getOrFail()
        assertEquals("a = [\n  9, # c\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to a replacing element in a document with CRLF line breaks ends its line in CRLF`() {
        val doc = parse("a = [1, 2]\r\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.replace(0, commented(9L)).getOrFail()
        assertEquals("a = [\r\n  9, # c\r\n  2,\r\n]\r\n", doc.toString())
    }

    @Test
    fun `a comment given to a replacing element takes the place of the old element's comment`() {
        val doc = parse("a = [\n  1, # old\n  2,\n]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.replace(0, commented(9L)).getOrFail()
        assertEquals("a = [\n  9, # c\n  2,\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to an element replacing the last one takes the place of its comment`() {
        val doc = parse("a = [\n  1,\n  2 # old\n]\n")
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.replace(1, commented(9L)).getOrFail()
        assertEquals("a = [\n  1,\n  9, # c\n]\n", doc.toString())
    }

    @Test
    fun `a comment given to a value set at a key takes the place of the old value's comment`() {
        val doc = parse("t = {\n  a = 1, # old\n  b = 2\n}\n")
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("a", commented(5L)).getOrFail()
        assertEquals("t = {\n  a = 5, # c\n  b = 2\n}\n", doc.toString())
    }

    @Test
    fun `a comment given to a value set at the last key takes the place of the old value's comment`() {
        val doc = parse("t = {\n  a = 1,\n  b = 2 # old\n}\n")
        doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!.set("b", commented(5L)).getOrFail()
        assertEquals("t = {\n  a = 1,\n  b = 5 # c\n}\n", doc.toString())
    }

    @Test
    fun `a comment given to a value added at an index of an inline table's array spreads it in CRLF`() {
        val doc = parse("t = { a = [1, 2] }\r\n")
        val array = doc["t"].getOrFail().valueOrNull!!.inlineTableOrNull!!["a"].getOrFail().arrayOrNull!!
        array.add(0, commented(0L)).getOrFail()
        assertEquals("t = { a = [\r\n  0, # c\r\n  1,\r\n  2,\r\n] }\r\n", doc.toString())
    }

    @Test
    fun `an inline table made a standard table keeps its comments`() {
        val doc = parse("srv = {\n  host = \"h\", # host c\n  # above port\n  port = 1,\n}\n")
        doc.set("srv", doc["srv"].getOrFail().valueOrNull!!.inlineTableOrNull!!.toStandardTable()).getOrFail()
        assertEquals("[srv]\nhost = \"h\" # host c\n# above port\nport = 1\n", doc.toString())
    }

    @Test
    fun `an array of tables made a value keeps its comments in TOML 1_1`() {
        val doc = parse("# first\n[[s]] # ac\nn = 1 # one\n\n[[s]]\nn = 2\n")
        doc["s"].getOrFail().setInline(true).getOrFail()
        assertEquals("# first\ns = [\n  { # ac\n    n = 1, # one\n  },\n  { n = 2 },\n]\n", doc.toString())
    }

    @Test
    fun `an array of tables made a value in TOML 1_0 is refused while a comment inside a table would go`() {
        val text = "[[s]]\nn = 1 # one\n"
        val doc = parse(text, TomlVersion.V1_0)
        val error = doc["s"].getOrFail().setInline(true).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("s"), 1), error)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an array of tables made a value in TOML 1_0 with force keeps the comments on and between its headers`() {
        val doc = parse("# first\n[[s]] # ac\nn = 1 # one\n\n# second\n[[s]]\nn = 2\n", TomlVersion.V1_0)
        doc["s"].getOrFail().setInline(true, force = true).getOrFail()
        val expected = "# first\ns = [\n  { n = 1 }, # ac\n  # second\n  { n = 2 },\n]\n"
        assertEquals(expected, doc.toString())
        assertEquals(expected, parse(expected, TomlVersion.V1_0).toString())
    }

    @Test
    fun `an inline table made a standard table keeps the comments after its last value`() {
        val doc = parse("srv = { # open\n  port = 1 # pc\n  # end\n}\n")
        doc.set("srv", doc["srv"].getOrFail().valueOrNull!!.inlineTableOrNull!!.toStandardTable()).getOrFail()
        assertEquals("[srv] # open\nport = 1 # pc\n# end\n", doc.toString())
    }

    @Test
    fun `a removed table stored again in a document with CRLF line breaks prints the comment above it once`() {
        val text = "x = 1\r\n\r\n# about t\r\n[t]\r\nk = 1\r\n"
        val doc = parse(text)
        doc.set("t", doc.remove("t")!!).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a key added after the last key is removed prints below the comment above it`() {
        val doc = parse("# c\na = 1\n")
        doc.remove("a")
        doc["x"] = 1L
        assertEquals("# c\nx = 1\n", doc.toString())
    }

    private fun commented(value: Long): Value = Value.Integer(value).also { it.decor = Decor(suffix = " # c") }
}
