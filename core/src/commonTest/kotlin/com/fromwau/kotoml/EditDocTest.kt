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
    fun `the module example adds an inline table under a dotted header and formats it`() {
        val toml = """
"hello" = 'toml!' # comment
['a'.b]
"""
        val doc = TomlDocument.parse(toml).getOrFail()
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
    fun `the README example keeps the text after the last header`() {
        val toml = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n    "
        val doc = TomlDocument.parse(toml).getOrFail()
        assertEquals(toml, doc.toString())
        // Add a new key/value pair inside a.b: c = {d = "hello"}
        doc["a"]["b"]["c"]["d"] = "hello"
        // Autoformat inline table a.b.c: { d = "hello" }
        doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
        val expected = "\n\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n    "
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `the repository README example adds a key below a commented one`() {
        val doc = TomlDocument.parse("[package]\nname = \"kotoml\" # the name\n").getOrFail()
        doc["package"]["version"] = "0.1.0"
        assertEquals("[package]\nname = \"kotoml\" # the name\nversion = \"0.1.0\"\n", doc.toString())
    }

    @Test
    fun `the module README example adds an inline table under a quoted header`() {
        val text = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n"
        val doc = TomlDocument.parse(text).getOrFail()
        assertEquals(text, doc.toString())
        doc["a"]["b"]["c"]["d"] = "hello"
        doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
        assertEquals("\n\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n", doc.toString())
    }

    @Test
    fun `the module README values example builds decorated values and reads their spelling`() {
        val doc = TomlDocument()
        doc["port"] = Value.Integer(8080L, Decor(prefix = " ", suffix = " # default"))
        doc["when"] = Datetime(date = Date(year = 1979, month = 5, day = 27))
        assertEquals("port = 8080 # default\nwhen = 1979-05-27\n", doc.toString())

        assertEquals(8080L, doc["port"].longOrNull)
        val parsed = TomlDocument.parse("n = 0x10\n").getOrFail()
        val spelled = parsed["n"].valueOrNull as Value.Integer
        assertEquals(16L, spelled.value)
        assertEquals("0x10", spelled.spelling)
        spelled.resetFormatting()
        assertEquals("16", spelled.displaySpelling)
    }

    @Test
    fun `the module README tables example extends an array and adds a key and a table`() {
        val doc = TomlDocument.parse("[server]\nhosts = [\"alpha\"]\n").getOrFail()
        doc["server"]["hosts"].arrayOrNull?.add("beta")
        val server = doc["server"].standardTableOrNull!!
        server.getOrPut("port") { Item(Value.Integer(8080L)) }
        doc["client"] = standardTableOf("retries" to Item(Value.Integer(3L)))
        assertEquals(
            "[server]\nhosts = [\"alpha\", \"beta\"]\nport = 8080\n\n[client]\nretries = 3\n",
            doc.toString(),
        )
    }

    @Test
    fun `the module README example of writing a key into a string fails with NotATable`() {
        val doc = TomlDocument.parse("a = 'text'\n").getOrFail()
        assertTrue(doc["a"].set("key", 1L).errorOrFail() is TomlEditError.NotATable)
        assertEquals("a = 'text'\n", doc.toString())
    }

    @Test
    fun `the module README example reads a document from a source and writes it to a sink`() {
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
        doc.writeTo(written).getOrFail()
        assertEquals("[package]\nname = \"kotoml\" # the name\nversion = \"0.2.0\"\n", written.readString())
    }

    @Test
    fun `the module README error example renders a duplicate key with its line`() {
        val input = "a = 1\na = 2\n"
        val error = TomlDocument.parse(input).errorOrFail()
        assertEquals(TomlParseError.Reason.DuplicateKey("a"), error.reason)
        assertEquals(TextPosition(line = 2, column = 1), error.position)
        assertSnapshot(
            """
TOML parse error at line 2, column 1
  |
2 | a = 2
  | ^
duplicate key `a`

""",
            error.render(input),
        )
    }

    @Test
    fun `a string set on a new document prints in double quotes`() {
        val doc = TomlDocument()
        doc["foo"] = "bar"
        assertEquals("foo = \"bar\"\n", doc.toString())
    }

    @Test
    fun `a parsed value set on a new document prints with its own spelling`() {
        val doc = TomlDocument()
        doc["foo"] = Value.parse("'bar'").getOrFail()
        assertEquals("foo = 'bar'\n", doc.toString())
    }

    @Test
    fun `an empty array can be created`() {
        assertEquals("[]", TomlArray().toString())
    }

    @Test
    fun `an array's size counts its elements`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")
        assertEquals(2, arr.size)
    }

    @Test
    fun `an array is empty until an element is added`() {
        val arr = TomlArray()
        assertTrue(arr.isEmpty())

        arr.add(1L)
        arr.add("foo")
        assertTrue(arr.isNotEmpty())
    }

    @Test
    fun `an array takes elements of different types`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")
        assertEquals("[1, \"foo\"]", arr.toString())
    }

    @Test
    fun `an array takes a parsed value with its spelling`() {
        val formattedValue = Value.parse("'literal'").getOrFail()
        val arr = TomlArray()
        arr.add(formattedValue)
        assertEquals("['literal']", arr.toString())
    }

    @Test
    fun `an array takes a value inserted at its start`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.add(0, Value.String("start")).getOrFail()
        assertEquals("[\"start\", 1, \"foo\"]", arr.toString())
    }

    @Test
    fun `an array takes a parsed value inserted at its start`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        val formattedValue = Value.parse("'start'").getOrFail()
        arr.add(0, formattedValue).getOrFail()
        assertEquals("['start', 1, \"foo\"]", arr.toString())
    }

    @Test
    fun `an array element can be replaced`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.replace(0, Value.String("start")).getOrFail()
        assertEquals("[\"start\", \"foo\"]", arr.toString())
    }

    @Test
    fun `an array element can be replaced by a parsed value without keeping its decor`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        val formattedValue = Value.parse("'start'").getOrFail()
        arr.replace(0, formattedValue, keepDecor = false).getOrFail()
        assertEquals("['start', \"foo\"]", arr.toString())
    }

    @Test
    fun `removing an array element shrinks the array`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.removeAt(0).getOrFail()
        assertEquals(1, arr.size)
    }

    @Test
    fun `an array of tables element can be set to another table`() {
        val arr = ArrayOfTables()
        arr.add(standardTableOf("name" to Item(Value.String("apple"))))

        arr.set(0, standardTableOf("name" to Item(Value.String("banana")))).getOrFail()
        assertEquals("banana", arr[0]?.get("name")?.stringOrNull)
    }

    @Test
    fun `string values that need escaping print as literal strings`() {
        val table = StandardTable()
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
    fun `an implicit table with a sub-table prints only the sub-table's header`() {
        val doc = TomlDocument.parse("[a]\n[a.b]\n").getOrFail()

        doc["a"].standardTableOrNull!!.isImplicit = true
        assertEquals("[a.b]\n", doc.toString())
    }

    @Test
    fun `getOrPut on an empty table stores the key under its name`() {
        val map = StandardTable()
        map.getOrPut("hello") { Item(Value.Integer(1L)) }
        assertEquals("hello", map.keyOrNull("hello")?.name)
    }

    @Test
    fun `getOrPut on a present key keeps the key under its name`() {
        val map = StandardTable()
        map["foo"] = 1L
        map.getOrPut("foo") { Item(Value.Integer(2L)) }
        assertEquals("foo", map.keyOrNull("foo")?.name)
    }

    @Test
    fun `getOrPut on a missing key stores the key under its name`() {
        val map = StandardTable()
        map.getOrPut("foo") { Item(Value.Integer(1L)) }
        assertEquals("foo", map.keyOrNull("foo")?.name)
    }

    @Test
    fun `getOrPut on an inline table stores the key under its name`() {
        val map = InlineTable()
        map.getOrPut("hello") { Value.Integer(1L) }
        assertEquals("hello", map.keyOrNull("hello")?.name)
    }

    @Test
    fun `a new value's decor has no suffix`() {
        val v = Value.Boolean(true)
        assertNull(v.decor.suffix)
    }

    @Test
    fun `a new value's decor reads as default`() {
        assertEquals(Decor(), Value.Boolean(true).decor)
    }

    @Test
    fun `a value's decor prints around its text`() {
        val v = Value.Integer(42L)
        assertEquals("42", v.toString())
        v.decor = Decor(" ", " ")
        assertEquals(" 42 ", v.toString())
    }

    private fun collectStrings(
        table: Table,
        strings: MutableList<String>,
    ) {
        for ((_, item) in table.entries) collectStrings(item, strings)
    }

    private fun collectStrings(
        item: Item,
        strings: MutableList<String>,
    ) {
        item.valueOrNull?.let { collectStrings(it, strings) }
        item.standardTableOrNull?.let { collectStrings(it, strings) }
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
    fun `walking a document collects every string in tables and inline tables`() {
        val input = """
laputa = "sky-castle"
the-force = { value = "surrounds-you" }
"""

        val document = TomlDocument.parse(input).getOrFail()
        val strings = mutableListOf<String>()
        collectStrings(document.root, strings)

        assertEquals(listOf("sky-castle", "surrounds-you"), strings)
    }

    private fun floatsToStrings(table: Table) {
        for ((_, item) in table.entries) floatsToStrings(item)
    }

    private fun floatsToStrings(item: Item) {
        val value = item.valueOrNull
        if (value != null) {
            val kept = floatsToStrings(value)
            if (kept !== value) item.assign(Item(kept))
        }
        item.standardTableOrNull?.let { floatsToStrings(it) }
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
    fun `walking a document replaces each float with a string and keeps its decor`() {
        val input = """
banana = 3.26
table = { apple = 4.5 }
"""

        val document = TomlDocument.parse(input).getOrFail()
        floatsToStrings(document.root)

        val output = """
banana = "3.26"
table = { apple = "4.50" }
"""

        assertEquals(output, document.toString())
    }
}
