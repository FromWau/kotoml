package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.io.Buffer
import kotlinx.io.readString

/**
 * A document's [TomlDocument.version]: [TomlDocument.setVersion] converting it, and the edits a TOML 1.0 document
 * refuses for adding what only TOML 1.1 reads.
 */
class VersionTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    private fun written(doc: TomlDocument): String {
        val buffer = Buffer()
        doc.writeTo(buffer).assertSuccess()
        return buffer.readString()
    }

    /** Converts [text] to TOML 1.0, checks the result reads back as TOML 1.0, and returns what it prints. */
    private fun converted(text: String): String {
        val doc = parse(text)
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
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
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        val value = doc["t"].assertSuccess().valueOrNull!!
        assertEquals(0, value.datetimeOrNull!!.time!!.second)
        assertEquals("07:32:00", value.toString())
    }

    @Test
    fun `a time without seconds stored in a TOML 1_0 document reads its seconds`() {
        val doc = parse("a = [1]\nt = { x = 1 }\n[[l]]\n", TomlVersion.V1_0)
        val time = Value.Datetime(Datetime.LocalTime(Time.of(7, 32).assertSuccess()))
        doc.set("s", time).assertSuccess()
        doc["a"].assertSuccess().valueOrNull!!.arrayOrNull!!.add(time).assertSuccess()
        doc["t"].assertSuccess().inline().set("y", time).assertSuccess()
        doc["t"]["x"].assertSuccess().assign(Item(time)).assertSuccess()
        val table = StandardTable().apply { set("z", time).assertSuccess() }
        doc["l"].assertSuccess().arrayOfTablesOrNull!!.add(table).assertSuccess()
        val stored = listOf(doc["s"], doc["a"][1], doc["t"]["y"], doc["t"]["x"], doc["l"][1]["z"])
        for (slot in stored) assertEquals(0, slot.assertSuccess().valueOrNull!!.datetimeOrNull!!.time!!.second)
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
        val error = doc.setVersion(TomlVersion.V1_0).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a", "t"), 2), error)
        assertEquals(text, doc.toString())
        assertEquals(TomlVersion.V1_1, doc.version)
        assertNull(doc["t0"].assertSuccess().valueOrNull!!.datetimeOrNull!!.time!!.second)
        assertEquals("\"\\e\"", doc["s"].assertSuccess().valueOrNull!!.toString())
    }

    @Test
    fun `an inline table across lines with comments goes on one line when forced`() {
        val doc = parse("[a]\nt = { # one\n  x = 1, # two\n} # three\n")
        doc.setVersion(TomlVersion.V1_0, force = true).assertSuccess()
        assertEquals("[a]\nt = { x = 1 } # three\n", doc.toString())
        assertEquals(TomlVersion.V1_0, doc.version)
    }

    @Test
    fun `an inline table across lines keeps its header comment after the braces`() {
        val doc = parse("[a]\nt = { # one\n  x = 1,\n}\nl = [\n  { # two\n    y = 1,\n  },\n]\n")
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        assertEquals("[a]\nt = { x = 1 } # one\nl = [\n  { y = 1 }, # two\n]\n", doc.toString())
    }

    @Test
    fun `an inline table across lines drops the header comments of the inline tables inside when forced`() {
        val doc = parse("t = { # one\n  u = { # two\n    x = 1,\n  },\n}\n")
        val error = doc.setVersion(TomlVersion.V1_0).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        doc.setVersion(TomlVersion.V1_0, force = true).assertSuccess()
        assertEquals("t = { u = { x = 1 } } # one\n", doc.toString())
    }

    @Test
    fun `a handle to a converted inline table stays in the document`() {
        val doc = parse("t = {\n  a = 1,\n}\n")
        val table = doc["t"].assertSuccess().inline()
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        table.set("b", Value.Integer(2L)).assertSuccess()
        assertSame(table, doc["t"].assertSuccess().inline())
        assertEquals("t = { a = 1, b = 2 }\n", doc.toString())
    }

    @Test
    fun `a value removed from a converted document keeps its TOML 1_0 spelling`() {
        val doc = parse("s = \"\\e\"\n")
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        assertEquals("\"\\u001B\"", doc.remove("s").assertSuccess().valueOrNull!!.toString())
    }

    @Test
    fun `a table removed from a TOML 1_0 document takes edits as that document does`() {
        val doc = parse("[a]\nt = { x = 1 }\n", TomlVersion.V1_0)
        val removed = doc.remove("a").assertSuccess()
        val inline = removed["t"].assertSuccess().inline()
        val error = inline.setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.InlineTableTrailingComma), error)
        removed["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        val time = Value.Datetime(Datetime.LocalTime(Time.of(7, 32).assertSuccess()))
        removed.standardTableOrNull!!.set("u", time).assertSuccess()
        assertEquals("07:32:00", (removed["u"].assertSuccess().valueOrNull as Value.Datetime).displaySpelling)
        assertEquals("t = { x = 1 }\nu = 07:32:00\n", removed.toString())
    }

    @Test
    fun `a table removed from a TOML 1_0 document takes TOML 1_1 edits once stored in a TOML 1_1 document`() {
        val removed = parse("[a]\nt = { x = 1 }\n", TomlVersion.V1_0).remove("a").assertSuccess()
        val doc = parse("b = 1\n")
        doc["a"] = removed
        doc["a"]["t"].assertSuccess().inline().setTrailingComma(true).assertSuccess()
        doc["a"]["u"] = Value.Datetime(Datetime.LocalTime(Time.of(7, 32).assertSuccess()))
        assertEquals("b = 1\n\n[a]\nt = { x = 1 ,}\nu = 07:32\n", doc.toString())
    }

    @Test
    fun `a value detached from a TOML 1_0 document by a replace takes edits as that document does`() {
        val doc = parse("t = { x = 1 }\nl = [{ y = 1 }]\n", TomlVersion.V1_0)
        val bySet = doc["t"].assertSuccess().inline()
        val byReplace = doc["l"][0].assertSuccess().valueOrNull!!.inlineTableOrNull!!
        doc["t"] = 1L
        doc["l"].assertSuccess().arrayOrNull!!.replace(0, Value.Integer(2L)).assertSuccess()
        bySet.setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        byReplace.setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        assertEquals("t = 1\nl = [2]\n", doc.toString())
    }

    @Test
    fun `a copy of a table in a TOML 1_0 document takes edits as that document does`() {
        val doc = parse("t = { x = 1 }\n", TomlVersion.V1_0)
        doc["t"].assertSuccess().inline().copy().setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        doc["t"].assertSuccess().copy().inline().setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        doc.root.copy()["t"].assertSuccess().inline().setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
    }

    @Test
    fun `a table built in code takes TOML 1_1 edits`() {
        val table = inlineTableOf("x" to Value.Integer(1L)).assertSuccess()
        table.setTrailingComma(true).assertSuccess()
        val time = Value.Datetime(Datetime.LocalTime(Time.of(7, 32).assertSuccess()))
        assertEquals("07:32", time.displaySpelling)
        assertEquals("07:32", time.toString())
        assertEquals("{ x = 1 ,}", table.toString())
    }

    @Test
    fun `a document made from a table removed from a TOML 1_0 document holds TOML 1_0`() {
        val removed = parse("[a]\nx = 1\n", TomlVersion.V1_0).remove("a").assertSuccess().standardTableOrNull!!
        assertEquals(TomlVersion.V1_0, TomlDocument(removed).version)
        val built = StandardTable()
        assertEquals(TomlVersion.V1_1, TomlDocument(built).version)
    }

    @Test
    fun `a time without seconds displays as it prints in the version of its document`() {
        val doc = TomlDocument()
        doc["t"] = Value.Datetime(Datetime.LocalTime(Time.of(7, 32).assertSuccess()))
        val time = doc["t"].assertSuccess().valueOrNull as Value.Datetime
        assertEquals("07:32", time.displaySpelling)
        assertEquals("07:32", time.toString())
        assertEquals("t = 07:32\n", doc.toString())
        // No public edit leaves such a time in a TOML 1.0 document, so the version is set without converting it.
        doc.root.version = TomlVersion.V1_0
        assertEquals("07:32:00", time.displaySpelling)
        assertEquals("07:32:00", time.toString())
        assertEquals("t = 07:32:00\n", doc.toString())
        assertNull(time.spelling)
    }

    @Test
    fun `a TOML 1_0 document set to TOML 1_0 changes nothing`() {
        val text = "# c\nt = { a = 1 }\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a document set to TOML 1_1 changes nothing and takes TOML 1_1 edits`() {
        val text = "t = { a = 1 } # c\nd = 07:32:00\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc.setVersion(TomlVersion.V1_1).assertSuccess()
        assertEquals(TomlVersion.V1_1, doc.version)
        assertEquals(text, doc.toString())
        doc["t"].assertSuccess().inline().setTrailingComma(true).assertSuccess()
        doc["e"] = Value.parse("\"\\e\"").assertSuccess()
        assertEquals("t = { a = 1 ,} # c\nd = 07:32:00\ne = \"\\e\"\n", written(doc))
    }

    @Test
    fun `a document set to TOML 1_0 and back keeps the TOML 1_0 spelling`() {
        val doc = parse("s = \"\\e\"\n")
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        doc.setVersion(TomlVersion.V1_1).assertSuccess()
        assertEquals("s = \"\\u001B\"\n", doc.toString())
    }

    @Test
    fun `a table and a value print what the converted document holds`() {
        val doc = parse("[a]\nt = 07:32\ns = \"\\e\"\nl = [\"\\x41\"]\n")
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        assertEquals("t = 07:32:00\ns = \"\\u001B\"\nl = [\"\\u0041\"]\n", doc["a"].assertSuccess().toString())
        assertEquals("\"\\u0041\"", doc["a"]["l"][0].assertSuccess().valueOrNull!!.toString())
    }

    @Test
    fun `a table set across lines in a TOML 1_0 document is refused`() {
        val text = "# c\n[t] # t\na = 1 # a\n"
        val doc = parse(text, TomlVersion.V1_0)
        val error = doc["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), error)
        assertEquals(text, doc.toString())
    }

    @Test
    fun `an inline table set across lines in a TOML 1_0 document is refused`() {
        val doc = parse("[a]\nt = { x = 1 }\nl = [{ y = 1 }]\n", TomlVersion.V1_0)
        val inTable = doc["a"]["t"].assertSuccess().setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("a", "t"), Toml11Feature.MultilineInlineTable), inTable)
        val inArray = doc["a"]["l"].assertSuccess().setLayout(TableLayout.MultilineInline).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("a", "l"), Toml11Feature.MultilineInlineTable), inArray)
        assertEquals("[a]\nt = { x = 1 }\nl = [{ y = 1 }]\n", doc.toString())
    }

    @Test
    fun `a table set on one line in a TOML 1_0 document is written`() {
        val doc = parse("[t] # t\na = 1\n", TomlVersion.V1_0)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { a = 1 } # t\n", written(doc))
    }

    @Test
    fun `a trailing comma in a TOML 1_0 document is refused`() {
        val doc = parse("[a]\nt = { x = 1 }\n", TomlVersion.V1_0)
        val table = doc["a"]["t"].assertSuccess().inline()
        val error = table.setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("a", "t"), Toml11Feature.InlineTableTrailingComma), error)
        assertEquals(false, table.trailingComma)
        table.setTrailingComma(false).assertSuccess()
        assertEquals("[a]\nt = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a trailing comma in a TOML 1_1 document prints`() {
        val doc = parse("t = { x = 1 }\n")
        doc["t"].assertSuccess().inline().setTrailingComma(true).assertSuccess()
        assertEquals("t = { x = 1 ,}\n", doc.toString())
    }

    @Test
    fun `a string spelled with a TOML 1_1 escape is refused in a TOML 1_0 document`() {
        val doc = parse("a = [1]\nt = { x = 1 }\n", TomlVersion.V1_0)
        val escaped = Value.parse("\"\\e\"").assertSuccess()
        val stored = doc.set("s", escaped).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("s"), Toml11Feature.EscapeEscape), stored)
        val array = doc["a"].assertSuccess().valueOrNull!!.arrayOrNull!!
        val added = array.add(escaped).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("a"), Toml11Feature.EscapeEscape), added)
        val table = doc["t"].assertSuccess().inline()
        val inTable = table.set("y", escaped).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "y"), Toml11Feature.EscapeEscape), inTable)
        val assigned = doc["t"]["x"].assertSuccess().assign(Item(escaped)).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "x"), Toml11Feature.EscapeEscape), assigned)
        assertEquals("a = [1]\nt = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a key spelled with a TOML 1_1 escape is refused in a TOML 1_0 document`() {
        val doc = parse("t = { x = 1 }\n", TomlVersion.V1_0)
        val key = Key.parse("\"\\x41\"").assertSuccess()
        val error = doc.put(key, Item(Value.Integer(1L))).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("A"), Toml11Feature.HexEscape), error)
        val inTable = doc["t"].assertSuccess().inline().put(key, Value.Integer(1L)).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "A"), Toml11Feature.HexEscape), inTable)
        assertEquals("t = { x = 1 }\n", doc.toString())
    }

    @Test
    fun `a table holding what only TOML 1_1 reads is refused in a TOML 1_0 document`() {
        val source = parse("[t]\nk = 1\n[t.u]\n\"\\e\" = 1\n")
        val doc = parse("[[x]]\n", TomlVersion.V1_0)
        val table = source["t"].assertSuccess().standardTableOrNull!!
        assertEquals(
            TomlEditError.NotInToml10(listOf("y", "u", "\u001B"), Toml11Feature.EscapeEscape),
            doc.set("y", table).assertError<TomlEditError.NotInToml10>(),
        )
        val tables = doc["x"].assertSuccess().arrayOfTablesOrNull!!
        assertEquals(
            TomlEditError.NotInToml10(listOf("x", "u", "\u001B"), Toml11Feature.EscapeEscape),
            tables.add(table).assertError<TomlEditError.NotInToml10>(),
        )
        assertEquals("[[x]]\n", doc.toString())
    }

    @Test
    fun `an inline table across lines or with a trailing comma is refused in a TOML 1_0 document`() {
        val doc = parse("a = 1\n", TomlVersion.V1_0)
        val across = Value.parse("{\n  x = 1,\n}").assertSuccess()
        val acrossError = doc.set("t", Value.Array(tomlArrayOf(across).assertSuccess())).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t"), Toml11Feature.MultilineInlineTable), acrossError)
        val comma = Value.parse("{ x = { y = 1, } }").assertSuccess()
        val commaError = doc.set("t", comma).assertError<TomlEditError.NotInToml10>()
        assertEquals(TomlEditError.NotInToml10(listOf("t", "x"), Toml11Feature.InlineTableTrailingComma), commaError)
        assertEquals("a = 1\n", doc.toString())
    }

    @Test
    fun `a time without seconds stored in a TOML 1_0 document prints its seconds`() {
        val doc = parse("a = [1]\n", TomlVersion.V1_0)
        doc["t"] = Value.parse("07:32").assertSuccess()
        doc["a"].assertSuccess().valueOrNull!!.arrayOrNull!!.add(Value.parse("1979-05-27T07:32").assertSuccess()).assertSuccess()
        val expected = "a = [1, 1979-05-27T07:32:00]\nt = 07:32:00\n"
        assertEquals(expected, written(doc))
        parse(expected, TomlVersion.V1_0)
    }

    @Test
    fun `a TOML 1_1 spelling stored in a TOML 1_1 document is kept`() {
        val doc = parse("a = 1\n")
        doc["s"] = Value.parse("\"\\e\"").assertSuccess()
        doc["t"] = Value.parse("{\n  x = 1,\n}").assertSuccess()
        assertEquals("a = 1\ns = \"\\e\"\nt = {\n  x = 1,\n}\n", written(doc))
    }

    @Test
    fun `a refused edit reads as a sentence naming the key`() {
        val doc = parse("point = { x = 1 }\n", TomlVersion.V1_0)
        val error = doc["point"].assertSuccess().inline().setTrailingComma(true).assertError<TomlEditError.NotInToml10>()
        assertEquals("point: inline tables cannot end with a comma in TOML 1.0", error.message)
    }
}
