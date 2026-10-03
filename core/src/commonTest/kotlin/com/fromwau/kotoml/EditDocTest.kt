package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Upstream's documentation examples for `toml_edit`, which `cargo test` runs as doctests. */
class EditDocTest {

    @Test
    fun crateExample() {
        val toml = """
"hello" = 'toml!' # comment
['a'.b]
"""
        val doc = TomlDocument.parse(toml).unwrap()
        assertEquals(toml, doc.toString())
        // Add a new key/value pair inside a.b: c = {d = "hello"}
        doc["a"]["b"]["c"]["d"] = "hello"
        // Autoformat inline table a.b.c: { d = "hello" }
        doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
        val expected = """
"hello" = 'toml!' # comment
['a'.b]
c = { d = "hello" }
"""
        assertEquals(expected, doc.toString())
    }

    @Test
    fun readmeExample() {
        val toml = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n    "
        val doc = TomlDocument.parse(toml).unwrap()
        assertEquals(toml, doc.toString())
        // Add a new key/value pair inside a.b: c = {d = "hello"}
        doc["a"]["b"]["c"]["d"] = "hello"
        // Autoformat inline table a.b.c: { d = "hello" }
        doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
        val expected = "\n\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n    "
        assertEquals(expected, doc.toString())
    }

    @Test
    fun repositoryReadmeExample() {
        val doc = TomlDocument.parse("[package]\nname = \"kotoml\" # the name\n").unwrap()
        doc["package"]["version"] = "0.1.0"
        assertEquals("[package]\nname = \"kotoml\" # the name\nversion = \"0.1.0\"\n", doc.toString())
    }

    @Test
    fun moduleReadmeExample() {
        val text = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n"
        val doc = TomlDocument.parse(text).unwrap()
        assertEquals(text, doc.toString())
        doc["a"]["b"]["c"]["d"] = "hello"
        doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
        assertEquals("\n\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n", doc.toString())
    }

    @Test
    fun moduleReadmeValues() {
        val doc = TomlDocument()
        doc["port"] = Value.Integer(8080L, Decor(prefix = " ", suffix = " # default"))
        doc["when"] = Datetime(date = Date(year = 1979, month = 5, day = 27))
        assertEquals("port = 8080 # default\nwhen = 1979-05-27\n", doc.toString())

        assertEquals(8080L, doc["port"].longOrNull)
        val parsed = TomlDocument.parse("n = 0x10\n").unwrap()
        val spelled = parsed["n"].valueOrNull as Value.Integer
        assertEquals(16L, spelled.value)
        assertEquals("0x10", spelled.spelling)
        spelled.resetFormatting()
        assertEquals("16", spelled.displaySpelling)
    }

    @Test
    fun moduleReadmeTablesAndArrays() {
        val doc = TomlDocument.parse("[server]\nhosts = [\"alpha\"]\n").unwrap()
        doc["server"]["hosts"].arrayOrNull?.add("beta")
        val server = doc["server"].tableOrNull!!
        server.getOrPut("port") { Item(Value.Integer(8080L)) }
        doc["client"] = tableOf("retries" to Item(Value.Integer(3L)))
        assertEquals(
            "[server]\nhosts = [\"alpha\", \"beta\"]\nport = 8080\n\n[client]\nretries = 3\n",
            doc.toString(),
        )
    }

    @Test
    fun moduleReadmeSetIntoAString() {
        val doc = TomlDocument.parse("a = 'text'\n").unwrap()
        assertTrue(doc["a"].set("key", 1L).unwrapErr() is TomlEditError.NotATable)
        assertEquals("a = 'text'\n", doc.toString())
    }

