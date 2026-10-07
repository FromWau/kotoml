package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrElse
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString

/** Upstream's documentation examples for `toml_edit`, which `cargo test` runs as doctests. */
class EditDocTest {

    @Test
    fun `the module example adds an inline table under a dotted header and formats it`() {
        val toml = """
"hello" = 'toml!' # comment
['a'.b]
"""
        val doc = TomlDocument.parse(toml).assertSuccess()
        assertEquals(toml, doc.toString())
        // Add a new key/value pair inside a.b: c = {d = "hello"}
        doc["a"]["b"]["c"] = Value.InlineTable(InlineTable())
        doc["a"]["b"]["c"]["d"] = "hello"
        // Autoformat inline table a.b.c: { d = "hello" }
        doc["a"]["b"]["c"].assertSuccess().inlineTableOrNull?.format()
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
        val doc = TomlDocument.parse(toml).assertSuccess()
        assertEquals(toml, doc.toString())
        // Add a new key/value pair inside a.b: c = {d = "hello"}
        doc["a"]["b"]["c"] = Value.InlineTable(InlineTable())
        doc["a"]["b"]["c"]["d"] = "hello"
        // Autoformat inline table a.b.c: { d = "hello" }
        doc["a"]["b"]["c"].assertSuccess().inlineTableOrNull?.format()
        val expected = "\n\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n    "
        assertEquals(expected, doc.toString())
    }

    @Test
    fun `the repository README example adds a key below a commented one`() {
        val doc = TomlDocument.parse("[package]\nname = \"kotoml\" # the name\n").assertSuccess()
        doc["package"]["version"] = "0.1.0"
        assertEquals("[package]\nname = \"kotoml\" # the name\nversion = \"0.1.0\"\n", doc.toString())
    }

    @Test
    fun `the module README example adds an inline table under a quoted header`() {
        val text = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n"
        val doc = TomlDocument.parse(text).assertSuccess()
        assertEquals(text, doc.toString())
        doc["a"]["b"]["c"] = Value.InlineTable(InlineTable())
        doc["a"]["b"]["c"]["d"] = "hello"
        assertEquals("\n\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n", doc.toString())
    }

    @Test
    fun `the module README format example tidies a document`() {
        val doc = TomlDocument.parse("#top\n  name   =   'kotoml'    #the name\nlist = [ 1 ,2, ]\n\n\n[ server ]\n")
            .assertSuccess()
        doc.format()
        assertEquals("# top\nname = 'kotoml' # the name\nlist = [1, 2]\n\n[server]\n", doc.toString())
    }

    @Test
    fun `the format examples in the KDoc print as they say`() {
        val doc = TomlDocument.parse("#top\n  name   =   'kotoml'    #the name\n[ server ]\nlist = [ 1 ,2, ]\n")
            .assertSuccess()
        doc.format()
        assertEquals("# top\nname = 'kotoml' # the name\n\n[server]\nlist = [1, 2]\n", doc.toString())
        val tables = TomlDocument.parse("a=1\n[ t ]\n  b=2\n").assertSuccess()
        tables["t"].assertSuccess().standardTableOrNull?.format()
        assertEquals("a=1\n\n[t]\nb = 2\n", tables.toString())
        val inline = TomlDocument.parse("t = {a=1,b=2,}\n").assertSuccess()
        inline["t"].assertSuccess().inlineTableOrNull?.format()
        assertEquals("t = { a = 1, b = 2 }\n", inline.toString())
        val array = TomlDocument.parse("a = [ 1 ,2, ]\n").assertSuccess()
        array["a"].assertSuccess().arrayOrNull?.format()
        assertEquals("a = [1, 2]\n", array.toString())
        val slot = TomlDocument.parse("a   =   [ 1 ,2 ]   #c\nb=2\n").assertSuccess()
        slot["a"].assertSuccess().format()
        assertEquals("a = [1, 2] # c\nb=2\n", slot.toString())
    }

    @Test
    fun `the module README values example builds decorated values and reads their spelling`() {
        val doc = TomlDocument()
        doc["port"] = Value.Integer(8080L, Decor(prefix = " ", suffix = " # default"))
        doc["when"] = Datetime.LocalDate(Date.of(1979, 5, 27).getOrElse { error(it) })
        assertEquals("port = 8080 # default\nwhen = 1979-05-27\n", doc.toString())

        assertEquals(8080L, doc["port"].assertSuccess().longOrNull)
        val parsed = TomlDocument.parse("n = 0x10\n").assertSuccess()
        val spelled = parsed["n"].assertSuccess().valueOrNull as Value.Integer
        assertEquals(16L, spelled.value)
        assertEquals("0x10", spelled.spelling)
        spelled.format()
        assertEquals("0x10", spelled.displaySpelling)
    }

