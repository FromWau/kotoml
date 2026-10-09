package com.fromwau.kotoml.app

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.WriteError
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The README's exit codes and `--json` output, from whole `kotoml` invocations. */
class ExitCodeTest {

    private open class Recorded : Terminal {
        val out = StringBuilder()
        val err = StringBuilder()

        /** Both streams in the order they were written, as a terminal showing them together reads them. */
        val both = StringBuilder()

        override fun out(text: String) {
            out.append(text)
            both.append(text)
        }

        override fun err(text: String) {
            err.append(text)
            both.append(text)
        }
    }

    private val files = mutableListOf<Path>()

    private fun file(text: String): String {
        val path = Path(SystemTemporaryDirectory, "kotoml-${Random.nextLong().toULong()}.toml")
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
        files += path
        return path.toString()
    }

    private fun missingFile(): String =
        Path(SystemTemporaryDirectory, "kotoml-missing-${Random.nextLong().toULong()}.toml").toString()

    @AfterTest
    fun deleteFiles() {
        for (path in files) SystemFileSystem.delete(path)
    }

    private fun kotoml(vararg args: String): Pair<Int, Recorded> {
        val terminal = Recorded()
        return runKotoml(args.toList(), terminal) to terminal
    }

    private class FailingWrites(private val error: WriteError) : Recorded() {
        override fun writeResult(): EmptyResult<WriteError> = Err(error)
    }

    @Test
    fun `a valid file checks with exit 0 and prints OK`() {
        val (code, terminal) = kotoml("check", file("a = 1\n"))
        assertEquals(0, code)
        assertEquals("OK", terminal.out.toString().trim())
    }

    @Test
    fun `check --json prints valid true for a valid file`() {
        val (code, terminal) = kotoml("check", "--json", file("a = 1\n"))
        assertEquals(0, code)
        assertEquals("{\"valid\":true}", terminal.out.toString().trim())
    }

    @Test
    fun `a file that is not TOML exits 1 and --json prints the error with its code on standard error`() {
        val bad = file("a = 1\na = 2\n")
        val (code, terminal) = kotoml("check", "--json", bad)
        assertEquals(1, code)
        assertEquals(
            "{\"error\":{\"type\":\"InvalidToml\",\"path\":\"$bad\"," +
                "\"error\":{\"reason\":{\"type\":\"DuplicateKey\",\"keyPath\":[\"a\"]}," +
                "\"span\":{\"start\":6,\"end\":7},\"position\":{\"line\":2,\"column\":1}," +
                "\"context\":{\"start\":0,\"end\":1}}},\"code\":1}",
            terminal.err.toString().trim(),
        )
    }

    @Test
    fun `an input that cannot be read exits 1`() {
        assertEquals(1, kotoml("check", missingFile()).first)
    }

    @Test
    fun `a filter that does not parse exits 2`() {
        assertEquals(2, kotoml("get", "-f", file("a = 1\n"), ".[").first)
    }

    @Test
    fun `an unknown option exits 2`() {
        assertEquals(2, kotoml("check", "--nope", file("a = 1\n")).first)
    }

    @Test
    fun `get prints what the filter selects with exit 0`() {
        val (code, terminal) = kotoml("get", "-f", file("a = 1\n"), ".a")
        assertEquals(0, code)
        assertEquals("1", terminal.out.toString().trim())
    }

    @Test
    fun `output the system refused exits 1 and says so`() {
        val terminal = FailingWrites(WriteError.Refused(Stream.Out, "No space left on device"))
        assertEquals(1, runKotoml(listOf("get", "-f", file("a = 1\n"), ".a"), terminal))
        assertEquals("error: cannot write to standard output: No space left on device\n", terminal.err.toString())
    }

    @Test
    fun `a write standard error refused is named as standard error`() {
        val terminal = FailingWrites(WriteError.Refused(Stream.Err, "No space left on device"))
        assertEquals(1, runKotoml(listOf("get", "-f", file("a = 1\n"), ".a"), terminal))
        assertEquals("error: cannot write to standard error: No space left on device\n", terminal.err.toString())
    }

    @Test
    fun `a reader that closes the pipe ends the run with 141 and no message`() {
        val terminal = FailingWrites(WriteError.BrokenPipe(Stream.Out))
        assertEquals(141, runKotoml(listOf("get", "-f", file("a = 1\n"), ".a"), terminal))
        assertEquals("", terminal.err.toString())
    }

    @Test
    fun `a filter that finds nothing exits 3 and says so on standard error`() {
        val (code, terminal) = kotoml("get", "-f", file("a = 1\n"), ".b")
        assertEquals(3, code)
        assertEquals("", terminal.out.toString())
        assertTrue("no key" in terminal.err.toString())
    }

    @Test
    fun `a value from a CRLF file prints with a bare newline`() {
        val (_, terminal) = kotoml("get", "-f", file("a = 1\r\n[t]\r\nx = \"y\"\r\n"), "-r", ".t.x")
        assertEquals("y\n", terminal.out.toString())
    }

    @Test
    fun `a document prints with each line's own ending`() {
        val text = "a = 1\r\nb = 2\n"
        val (_, terminal) = kotoml("get", "-f", file(text), ".")
        assertEquals(text, terminal.out.toString())
    }

