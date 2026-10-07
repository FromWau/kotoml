package com.fromwau.kotoml

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A document's [TomlDocument.version]: [TomlDocument.setVersion] converting it, and the edits a TOML 1.0 document
 * refuses for adding what only TOML 1.1 reads.
 */
class VersionTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).getOrFail()

    private fun written(doc: TomlDocument): String {
        val buffer = Buffer()
        doc.writeTo(buffer).getOrFail()
        return buffer.readString()
    }

    /** Converts [text] to TOML 1.0, checks the result reads back as TOML 1.0, and returns what it prints. */
    private fun converted(text: String): String {
        val doc = parse(text)
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        assertEquals(TomlVersion.V1_0, doc.version)
        val printed = written(doc)
        assertEquals(printed, doc.toString())
        parse(printed, TomlVersion.V1_0)
        return printed
    }

    private fun Item.inline(): InlineTable = valueOrNull!!.inlineTableOrNull!!

    @Test
    fun `a document parsed as TOML 1_0 reads TOML 1_0 and one parsed as TOML 1_1 reads TOML 1_1`() {
        assertEquals(TomlVersion.V1_0, parse("a = 1\n", TomlVersion.V1_0).version)
        assertEquals(TomlVersion.V1_1, parse("a = 1\n").version)
    }

    @Test
    fun `a new document reads TOML 1_1`() {
        assertEquals(TomlVersion.V1_1, TomlDocument().version)
    }

    @Test
    fun `a copy and a document made from a root keep the version`() {
        val doc = parse("a = 1\n", TomlVersion.V1_0)
        assertEquals(TomlVersion.V1_0, doc.copy().version)
        assertEquals(TomlVersion.V1_0, TomlDocument(doc.root).version)
        assertEquals(TomlVersion.V1_1, TomlDocument(StandardTable()).version)
    }

    @Test
    fun `times without seconds take seconds`() {
        val text = "t = 07:32\nd = 1979-05-27 07:32Z\nl = 1979-05-27T07:32\nn = 07:32:05\n"
        val expected = "t = 07:32:00\nd = 1979-05-27 07:32:00Z\nl = 1979-05-27T07:32:00\nn = 07:32:05\n"
        assertEquals(expected, converted(text))
    }

    @Test
    fun `a converted time reads its seconds`() {
        val doc = parse("t = 07:32\n")
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        val value = doc["t"].getOrFail().valueOrNull!!
        assertEquals(0, value.datetimeOrNull!!.time!!.second)
        assertEquals("07:32:00", value.toString())
    }

    @Test
    fun `a time without seconds stored in a TOML 1_0 document reads its seconds`() {
        val doc = parse("a = [1]\nt = { x = 1 }\n[[l]]\n", TomlVersion.V1_0)
        val time = Value.Datetime(Datetime.LocalTime(Time.of(7, 32).getOrFail()))
        doc.set("s", time).getOrFail()
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(time).getOrFail()
        doc["t"].getOrFail().inline().set("y", time).getOrFail()
        doc["t"]["x"].getOrFail().assign(Item(time)).getOrFail()
        val table = StandardTable().apply { set("z", time).getOrFail() }
        doc["l"].getOrFail().arrayOfTablesOrNull!!.add(table).getOrFail()
        val stored = listOf(doc["s"], doc["a"][1], doc["t"]["y"], doc["t"]["x"], doc["l"][1]["z"])
        for (slot in stored) assertEquals(0, slot.getOrFail().valueOrNull!!.datetimeOrNull!!.time!!.second)
        assertNull(time.value.time!!.second)
        val expected = "a = [1, 07:32:00]\nt = { x = 07:32:00, y = 07:32:00 }\ns = 07:32:00\n" +
            "[[l]]\n\n[[l]]\nz = 07:32:00\n"
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `times in arrays and inline tables take seconds`() {
        assertEquals("a = [07:32:00, { t = 08:15:00 }]\n", converted("a = [07:32, { t = 08:15 }]\n"))
    }

    @Test
    fun `strings spelled with a TOML 1_1 escape take the TOML 1_0 escape for it`() {
        val text = "s = \"\\x41\\e\"\nm = \"\"\"a\\eb\\\\e\"\"\"\nplain = \"\\\\e\"\nliteral = '\\e'\n"
        val expected = "s = \"\\u0041\\u001B\"\nm = \"\"\"a\\u001Bb\\\\e\"\"\"\nplain = \"\\\\e\"\nliteral = '\\e'\n"
        assertEquals(expected, converted(text))
    }

    @Test
    fun `keys spelled with a TOML 1_1 escape take the TOML 1_0 escape for it`() {
        val text = "\"k\\e\" = 1\na.\"\\x41\".b = 2\n[[t.\"\\x42\"]]\nk = 1\n[ t . \"\\x42\" . c ]\nd = 1\n"
        val expected =
            "\"k\\u001B\" = 1\na.\"\\u0041\".b = 2\n[[t.\"\\u0042\"]]\nk = 1\n[ t . \"\\u0042\" . c ]\nd = 1\n"
        assertEquals(expected, converted(text))
    }

    @Test
    fun `a key in an inline table spelled with a TOML 1_1 escape takes the TOML 1_0 escape for it`() {
        val expected = "t = { \"\\u001B\" = 1, a.\"\\u0041\" = 2 }\n"
        assertEquals(expected, converted("t = { \"\\e\" = 1, a.\"\\x41\" = 2 }\n"))
    }

    @Test
    fun `an inline table across lines without comments goes on one line`() {
        assertEquals("t = { a = 1, b = { c = 2 } }\n", converted("t = {\n  a = 1,\n  b = {\n    c = 2,\n  },\n}\n"))
    }

    @Test
    fun `inline tables across lines in an array go on one line`() {
        assertEquals("a = [{ x = 1 }, [{ y = 2 }]]\n", converted("a = [{\n  x = 1\n}, [{\n  y = 2,\n}]]\n"))
    }

    @Test
    fun `an array across lines in an inline table stays across lines`() {
        val text = "t = { a = [\n  1, # one\n  2,\n] }\n"
        assertEquals(text, converted(text))
    }

    @Test
    fun `trailing commas in inline tables go`() {
        assertEquals("point = { x = 1 }\na = [{ y = 2}]\n", converted("point = { x = 1, }\na = [{ y = 2,}]\n"))
    }

    @Test
    fun `a trailing comma in an array stays`() {
        assertEquals("a = [1, 2,]\n", converted("a = [1, 2,]\n"))
    }

    @Test
    fun `an inline table across lines with comments is refused naming it and every comment the change drops`() {
        val text = "s = \"\\e\"\nt0 = 07:32\n[a]\nt = { # one\n  x = 1, # two\n}\nu = {\n  y = 1, # three\n}\n"
        val doc = parse(text)
        val error = doc.setVersion(TomlVersion.V1_0).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a", "t"), 2), error)
        assertEquals(text, doc.toString())
        assertEquals(TomlVersion.V1_1, doc.version)
        assertNull(doc["t0"].getOrFail().valueOrNull!!.datetimeOrNull!!.time!!.second)
        assertEquals("\"\\e\"", doc["s"].getOrFail().valueOrNull!!.toString())
    }

    @Test
    fun `an inline table across lines with comments goes on one line when forced`() {
        val doc = parse("[a]\nt = { # one\n  x = 1, # two\n} # three\n")
        doc.setVersion(TomlVersion.V1_0, force = true).getOrFail()
        assertEquals("[a]\nt = { x = 1 } # three\n", doc.toString())
        assertEquals(TomlVersion.V1_0, doc.version)
    }

    @Test
    fun `an inline table across lines keeps its header comment after the braces`() {
        val doc = parse("[a]\nt = { # one\n  x = 1,\n}\nl = [\n  { # two\n    y = 1,\n  },\n]\n")
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        assertEquals("[a]\nt = { x = 1 } # one\nl = [\n  { y = 1 }, # two\n]\n", doc.toString())
    }

    @Test
    fun `an inline table across lines drops the header comments of the inline tables inside when forced`() {
        val doc = parse("t = { # one\n  u = { # two\n    x = 1,\n  },\n}\n")
        val error = doc.setVersion(TomlVersion.V1_0).errorOrFail()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc.setVersion(TomlVersion.V1_0, force = true).getOrFail()
        assertEquals("t = { u = { x = 1 } } # one\n", doc.toString())
    }

    @Test
    fun `a handle to a converted inline table stays in the document`() {
        val doc = parse("t = {\n  a = 1,\n}\n")
        val table = doc["t"].getOrFail().inline()
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        table.set("b", Value.Integer(2L)).getOrFail()
        assertSame(table, doc["t"].getOrFail().inline())
        assertEquals("t = { a = 1, b = 2 }\n", doc.toString())
    }

    @Test
    fun `a value removed from a converted document keeps its TOML 1_0 spelling`() {
        val doc = parse("s = \"\\e\"\n")
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        assertEquals("\"\\u001B\"", doc.remove("s")!!.valueOrNull!!.toString())
    }

    @Test
    fun `a TOML 1_0 document set to TOML 1_0 changes nothing`() {
        val text = "# c\nt = { a = 1 }\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a document set to TOML 1_1 changes nothing and takes TOML 1_1 edits`() {
        val text = "t = { a = 1 } # c\nd = 07:32:00\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc.setVersion(TomlVersion.V1_1).getOrFail()
        assertEquals(TomlVersion.V1_1, doc.version)
        assertEquals(text, doc.toString())
        doc["t"].getOrFail().inline().setTrailingComma(true).getOrFail()
        doc["e"] = Value.parse("\"\\e\"").getOrFail()
        assertEquals("t = { a = 1 ,} # c\nd = 07:32:00\ne = \"\\e\"\n", written(doc))
    }

    @Test
    fun `a document set to TOML 1_0 and back keeps the TOML 1_0 spelling`() {
        val doc = parse("s = \"\\e\"\n")
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        doc.setVersion(TomlVersion.V1_1).getOrFail()
        assertEquals("s = \"\\u001B\"\n", doc.toString())
    }

    @Test
    fun `a table and a value print what the converted document holds`() {
        val doc = parse("[a]\nt = 07:32\ns = \"\\e\"\nl = [\"\\x41\"]\n")
        doc.setVersion(TomlVersion.V1_0).getOrFail()
        assertEquals("t = 07:32:00\ns = \"\\u001B\"\nl = [\"\\u0041\"]\n", doc["a"].getOrFail().toString())
        assertEquals("\"\\u0041\"", doc["a"]["l"][0].getOrFail().valueOrNull!!.toString())
    }

    @Test
    fun `a table set across lines in a TOML 1_0 document is refused`() {
        val text = "# c\n[t] # t\na = 1 # a\n"
        val doc = parse(text, TomlVersion.V1_0)
        val error = doc["t"].getOrFail().setLayout(TableLayout.MultilineInline).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table set across lines in a TOML 1_0 document is refused`() {
        val doc = parse("[a]\nt = { x = 1 }\nl = [{ y = 1 }]\n", TomlVersion.V1_0)
        val inTable = doc["a"]["t"].getOrFail().setLayout(TableLayout.MultilineInline).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("a", "t"), Toml11Feature.MultilineInlineTable), inTable)
        val inArray = doc["a"]["l"].getOrFail().setLayout(TableLayout.MultilineInline).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("a", "l"), Toml11Feature.MultilineInlineTable), inArray)
        assertEquals("[a]\nt = { x = 1 }\nl = [{ y = 1 }]\n", doc.toString())
    }

    @Test
    fun `a table set on one line in a TOML 1_0 document is written`() {
        val doc = parse("[t] # t\na = 1\n", TomlVersion.V1_0)
        doc["t"].getOrFail().setLayout(TableLayout.Inline).getOrFail()
        assertEquals("t = { a = 1 } # t\n", written(doc))
    }

    @Test
    fun `a trailing comma in a TOML 1_0 document is refused`() {
        val doc = parse("[a]\nt = { x = 1 }\n", TomlVersion.V1_0)
        val table = doc["a"]["t"].getOrFail().inline()
        val error = table.setTrailingComma(true).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("a", "t"), Toml11Feature.InlineTableTrailingComma), error)
        assertEquals(false, table.trailingComma)
        table.setTrailingComma(false).getOrFail()
        assertEquals("[a]\nt = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a trailing comma in a TOML 1_1 document prints`() {
        val doc = parse("t = { x = 1 }\n")
        doc["t"].getOrFail().inline().setTrailingComma(true).getOrFail()
        assertEquals("t = { x = 1 ,}\n", doc.toString())
    }

    @Test
    fun `a string spelled with a TOML 1_1 escape is refused in a TOML 1_0 document`() {
        val doc = parse("a = [1]\nt = { x = 1 }\n", TomlVersion.V1_0)
        val escaped = Value.parse("\"\\e\"").getOrFail()
        val stored = doc.set("s", escaped).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("s"), Toml11Feature.EscapeEscape), stored)
        val array = doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!
        val added = array.add(escaped).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("a"), Toml11Feature.EscapeEscape), added)
        val table = doc["t"].getOrFail().inline()
        val inTable = table.set("y", escaped).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "y"), Toml11Feature.EscapeEscape), inTable)
        val assigned = doc["t"]["x"].getOrFail().assign(Item(escaped)).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "x"), Toml11Feature.EscapeEscape), assigned)
        assertEquals("a = [1]\nt = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a key spelled with a TOML 1_1 escape is refused in a TOML 1_0 document`() {
        val doc = parse("t = { x = 1 }\n", TomlVersion.V1_0)
        val key = Key.parse("\"\\x41\"").getOrFail()
        val error = doc.put(key, Item(Value.Integer(1L))).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("A"), Toml11Feature.HexEscape), error)
        val inTable = doc["t"].getOrFail().inline().put(key, Value.Integer(1L)).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "A"), Toml11Feature.HexEscape), inTable)
        assertEquals("t = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a table holding what only TOML 1_1 reads is refused in a TOML 1_0 document`() {
        val source = parse("[t]\nk = 1\n[t.u]\n\"\\e\" = 1\n")
        val doc = parse("[[x]]\n", TomlVersion.V1_0)
        val table = source["t"].getOrFail().standardTableOrNull!!
        assertEquals(
            TomlEditError.NotInToml10(listOf("y", "u", "\u001B"), Toml11Feature.EscapeEscape),
            doc.set("y", table).errorOrFail(),
        )
        val tables = doc["x"].getOrFail().arrayOfTablesOrNull!!
        assertEquals(
            TomlEditError.NotInToml10(listOf("x", "u", "\u001B"), Toml11Feature.EscapeEscape),
            tables.add(table).errorOrFail(),
        )
        assertEquals("[[x]]\n", doc.toString())
    }

    @Test
    fun `an inline table across lines or with a trailing comma is refused in a TOML 1_0 document`() {
        val doc = parse("a = 1\n", TomlVersion.V1_0)
        val across = Value.parse("{\n  x = 1,\n}").getOrFail()
        val acrossError = doc.set("t", Value.Array(tomlArrayOf(across).getOrFail())).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), acrossError)
        val comma = Value.parse("{ x = { y = 1, } }").getOrFail()
        val commaError = doc.set("t", comma).errorOrFail()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "x"), Toml11Feature.InlineTableTrailingComma), commaError)
        assertEquals("a = 1\n", doc.toString())
    }

    @Test
    fun `a time without seconds stored in a TOML 1_0 document prints its seconds`() {
        val doc = parse("a = [1]\n", TomlVersion.V1_0)
        doc["t"] = Value.parse("07:32").getOrFail()
        doc["a"].getOrFail().valueOrNull!!.arrayOrNull!!.add(Value.parse("1979-05-27T07:32").getOrFail()).getOrFail()
        val expected = "a = [1, 1979-05-27T07:32:00]\nt = 07:32:00\n"
        assertEquals(expected, written(doc))
        parse(expected, TomlVersion.V1_0)
    }

    @Test
    fun `a TOML 1_1 spelling stored in a TOML 1_1 document is kept`() {
        val doc = parse("a = 1\n")
        doc["s"] = Value.parse("\"\\e\"").getOrFail()
        doc["t"] = Value.parse("{\n  x = 1,\n}").getOrFail()
        assertEquals("a = 1\ns = \"\\e\"\nt = {\n  x = 1,\n}\n", written(doc))
    }

    @Test
    fun `a refused edit reads as a sentence naming the key`() {
        val doc = parse("point = { x = 1 }\n", TomlVersion.V1_0)
        val error = doc["point"].getOrFail().inline().setTrailingComma(true).errorOrFail()
        assertEquals("point: inline tables cannot end with a comma in TOML 1.0", error.message)
    }
}
