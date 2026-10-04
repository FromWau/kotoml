package com.fromwau.kotoml.app

import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.run
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The README's exit codes and `--json` output, from whole `kotoml` invocations. */
class ExitCodeTest {

    private class Recorded : Terminal {
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

    @Test
    fun `a valid file checks with exit 0`() {
        assertEquals(0, kotoml("check", file("a = 1\n")).first)
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
            "{\"error\":{\"type\":\"InvalidToml\",\"error\":{\"reason\":{\"type\":\"DuplicateKey\",\"key\":\"a\"}," +
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
}