    @Test
    fun `the module README tables example extends an array and adds a key and a table`() {
        val doc = TomlDocument.parse("[server]\nhosts = [\"alpha\"]\n").assertSuccess()
        doc["server"]["hosts"].assertSuccess().arrayOrNull?.add("beta")
        val server = doc["server"].assertSuccess().standardTableOrNull!!
        server.getOrPut("port") { Item(Value.Integer(8080L)) }
        doc["client"] = standardTableOf("retries" to Item(Value.Integer(3L))).assertSuccess()
        assertEquals(
            "[server]\nhosts = [\"alpha\", \"beta\"]\nport = 8080\n\n[client]\nretries = 3\n",
            doc.toString(),
        )
    }

    @Test
    fun `the module README rename example renames a table and the header under it`() {
        val doc = TomlDocument.parse("# db\n[old] # main\nurl = 1\n\n[cache]\n[old.pool]\nsize = 2\n").assertSuccess()
        doc.rename("old", "db").assertSuccess()
        assertEquals("# db\n[db] # main\nurl = 1\n\n[cache]\n[db.pool]\nsize = 2\n", doc.toString())
    }

    @Test
    fun `the KDoc example of rename renames a table and its sub-table`() {
        val doc = TomlDocument.parse("# db\n[old] # main\nurl = 1\n[old.pool]\nsize = 2\n").assertSuccess()
        doc.rename("old", "db").assertSuccess()
        assertEquals("# db\n[db] # main\nurl = 1\n[db.pool]\nsize = 2\n", doc.toString())
    }

    @Test
    fun `the module README layout example makes a table inline across lines and then forces it on one line`() {
        val text = "[t] # t\na = 1 # one\n"
        val doc = TomlDocument.parse(text).assertSuccess()
        val slot = doc["t"].assertSuccess()
        val error = slot.setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        assertEquals(text, doc.toString())
        slot.setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = { # t\n  a = 1, # one\n}\n", doc.toString())
        slot.setLayout(TableLayout.Inline, force = true).assertSuccess()
        assertEquals("t = { a = 1 } # t\n", doc.toString())
    }

    @Test
    fun `the KDoc example of setLayout goes across lines and back under a header keeping every comment`() {
        val doc = TomlDocument.parse("[t] # t\na = 1 # one\n").assertSuccess()
        val slot = doc["t"].assertSuccess()
        slot.setLayout(TableLayout.MultilineInline).assertSuccess()
        assertEquals("t = { # t\n  a = 1, # one\n}\n", doc.toString())
        val error = slot.setLayout(TableLayout.Inline).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t"), 1), error)
        slot.setLayout(TableLayout.Header).assertSuccess()
        assertEquals("[t] # t\na = 1 # one\n", doc.toString())
    }

    @Test
    fun `the KDoc example of TableLayout reads a header and puts the table on one line`() {
        val doc = TomlDocument.parse("[t]\na = 1\n").assertSuccess()
        assertEquals(TableLayout.Header, doc["t"].assertSuccess().layout)
        doc["t"].assertSuccess().setLayout(TableLayout.Inline).assertSuccess()
        assertEquals("t = { a = 1 }\n", doc.toString())
    }

    @Test
    fun `the module README builder example builds a document and edits it`() {
        val doc = buildTomlDocument {
            "title" to "kotoml"
            table("server") {
                "port" to 8080
                array("tags") { add("fast") }
            }
            arrayOfTables("bin") {
                table { "name" to "kotoml" }
            }
        }.getOrElse { error(it) }

        doc.edit {
            table("server") { "port" to 9090 }
            table("database") { "url" to "postgres://" }
        }.assertSuccess()
        assertEquals(
            "title = \"kotoml\"\n\n[server]\nport = 9090\ntags = [\"fast\"]\n\n[[bin]]\nname = \"kotoml\"\n\n" +
                "[database]\nurl = \"postgres://\"\n",
            doc.toString(),
        )
    }

    @Test
    fun `the module README versions example converts a document to TOML 1_0`() {
        val doc = TomlDocument.parse("t = 07:32\ns = \"\\e\"\n").assertSuccess()
        doc.setVersion(TomlVersion.V1_0).assertSuccess()
        assertEquals("t = 07:32:00\ns = \"\\u001B\"\n", doc.toString())
    }

    @Test
    fun `the module README example of writing a key into a string fails with NotATable`() {
        val doc = TomlDocument.parse("a = 'text'\n").assertSuccess()
        doc["a"].set("key", 1L).assertError<TomlEditError.NotATable>()
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
        doc.writeTo(written).assertSuccess()
        assertEquals("[package]\nname = \"kotoml\" # the name\nversion = \"0.2.0\"\n", written.readString())
    }

    @Test
    fun `the module README error example renders a duplicate key with its line`() {
        val input = "a = 1\na = 2\n"
        val error = TomlDocument.parse(input).assertError<TomlParseError>()
        assertEquals(TomlParseError.Reason.DuplicateKey(listOf("a")), error.reason)
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
        doc["foo"] = Value.parse("'bar'").assertSuccess()
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
        val formattedValue = Value.parse("'literal'").assertSuccess()
        val arr = TomlArray()
        arr.add(formattedValue)
        assertEquals("['literal']", arr.toString())
    }

    @Test
    fun `an array takes a value inserted at its start`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.add(0, Value.String("start")).assertSuccess()
        assertEquals("[\"start\", 1, \"foo\"]", arr.toString())
    }