    @Test
    fun `times print as JSON with their seconds`() {
        val (_, terminal) = kotoml("get", "-f", file("lt = 07:32\nodt = 1979-05-27T07:32Z\n"), "--json", ".")
        assertEquals("{\"lt\":\"07:32:00\",\"odt\":\"1979-05-27T07:32:00Z\"}", terminal.out.toString().trim())
    }

    @Test
    fun `an error names the file it is in`() {
        val bad = file("a = 1\na = 2\n")
        val (_, terminal) = kotoml("check", bad)
        assertTrue(terminal.err.toString().startsWith("error: $bad: "), terminal.err.toString())
    }

    @Test
    fun `check takes several files and names each`() {
        val good = file("a = 1\n")
        val bad = file("a = 1\na = 2\n")
        val (code, terminal) = kotoml("check", good, bad)
        assertEquals(1, code)
        assertTrue("$good: OK" in terminal.out.toString(), terminal.out.toString())
        assertTrue("error: $bad: " in terminal.err.toString(), terminal.err.toString())
    }

    @Test
    fun `check of several files reports each in the order it was given`() {
        val first = file("a = 1\n")
        val bad = file("a = 1\na = 2\n")
        val last = file("b = 1\n")
        val (code, terminal) = kotoml("check", first, bad, last)
        assertEquals(1, code)
        val reported = terminal.both.lines().filter { it.startsWith("error: ") || it.endsWith(": OK") }
        assertEquals(listOf("$first: OK", "error: $bad: TOML parse error at line 2, column 1", "$last: OK"), reported)
    }

    @Test
    fun `get takes its file through --file in every spelling`() {
        val toml = file("a = 1\n")
        for (args in listOf(listOf("-f", toml, ".a"), listOf("--file", toml, ".a"), listOf("--file=$toml", ".a"))) {
            val (code, terminal) = kotoml("get", *args.toTypedArray())
            assertEquals(0, code, terminal.err.toString())
            assertEquals("1\n", terminal.out.toString(), args.toString())
        }
    }

    @Test
    fun `a file after the filter is a usage error`() {
        val toml = file("a = 1\n")
        val (code, terminal) = kotoml("get", ".a", toml)
        assertEquals(2, code)
        assertEquals("error: unexpected extra argument: $toml\n", terminal.err.toString())
    }

    @Test
    fun `an index past the end exits 3`() {
        val (code, terminal) = kotoml("get", "-f", file("a = [1]\n"), ".a[2]")
        assertEquals(3, code)
        assertEquals("error: no index 2 at .a[2], it has 1\n", terminal.err.toString())
    }

    @Test
    fun `check of one file prints OK and of several names each valid one`() {
        val good = file("a = 1\n")
        val other = file("b = 1\n")
        assertEquals("OK\n", kotoml("check", good).second.out.toString())
        val (code, terminal) = kotoml("check", good, other)
        assertEquals(0, code)
        assertEquals("$good: OK\n$other: OK\n", terminal.out.toString())
        assertEquals("", terminal.err.toString())
    }

    @Test
    fun `check --json of several files prints each with its error`() {
        val good = file("a = 1\n")
        val missing = missingFile()
        val (code, terminal) = kotoml("check", "--json", good, missing)
        assertEquals(1, code)
        assertEquals(
            "[{\"path\":\"$good\",\"valid\":true},{\"path\":\"$missing\",\"valid\":false,\"error\":" +
                "{\"type\":\"ReadFailed\",\"path\":\"$missing\",\"failure\":{\"type\":\"NotFound\"}}}]",
            terminal.out.toString().trim(),
        )
        assertEquals("", terminal.err.toString())
    }

    @Test
    fun `a bare kotoml prints its help on standard error and exits 2`() {
        val (code, terminal) = kotoml()
        assertEquals(2, code)
        assertEquals("", terminal.out.toString())
        assertTrue("usage: kotoml" in terminal.err.toString(), terminal.err.toString())
    }

    @Test
    fun `kotoml --help prints its help on standard output`() {
        val (code, terminal) = kotoml("--help")
        assertEquals(0, code)
        assertTrue("usage: kotoml" in terminal.out.toString(), terminal.out.toString())
    }

    @Test
    fun `kotoml offers no --color option`() {
        val (_, help) = kotoml("--help-all")
        assertTrue("--color" !in help.out.toString(), help.out.toString())
        val (code, _) = kotoml("--color=always", "--help")
        assertEquals(2, code)
    }

    @Test
    fun `decode reads a file`() {
        val (code, terminal) = kotoml("decode", file("a = 1\n"))
        assertEquals(0, code)
        assertEquals("{\"a\":{\"type\":\"integer\",\"value\":\"1\"}}\n", terminal.out.toString())
    }

    @Test
    fun `encode names the file its JSON error is in`() {
        val bad = file("{\"a\":1}")
        val (code, terminal) = kotoml("encode", bad)
        assertEquals(1, code)
        assertEquals(
            "error: $bad: not toml-test's tagged JSON at $.a: expected a {\"type\":…,\"value\":…} object, a table " +
                "or an array\n",
            terminal.err.toString(),
        )
    }

    @Test
    fun `encode reads a file and ends its text with one newline`() {
        val (code, terminal) = kotoml("encode", file("{\"a\":{\"type\":\"integer\",\"value\":\"1\"}}"))
        assertEquals(0, code)
        assertEquals("a = 1\n", terminal.out.toString())
    }
}
