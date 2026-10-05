package com.fromwau.kotoml.app

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.klap.run
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The README's exit codes and `--json` output, from whole `kotoml` invocations. */
class ExitCodeTest {

    private open class Recorded : Terminal {
        val out = StringBuilder()
        val err = StringBuilder()

        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }
    }

    private val files = mutableListOf<Path>()

    private fun file(text: String): String {
        val path = Path(SystemTemporaryDirectory, "kotoml-${Random.nextLong().toULong()}.toml")
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
        files += path
        return path.toString()
    }

    @AfterTest
    fun deleteFiles() {
        for (path in files) SystemFileSystem.delete(path)
    }

    private fun kotoml(vararg args: String): Pair<Int, Recorded> {
        val terminal = Recorded()
        return kotomlCli().run(args.toList(), terminal) to terminal
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
        val (code, terminal) = kotoml("check", "--json", file("a = 1\na = 2\n"))
        assertEquals(1, code)
        assertEquals(
            "{\"error\":{\"type\":\"InvalidToml\",\"error\":" +
                "{\"reason\":{\"type\":\"DuplicateKey\",\"keyPath\":[\"a\"]}," +
                "\"span\":{\"start\":6,\"end\":7},\"position\":{\"line\":2,\"column\":1}," +
                "\"context\":{\"start\":0,\"end\":1}}},\"code\":1}",
            terminal.err.toString().trim(),
        )
    }

    @Test
    fun `an input that cannot be read exits 1`() {
        val missing = Path(SystemTemporaryDirectory, "kotoml-missing-${Random.nextLong().toULong()}.toml").toString()
        assertEquals(1, kotoml("check", missing).first)
    }

    @Test
    fun `a filter that finds nothing exits 1`() {
        assertEquals(1, kotoml("get", ".b", file("a = 1\n")).first)
    }

    @Test
    fun `a filter that does not parse exits 2`() {
        assertEquals(2, kotoml("get", ".[", file("a = 1\n")).first)
    }

    @Test
    fun `an unknown option exits 2`() {
        assertEquals(2, kotoml("check", "--nope", file("a = 1\n")).first)
    }

    @Test
    fun `get prints what the filter selects with exit 0`() {
        val (code, terminal) = kotoml("get", ".a", file("a = 1\n"))
        assertEquals(0, code)
        assertEquals("1", terminal.out.toString().trim())
    }

    @Test
    fun `output the system refused exits 1 and says so`() {
        val terminal = FailingWrites(WriteError.Refused("No space left on device"))
        assertEquals(1, kotomlCli().run(listOf("get", ".a", file("a = 1\n")), terminal))
        assertEquals("error: cannot write to standard output: No space left on device\n", terminal.err.toString())
    }

    @Test
    fun `a reader that closes the pipe ends the run with 141 and no message`() {
        val terminal = FailingWrites(WriteError.BrokenPipe)
        assertEquals(141, kotomlCli().run(listOf("get", ".a", file("a = 1\n")), terminal))
        assertEquals("", terminal.err.toString())
    }

    @Ignore
    @Test
    fun `a filter that finds nothing exits 3 and says so on standard error`() {
        val (code, terminal) = kotoml("get", ".b", file("a = 1\n"))
        assertEquals(3, code)
        assertEquals("", terminal.out.toString())
        assertTrue("no key" in terminal.err.toString())
    }

    @Ignore
    @Test
    fun `a value from a CRLF file prints with a bare newline`() {
        val (_, terminal) = kotoml("get", "-r", ".t.x", file("a = 1\r\n[t]\r\nx = \"y\"\r\n"))
        assertEquals("y\n", terminal.out.toString())
    }

    @Ignore
    @Test
    fun `a document prints with each line's own ending`() {
        val text = "a = 1\r\nb = 2\n"
        val (_, terminal) = kotoml("get", ".", file(text))
        assertEquals(text, terminal.out.toString())
    }

    @Ignore
    @Test
    fun `times print as JSON with their seconds`() {
        val (_, terminal) = kotoml("get", "--json", ".", file("lt = 07:32\nodt = 1979-05-27T07:32Z\n"))
        assertEquals("{\"lt\":\"07:32:00\",\"odt\":\"1979-05-27T07:32:00Z\"}", terminal.out.toString().trim())
    }

    @Ignore
    @Test
    fun `an error names the file it is in`() {
        val bad = file("a = 1\na = 2\n")
        val (_, terminal) = kotoml("check", bad)
        assertTrue(terminal.err.toString().startsWith("error: $bad: "), terminal.err.toString())
    }

    @Ignore
    @Test
    fun `check takes several files and names each`() {
        val good = file("a = 1\n")
        val bad = file("a = 1\na = 2\n")
        val (code, terminal) = kotoml("check", good, bad)
        assertEquals(1, code)
        assertTrue("$good: OK" in terminal.out.toString(), terminal.out.toString())
        assertTrue("error: $bad: " in terminal.err.toString(), terminal.err.toString())
    }

    @Ignore
    @Test
    fun `encode reads a file and ends its text with one newline`() {
        val (code, terminal) = kotoml("encode", file("{\"a\":{\"type\":\"integer\",\"value\":\"1\"}}"))
        assertEquals(0, code)
        assertEquals("a = 1\n", terminal.out.toString())
    }
}
