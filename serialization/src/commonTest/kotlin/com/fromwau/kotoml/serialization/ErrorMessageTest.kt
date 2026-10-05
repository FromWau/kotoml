package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Every error reads as a sentence, and a decode error says where in the text it is. */
class ErrorMessageTest {

    private val app = "title = \"t\"\n\n[database]\nurl = \"db\"\n\n[database.pool]\nsize = \"ten\"\n"

    @Test
    fun `a type mismatch names the path what it expected and the value it found`() {
        assertEquals(
            "database.pool.size: expected an integer, found the string \"ten\"",
            Toml.decodeFromString<App>(app).errorOrFail().message,
        )
    }

    @Test
    fun `a mismatch on a table names its kind alone`() {
        assertEquals(
            "servers: expected an array, found a table",
            Toml.decodeFromString<Fleet>("name = \"f\"\n[servers]\nhost = \"a\"\n").errorOrFail().message,
        )
    }

    @Test
    fun `an unknown key suggests the field it is closest to`() {
        val error = Toml.decodeFromString<Fleet>("nmae = \"f\"\n").errorOrFail()
        assertEquals(TomlDecodeError.UnknownKey("nmae", "name", TextPosition(1, 1)), error)
        assertEquals("unknown key nmae, did you mean name?", error.message)
    }

    @Test
    fun `an unknown key close to no field suggests nothing`() {
        assertEquals("unknown key zzz", Toml.decodeFromString<Server>("host = \"h\"\nzzz = 1\n").errorOrFail().message)
    }

    @Test
    fun `a missing key and an unknown enum value read as sentences`() {
        val missing = Toml.decodeFromString<App>("title = \"t\"\n[database]\n").errorOrFail()
        assertEquals("missing key database.url", missing.message)
        assertEquals(
            "mode: unknown value \"tcp\", did you mean TCP?",
            Toml.decodeFromString<Config>("hostname = \"h\"\nport = 1\nmode = \"tcp\"\n").errorOrFail().message,
        )
        assertEquals(
            "shape: unknown subclass \"triangle\", expected one of circle, point, square",
            Toml.decodeFromString<Holder>("[shape]\ntype = \"triangle\"\n").errorOrFail().message,
        )
    }

    @Test
    fun `a parse error gives its place and reason`() {
        assertEquals(
            "line 2, column 1: duplicate key `a`",
            Toml.decodeFromString<Ports>("a = 1\na = 2\n").errorOrFail().message,
        )
    }

    @Test
    fun `a decode error has the position of the key it names`() {
        val error = Toml.decodeFromString<App>(app).errorOrFail()
        assertIs<TomlDecodeError.Located>(error)
        assertEquals(TextPosition(7, 1), error.position)
    }

    @Test
    fun `an element of a list has the position of the list's key`() {
        val error = Toml.decodeFromString<Grid>("\nrows = [[1, 2], [3, \"x\"]]\n").errorOrFail()
        val expected = TomlDecodeError.TypeMismatch("rows[1][1]", TomlType.Integer, TomlType.String, "\"x\"")
        assertEquals(expected.copy(position = TextPosition(2, 1)), error)
    }

    @Test
    fun `a document parsed from text gives positions too`() {
        val document = TomlDocument.parse("host = 1\n").getOrFail()
        val error = Toml.decodeFromDocument<Server>(document).errorOrFail()
        assertIs<TomlDecodeError.Located>(error)
        assertEquals(TextPosition(1, 1), error.position)
    }

    @Test
    fun `a document built in code has no positions`() {
        val root = StandardTable()
        root.set("host", Value.Integer(1)).getOrFail()
        val error = Toml.decodeFromDocument<Server>(TomlDocument(root)).errorOrFail()
        assertIs<TomlDecodeError.Located>(error)
        assertNull(error.position)
    }

    @Test
    fun `an encode error reads as a sentence`() {
        assertEquals(
            "ports: a map key that is not a string, which a table cannot hold",
            Toml.encodeToString(NumberedPorts(mapOf(1 to "a"))).errorOrFail().message,
        )
        assertEquals(
            "kotlin.Int cannot be written as a document, which is a table",
            Toml.encodeToString(1).errorOrFail().message,
        )
    }
}