    @Test
    fun moduleReadmeReadAndWrite() {
        val file = Buffer().apply { writeString("[package]\nname = \"kotoml\" # the name\nversion = \"0.1.0\"\n") }
        val read = file.use { TomlDocument.read(it) }
        val doc = when (read) {
            is Result.Success -> read.value
            is Result.Error -> fail(
                when (val error = read.error) {
                    is TomlIoError -> "cannot read: ${error.detail}"
                    is TomlReadError.Parse -> error.error.render(error.text)
                },
            )
        }
        doc["package"]["version"] = "0.2.0"
        val written = Buffer()
        doc.writeTo(written).unwrap()
        assertEquals("[package]\nname = \"kotoml\" # the name\nversion = \"0.2.0\"\n", written.readString())
    }

    @Test
    fun moduleReadmeError() {
        val input = "a = 1\na = 2\n"
        val error = TomlDocument.parse(input).unwrapErr()
        assertEquals(TomlParseError.Reason.DuplicateKey("a"), error.reason)
        assertEquals(TextPosition(line = 2, column = 1), error.position)
        assertData(
            """
TOML parse error at line 2, column 1
  |
2 | a = 2
  | ^
duplicate key

""",
            error.render(input),
        )
    }

    @Test
    fun crateDefaultFormatting() {
        val doc = TomlDocument()
        doc["foo"] = "bar"
        assertEquals("foo = \"bar\"\n", doc.toString())
    }

    @Test
    fun crateCustomRepresentation() {
        val doc = TomlDocument()
        doc["foo"] = Value.parse("'bar'").unwrap()
        assertEquals("foo = 'bar'\n", doc.toString())
    }

    @Test
    fun arrayNew() {
        TomlArray()
    }

