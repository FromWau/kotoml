package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GetTest {

    private val sample = """
        |# Package metadata
        |[package]
        |name = "kotoml"   # the name
        |version = "0.1.0"
        |
        |[[servers]]
        |host = "alpha"
        |port = 8080
        |
        |[[servers]]
        |host = "beta"
        |port = 8081
        |""".trimMargin()

    private fun select(
        filter: String,
        text: String = sample,
    ): Result<Selection, AppError> {
        val document = TomlDocument.parse(text).assertSuccess()
        val parsed = when (val parsed = parseFilter(filter)) {
            is Result.Success -> parsed.value
            is Result.Error -> return parsed
        }
        return parsed.select(document)
    }

    private fun selection(
        filter: String,
        text: String = sample,
    ): Selection = select(filter, text).assertSuccess()

    private fun get(
        filter: String,
        raw: Boolean = false,
        text: String = sample,
    ): String = selection(filter, text).humanText(raw)

    private fun json(filter: String): String = Json.encodeToString(SelectionSerializer, selection(filter))

    @Test
    fun `the root prints as written`() {
        assertEquals(sample.trimEnd(), get("."))
    }

    @Test
    fun `the root prints the comments and blank lines after the last item`() {
        assertEquals("a = 1\n\n# end", get(".", text = "a = 1\n\n# end\n"))
        assertEquals("a = 1\r\n# end\r", get(".", text = "a = 1\r\n# end\r\n"))
    }

    @Test
    fun `the root of a file of only comments prints them`() {
        assertEquals("# note", get(".", text = "# note\n"))
    }

    @Test
    fun `an empty result prints nothing whatever the line endings`() {
        assertEquals("", selection(".a[]", text = "a = []\r\n").humanText(raw = false))
        assertEquals("", selection(".a[]", text = "a = []\n").humanText(raw = false))
    }

    @Test
    fun `a value in a CRLF file ends with a bare newline`() {
        assertEquals("1\n", selection(".a", text = "a = 1\r\n").print(raw = false))
        assertEquals("1\n2\n", selection(".a[]", text = "a = [1, 2]\r\n").print(raw = false))
    }

    @Test
    fun `a table ends each line as the file does`() {
        assertEquals("x = 1\r\ny = 2\n", selection(".t", text = "[t]\nx = 1\r\ny = 2\n").print(raw = false))
    }

    @Test
    fun `a table prints its lines without the header comment`() {
        assertEquals("name = \"kotoml\"   # the name\nversion = \"0.1.0\"", get(".package"))
    }

    @Test
    fun `a value prints without its decor`() {
        assertEquals("\"kotoml\"", get(".package.name"))
        assertEquals("kotoml", get(".package.name", raw = true))
    }

    @Test
    fun `iteration and pipes stream each result`() {
        assertEquals("\"alpha\"\n\"beta\"", get(".servers[] | .host"))
        assertEquals("8081", get(".servers[-1].port"))
    }

    @Test
    fun `an array of tables prints as written under its headers`() {
        assertEquals(
            "[[servers]]\nhost = \"alpha\"\nport = 8080\n\n[[servers]]\nhost = \"beta\"\nport = 8081",
            get(".servers"),
        )
    }

    @Test
    fun `an array of tables keeps its comments`() {
        val text = "# servers\n[[srv]]\nhost = \"a\"  # primary\n"
        assertEquals("# servers\n[[srv]]\nhost = \"a\"  # primary", get(".srv", text = text))
    }

    @Test
    fun `a nested array of tables prints its whole header path`() {
        val text = "[a]\nx = 1\n\n[[a.srv]]\nh = 1\n"
        assertEquals("[[a.srv]]\nh = 1", get(".a.srv", text = text))
        assertEquals("[[a.srv]]\nh = 1", get(".a | .srv", text = text))
    }

    @Test
    fun `iterating tables separates them with a blank line`() {
        assertEquals(
            "host = \"alpha\"\nport = 8080\n\nhost = \"beta\"\nport = 8081",
            get(".servers[]"),
        )
    }

    @Test
    fun `a table made only of dotted keys prints its lines`() {
        assertEquals("b = 2\nc = 3", get(".a", text = "a.b = 2\na.c = 3\n"))
        assertEquals("y.z = 1", get(".x", text = "x.y.z = 1\n"))
    }

    @Test
    fun `a table in a CRLF file prints with CRLF line endings`() {
        val text = "[t]\r\nc = 2\r\nd = 3\r\n\r\n[[s]]\r\nh = 1\r\n\r\n[[s]]\r\nh = 2\r\n"
        assertEquals("c = 2\r\nd = 3\r", get(".t", text = text))
        assertEquals("h = 1\r\n\r\nh = 2\r", get(".s[]", text = text))
        assertEquals("[[s]]\r\nh = 1\r\n\r\n[[s]]\r\nh = 2\r", get(".s", text = text))
    }

    @Test
    fun `a quoted key takes jq escapes`() {
        val text = "ttk = 1\n\"t\\tk\" = 9\n\"a\\nb\" = 2\n\"\u00e9\" = 3\n\"q\\\"\" = 4\n"
        assertEquals("9", get(".\"t\\tk\"", text = text))
        assertEquals("2", get(".[\"a\\nb\"]", text = text))
        assertEquals("3", get(".\"\\u00e9\"", text = text))
        assertEquals("4", get(".\"q\\\"\"", text = text))
    }

    @Test
    fun `an unknown escape in a quoted key is a filter syntax error`() {
        assertEquals(
            Result.Error(AppError.FilterSyntax(".\"t\\zk\"", 3, FilterExpected.Escape)),
            parseFilter(".\"t\\zk\""),
        )
    }

    @Test
    fun `a key that is not there is an error naming it and where`() {
        assertEquals(Result.Error(AppError.NoSuchKey(".nope", "nope")), select(".nope.deeper"))
        assertEquals(Result.Error(AppError.NoSuchKey(".package.nope", "nope")), select(".package.nope"))
        assertEquals(Result.Error(AppError.NoSuchKey(".\"my key\"", "my key")), select(".\"my key\""))
        assertEquals(Result.Error(AppError.NoSuchKey(".servers[0].nope", "nope")), select(".servers[] | .nope"))
    }

    @Test
    fun `iterating a key that is not there is an error`() {
        assertEquals(Result.Error(AppError.NoSuchKey(".nope", "nope")), select(".nope[]"))
    }

    @Test
    fun `an index past either end is an error naming the size`() {
        assertEquals(Result.Error(AppError.NoSuchIndex(".servers[5]", 5, 2)), select(".servers[5]"))
        assertEquals(Result.Error(AppError.NoSuchIndex(".servers[-3]", -3, 2)), select(".servers[-3]"))
    }

    @Test
    fun `a missing key or index reads as a sentence`() {
        assertEquals("no key \"nope\" at .nope", AppError.NoSuchKey(".nope", "nope").describe())
        assertEquals("no index 5 at .servers[5], it has 2", AppError.NoSuchIndex(".servers[5]", 5, 2).describe())
    }

    @Test
    fun `a step a value cannot take names its type in words`() {
        assertEquals(
            "cannot apply .\"x\" to array of tables",
            AppError.CannotApply(Step.Key("x"), TomlType.ArrayOfTables).describe(),
        )
    }

    @Test
    fun `JSON prints one value or a stream as an array`() {
        assertEquals("""{"host":"alpha","port":8080}""", json(".servers[0]"))
        assertEquals("""["alpha","beta"]""", json(".servers[] | .host"))
    }

    @Test
    fun `a step a scalar cannot take is an error`() {
        assertEquals(
            Result.Error(AppError.CannotApply(Step.Key("x"), TomlType.String)),
            select(".package.name.x"),
        )
    }
}
