package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** The builder: it builds documents from scratch and edits existing ones all or nothing, keeping their formatting. */
class TomlDslTest {

    private fun parse(text: String): TomlDocument = TomlDocument.parse(text).assertSuccess()

    @Test
    fun `a document built with every kind of block prints as TOML`() {
        val doc = buildTomlDocument {
            "title" to "kotoml"
            table("server") {
                "host" to "localhost"
                "port" to 8080
                "ratio" to 0.5
                "debug" to false
                array("tags") {
                    add("a")
                    add(1)
                }
                inlineTable("limits") { "rps" to 100 }
            }
            arrayOfTables("bin") {
                table { "name" to "kotoml" }
                table { "name" to "kt" }
            }
        }.assertSuccess()
        assertEquals(
            "title = \"kotoml\"\n\n[server]\nhost = \"localhost\"\nport = 8080\nratio = 0.5\ndebug = false\n" +
                "tags = [\"a\", 1]\nlimits = { rps = 100 }\n\n[[bin]]\nname = \"kotoml\"\n\n[[bin]]\nname = \"kt\"\n",
            doc.toString(),
        )
    }

    @Test
    fun `a table that only holds other tables prints no header of its own`() {
        val doc = buildTomlDocument {
            table("tool") {
                table("poetry") { "name" to "kotoml" }
            }
        }.assertSuccess()
        assertEquals("[tool.poetry]\nname = \"kotoml\"\n", doc.toString())
    }

    @Test
    fun `an empty table prints its header`() {
        val doc = buildTomlDocument { table("empty") {} }.assertSuccess()
        assertEquals("[empty]\n", doc.toString())
    }

    @Test
    fun `an edit keeps the spelling and comments of a key it writes`() {
        val doc = parse("# the server\n[server]\n'port' = 8080 # default\nhost = \"x\"\n")
        doc.edit {
            table("server") { "port" to 9090 }
        }.assertSuccess()
        assertEquals("# the server\n[server]\n'port' = 9090 # default\nhost = \"x\"\n", doc.toString())
    }

    @Test
    fun `an edit creates a table that is not there`() {
        val doc = parse("[server]\nport = 8080\n")
        doc.edit {
            table("database") { "url" to "postgres://" }
        }.assertSuccess()
        assertEquals("[server]\nport = 8080\n\n[database]\nurl = \"postgres://\"\n", doc.toString())
    }

    @Test
    fun `an edit appends to an array and to an array of tables`() {
        val doc = parse("tags = [\"a\"]\n\n[[bin]]\nname = \"a\"\n")
        doc.edit {
            array("tags") { add("b") }
            arrayOfTables("bin") { table { "name" to "b" } }
        }.assertSuccess()
        assertEquals("tags = [\"a\", \"b\"]\n\n[[bin]]\nname = \"a\"\n\n[[bin]]\nname = \"b\"\n", doc.toString())
    }

    @Test
    fun `an edit removes a key and keeps the comment above it`() {
        val doc = parse("# about debug\ndebug = true\nport = 1\n")
        doc.edit { remove("debug") }.assertSuccess()
        assertEquals("# about debug\nport = 1\n", doc.toString())
    }

    @Test
    fun `opening a key that holds another kind is TypeMismatch`() {
        val doc = parse("port = 1\n[server]\n")
        assertEquals(
            TomlEditError.TypeMismatch("port", TomlType.Table, TomlType.Integer),
            doc.edit { table("port") {} }.assertError<TomlEditError.TypeMismatch>(),
        )
        assertEquals(
            TomlEditError.TypeMismatch("server", TomlType.Array, TomlType.Table),
            doc.edit { array("server") {} }.assertError<TomlEditError.TypeMismatch>(),
        )
        assertEquals(
            TomlEditError.TypeMismatch("server", TomlType.InlineTable, TomlType.Table),
            doc.edit { inlineTable("server") {} }.assertError<TomlEditError.TypeMismatch>(),
        )
        assertEquals(
            TomlEditError.TypeMismatch("port", TomlType.ArrayOfTables, TomlType.Integer),
            doc.edit { arrayOfTables("port") {} }.assertError<TomlEditError.TypeMismatch>(),
        )
    }

    @Test
    fun `a failed edit leaves the document as it was with the writes before the error undone`() {
        val text = "# about port\nport = 1\n\n[server]\nhost = \"x\"\n"
        val doc = parse(text)
        doc.edit {
            "port" to 2
            table("server") { "host" to "y" }
            table("port") {}
            "after" to true
        }.assertError<TomlEditError.TypeMismatch>()
        assertEquals(text, doc.toString())
    }

    @Test
    fun `a build that opens a key holding another kind fails`() {
        val error = buildTomlDocument {
            "a" to 1
            table("a") {}
        }.assertError<TomlEditError.TypeMismatch>()
        assertEquals(TomlEditError.TypeMismatch("a", TomlType.Table, TomlType.Integer), error)
    }

    @Test
    fun `the document reads the edited content after an edit`() {
        val doc = parse("[server]\nport = 8080\n")
        doc.edit { table("server") { "port" to 9090 } }.assertSuccess()
        assertEquals(9090L, doc["server"]["port"].assertSuccess().longOrNull)
    }

    @Test
    fun `an item taken before a successful edit no longer writes into the document`() {
        val doc = parse("port = 1\n")
        val port = doc["port"].assertSuccess()
        doc.edit { "host" to "x" }.assertSuccess()
        port.assign(Item(Value.Integer(2L))).assertSuccess()
        assertEquals("port = 1\nhost = \"x\"\n", doc.toString())
    }

    @Test
    fun `TypeMismatch encodes with the key and both kinds`() {
        assertEquals(
            """{"type":"TypeMismatch","key":"port","expected":"Table","found":"Integer"}""",
            Json.encodeToString(
                TomlEditError.serializer(),
                TomlEditError.TypeMismatch("port", TomlType.Table, TomlType.Integer),
            ),
        )
    }
}