    @Test
    fun arrayLen() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")
        assertEquals(2, arr.size)
    }

    @Test
    fun arrayIsEmpty() {
        val arr = TomlArray()
        assertTrue(arr.isEmpty())

        arr.add(1L)
        arr.add("foo")
        assertTrue(!arr.isEmpty())
    }

    @Test
    fun arrayPush() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")
    }

    @Test
    fun arrayPushFormatted() {
        val formattedValue = Value.parse("'literal'").unwrap()
        val arr = TomlArray()
        arr.add(formattedValue)
    }

    @Test
    fun arrayInsert() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.add(0, Value.String("start")).unwrap()
    }

    @Test
    fun arrayInsertFormatted() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        val formattedValue = Value.parse("'start'").unwrap()
        arr.add(0, formattedValue).unwrap()
    }

    @Test
    fun arrayReplace() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.replace(0, Value.String("start")).unwrap()
    }

    @Test
    fun arrayReplaceFormatted() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        val formattedValue = Value.parse("'start'").unwrap()
        arr.replace(0, formattedValue, keepDecor = false).unwrap()
    }

    @Test
    fun arrayRemove() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.removeAt(0).unwrap()
        assertEquals(1, arr.size)
    }

    @Test
    fun arrayOfTablesReplace() {
        val arr = ArrayOfTables()
        arr.add(tableOf("name" to Item(Value.String("apple"))))

        arr.set(0, tableOf("name" to Item(Value.String("banana")))).unwrap()
    }

    @Test
    fun itemValue() {
        val table = Table()
        val array = TomlArray()
        array.add("hello")
        // A backslash is only allowed in a literal string.
        array.add("\\, world")
        table["key1"] = "value1"
        table["key2"] = 42L
        table["key3"] = Value.Array(array)
        assertEquals("key1 = \"value1\"\nkey2 = 42\nkey3 = [\"hello\", '\\, world']\n", table.toString())
    }

    @Test
    fun tableSetImplicit() {
        val doc = TomlDocument.parse("[a]\n[a.b]\n").unwrap()

        doc["a"].tableOrNull!!.isImplicit = true
        assertEquals("[a.b]\n", doc.toString())
    }

    @Test
    fun tableEntryKey() {
        val map = Table()
        map.getOrPut("hello") { Item(Value.Integer(1L)) }
        assertEquals("hello", map.key("hello")?.name)
    }

    @Test
    fun tableOccupiedEntryKey() {
        val map = Table()
        map["foo"] = 1L
        map.getOrPut("foo") { Item(Value.Integer(2L)) }
        assertEquals("foo", map.key("foo")?.name)
    }

    @Test
    fun tableVacantEntryKey() {
        val map = Table()
        map.getOrPut("foo") { Item(Value.Integer(1L)) }
        assertEquals("foo", map.key("foo")?.name)
    }

    @Test
    fun inlineEntryKey() {
        val map = InlineTable()
        map.getOrPut("hello") { Value.Integer(1L) }
        assertEquals("hello", map.key("hello")?.name)
    }

    @Test
    fun valueDecorMut() {
        val v = Value.Boolean(true)
        assertNull(v.decor.suffix)
    }

    @Test
    fun valueDecor() {
        val v = Value.Boolean(true)
        assertNull(v.decor.suffix)
    }

    @Test
    fun valueDecorated() {
        val v = Value.Integer(42L)
        assertEquals("42", v.toString())
        v.decor = Decor(" ", " ")
        assertEquals(" 42 ", v.toString())
    }

    private fun collectStrings(
        table: TableLike,
        strings: MutableList<String>,
    ) {
        for ((_, item) in table.items()) collectStrings(item, strings)
    }

    private fun collectStrings(
        item: Item,
        strings: MutableList<String>,
    ) {
        item.valueOrNull?.let { collectStrings(it, strings) }
        item.tableOrNull?.let { collectStrings(it, strings) }
        item.arrayOfTablesOrNull?.forEach { collectStrings(it, strings) }
    }

    private fun collectStrings(
        value: Value,
        strings: MutableList<String>,
    ) {
        when (value) {
            is Value.String -> strings += value.value
            is Value.Array -> value.array.forEach { collectStrings(it, strings) }
            is Value.InlineTable -> collectStrings(value.table, strings)
            is Value.Integer, is Value.Float, is Value.Boolean, is Value.Datetime -> {}
        }
    }

    @Test
    fun visitStringCollector() {
        val input = """
laputa = "sky-castle"
the-force = { value = "surrounds-you" }
"""

        val document = TomlDocument.parse(input).unwrap()
        val strings = mutableListOf<String>()
        collectStrings(document.root, strings)

        assertEquals(listOf("sky-castle", "surrounds-you"), strings)
    }

    private fun floatsToStrings(table: TableLike) {
        for ((_, item) in table.items()) floatsToStrings(item)
    }

    private fun floatsToStrings(item: Item) {
        val value = item.valueOrNull
        if (value != null) {
            val kept = floatsToStrings(value)
            if (kept !== value) item.assign(Item(kept))
        }
        item.tableOrNull?.let { floatsToStrings(it) }
        item.arrayOfTablesOrNull?.forEach { floatsToStrings(it) }
    }

    /** Returns the value to keep in [value]'s place, since a [Value] cannot replace itself. */
    private fun floatsToStrings(value: Value): Value = when (value) {
        is Value.Float -> {
            // Convert the float to a string, with two decimals.
            val hundredths = round(value.value * 100).toLong()
            val text = "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
            // Copy over the formatting.
            Value.String(text, value.decor)
        }
        is Value.Array -> {
            for (index in 0 until value.array.size) {
                val element = value.array[index] ?: continue
                val kept = floatsToStrings(element)
                if (kept !== element) value.array.replace(index, kept, keepDecor = false)
            }
            value
        }
        is Value.InlineTable -> {
            floatsToStrings(value.table)
            value
        }
        is Value.String, is Value.Integer, is Value.Boolean, is Value.Datetime -> value
    }

    @Test
    fun visitMutFloatToString() {
        val input = """
banana = 3.26
table = { apple = 4.5 }
"""

        val document = TomlDocument.parse(input).unwrap()
        floatsToStrings(document.root)

        val output = """
banana = "3.26"
table = { apple = "4.50" }
"""

        assertEquals(output, document.toString())
    }
}