    @Test
    fun `an array takes a parsed value inserted at its start`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        val formattedValue = Value.parse("'start'").assertSuccess()
        arr.add(0, formattedValue).assertSuccess()
        assertEquals("['start', 1, \"foo\"]", arr.toString())
    }

    @Test
    fun `an array element can be replaced`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.replace(0, Value.String("start")).assertSuccess()
        assertEquals("[\"start\", \"foo\"]", arr.toString())
    }

    @Test
    fun `an array element can be replaced by a parsed value without keeping its decor`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        val formattedValue = Value.parse("'start'").assertSuccess()
        arr.replace(0, formattedValue, keepDecor = false).assertSuccess()
        assertEquals("['start', \"foo\"]", arr.toString())
    }

    @Test
    fun `removing an array element shrinks the array`() {
        val arr = TomlArray()
        arr.add(1L)
        arr.add("foo")

        arr.removeAt(0).assertSuccess()
        assertEquals(1, arr.size)
    }

    @Test
    fun `an array of tables element can be set to another table`() {
        val arr = ArrayOfTables()
        arr.add(standardTableOf("name" to Item(Value.String("apple"))).assertSuccess())

        arr.set(0, standardTableOf("name" to Item(Value.String("banana"))).assertSuccess()).assertSuccess()
        assertEquals("banana", arr[0].assertSuccess()["name"].assertSuccess().stringOrNull)
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
        val doc = TomlDocument.parse("[a]\n[a.b]\n").assertSuccess()

        doc["a"].assertSuccess().standardTableOrNull!!.setImplicit(true)
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
    fun `a value's decor prints around its text where it is stored`() {
        val v = Value.Integer(42L)
        v.decor = Decor(" ", " # c")
        assertEquals("42", v.toString())
        val doc = TomlDocument()
        doc.set("a", v).assertSuccess()
        assertEquals("a = 42 # c\n", doc.toString())
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
            is Value.Array -> value.value.forEach { collectStrings(it, strings) }
            is Value.InlineTable -> collectStrings(value.value, strings)
            is Value.Integer, is Value.Float, is Value.Boolean, is Value.Datetime -> {}
        }
    }

    @Test
    fun `walking a document collects every string in tables and inline tables`() {
        val input = """
laputa = "sky-castle"
the-force = { value = "surrounds-you" }
"""

        val document = TomlDocument.parse(input).assertSuccess()
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
            for (index in 0 until value.value.size) {
                val element = value.value.getOrNull(index) ?: continue
                val kept = floatsToStrings(element)
                if (kept !== element) value.value.replace(index, kept, keepDecor = false)
            }
            value
        }
        is Value.InlineTable -> {
            floatsToStrings(value.value)
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

        val document = TomlDocument.parse(input).assertSuccess()
        floatsToStrings(document.root)

        val output = """
banana = "3.26"
table = { apple = "4.50" }
"""

        assertEquals(output, document.toString())
    }
}
