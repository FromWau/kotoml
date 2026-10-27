package com.fromwau.kotoml.app

import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.kern.terminal.test.Write
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The README's exit codes and `--json` output, from whole `kotoml` invocations. */
class ExitCodeTest {

    private val files = mutableListOf<Path>()

    private fun file(
        text: String,
        name: String = "kotoml",
    ): String {
        val path = Path(SystemTemporaryDirectory, "$name-${Random.nextLong().toULong()}.toml")
        val sink = SystemFileSystem.sink(path)
        files += path
        sink.buffered().use { it.writeString(text) }
        return path.toString()
    }

    /** A file with the [name] given, or null with a note where the file system refuses such a name, as Windows does. */
    private fun namedFile(
        name: String,
        text: String,
    ): String? = try {
        file(text, name)
    } catch (_: IOException) {
        println("test skipped because the file system refuses the name ${inputName(name)}")
        null
    }

    private fun missingFile(): String =
        Path(SystemTemporaryDirectory, "kotoml-missing-${Random.nextLong().toULong()}.toml").toString()

    @AfterTest
    fun deleteFiles() {
        for (path in files) SystemFileSystem.delete(path)
    }

    private fun kotoml(vararg args: String): Pair<Int, RecordingTerminal> {
        val terminal = RecordingTerminal()
        return runKotoml(args.toList(), terminal) to terminal
    }

    @Test
    fun `a valid file checks with exit 0 and prints its name and OK`() {
        val toml = file("a = 1\n")
        val (code, terminal) = kotoml("check", toml)
        assertEquals(0, code)
        assertEquals("$toml: OK\n", terminal.out)
    }

    @Test
    fun `check --json prints valid true for a valid file`() {
        val (code, terminal) = kotoml("check", "--json", file("a = 1\n"))
        assertEquals(0, code)
        assertEquals("{\"valid\":true}", terminal.out.trim())
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
            terminal.err.trim(),
        )
    }

    @Test
    fun `an input that cannot be read exits 1`() {
        assertEquals(1, kotoml("check", missingFile()).first)
    }

    @Test
    fun `a name longer than a file name may have is refused with its length`() {
        val path = Path(SystemTemporaryDirectory, "a".repeat(300)).toString()
        val (code, terminal) = kotoml("get", "-f", path, ".a")
        assertEquals(1, code)
        assertEquals(
            "error: $path: a name in the path is 300 bytes, longer than the 255 a file name may have\n",
            terminal.err,
        )
        val (jsonCode, json) = kotoml("--json", "get", "-f", path, ".a")
        assertEquals(1, jsonCode)
        assertEquals(
            "{\"error\":{\"type\":\"ReadFailed\",\"path\":\"$path\"," +
                "\"failure\":{\"type\":\"NameTooLong\",\"bytes\":300}},\"code\":1}",
            json.err.trim(),
        )
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
        assertEquals("1", terminal.out.trim())
    }

    @Test
    fun `output the system refused exits 1 and says so`() {
        val terminal = RecordingTerminal().apply { failWith(WriteError.Refused(Stream.Out, "No space left on device")) }
        assertEquals(1, runKotoml(listOf("get", "-f", file("a = 1\n"), ".a"), terminal))
        assertEquals("error: cannot write to standard output: No space left on device\n", terminal.err)
    }

    @Test
    fun `a write standard error refused exits 1 after trying to report it there and keeps standard output`() {
        val terminal = RecordingTerminal().apply { failWith(WriteError.Refused(Stream.Err, "No space left on device")) }
        assertEquals(1, runKotoml(listOf("get", "-f", file("a = 1\n"), ".a"), terminal))
        assertEquals("1\n", terminal.out)
        assertEquals(
            listOf(Write(Stream.Err, "error: cannot write to standard error: No space left on device\n")),
            terminal.lostWrites,
        )
    }

    @Test
    fun `a reader that closes the pipe ends the run with 141 and no message`() {
        val terminal = RecordingTerminal().apply { failWith(WriteError.BrokenPipe(Stream.Out)) }
        assertEquals(141, runKotoml(listOf("get", "-f", file("a = 1\n"), ".a"), terminal))
        assertEquals("", terminal.err)
    }

    @Test
    fun `a filter that finds nothing exits 3 and says so on standard error`() {
        val (code, terminal) = kotoml("get", "-f", file("a = 1\n"), ".b")
        assertEquals(3, code)
        assertEquals("", terminal.out)
        assertTrue("no key" in terminal.err)
    }

    @Test
    fun `a value from a CRLF file prints with a bare newline`() {
        val (_, terminal) = kotoml("get", "-f", file("a = 1\r\n[t]\r\nx = \"y\"\r\n"), "-r", ".t.x")
        assertEquals("y\n", terminal.out)
    }

    @Test
    fun `a document prints with each line's own ending`() {
        val text = "a = 1\r\nb = 2\n"
        val (_, terminal) = kotoml("get", "-f", file(text), ".")
        assertEquals(text, terminal.out)
    }

    @Test
    fun `times print as JSON with their seconds`() {
        val (_, terminal) = kotoml("get", "-f", file("lt = 07:32\nodt = 1979-05-27T07:32Z\n"), "--json", ".")
        assertEquals("{\"lt\":\"07:32:00\",\"odt\":\"1979-05-27T07:32:00Z\"}", terminal.out.trim())
    }

    @Test
    fun `an error names the file it is in`() {
        val bad = file("a = 1\na = 2\n")
        val (_, terminal) = kotoml("check", bad)
        assertTrue(terminal.err.startsWith("error: $bad: "), terminal.err)
    }

    @Test
    fun `check takes several files and names each`() {
        val good = file("a = 1\n")
        val bad = file("a = 1\na = 2\n")
        val (code, terminal) = kotoml("check", good, bad)
        assertEquals(1, code)
        assertTrue("$good: OK" in terminal.out, terminal.out)
        assertTrue("error: $bad: " in terminal.err, terminal.err)
    }

    @Test
    fun `check of several files reports each in the order it was given`() {
        val first = file("a = 1\n")
        val bad = file("a = 1\na = 2\n")
        val last = file("b = 1\n")
        val (code, terminal) = kotoml("check", first, bad, last)
        assertEquals(1, code)
        assertEquals(
            listOf(
                Write(Stream.Out, "$first: OK\n"),
                Write(
                    Stream.Err,
                    "error: $bad: TOML parse error at line 2, column 1\n  |\n2 | a = 2\n  | ^\nduplicate key `a`\n",
                ),
                Write(Stream.Out, "$last: OK\n"),
            ),
            terminal.writes,
        )
    }

    @Test
    fun `check of several files escapes a control character in a valid file's name as in an error`() {
        val named = namedFile("kotoml-\u001b[31m", "a = 1\n") ?: return
        val (_, terminal) = kotoml("check", named, file("b = 1\n"))
        assertTrue('\u001b' !in terminal.out, terminal.out)
        assertTrue("kotoml-\\x1B[31m-" in terminal.out, terminal.out)
    }

    @Test
    fun `get reads a file named from the home directory in either spelling`() {
        // Where the shell leaves `~` as typed, `--file=~/x` or a quoted `-f '~/x'`, kotoml resolves it as Tab does.
        val toml = Path(file("a = 1\n"))
        val home = toml.parent ?: Path(".")
        for (args in listOf(listOf("-f", "~/${toml.name}", ".a"), listOf("--file=~/${toml.name}", ".a"))) {
            val terminal = RecordingTerminal()
            assertEquals(0, runKotoml(listOf("get") + args, terminal) { home }, terminal.err)
            assertEquals("1\n", terminal.out, args.toString())
        }
    }

    @Test
    fun `check of several files keeps a valid file's name on its one line`() {
        val named = namedFile("kotoml-\nfake.toml: OK\n", "a = 1\n") ?: return
        val (_, terminal) = kotoml("check", named, file("b = 1\n"))
        assertEquals(2, terminal.out.lines().count { it.isNotEmpty() }, terminal.out)
        assertTrue("kotoml-\\x0Afake.toml: OK\\x0A-" in terminal.out, terminal.out)
    }

    @Test
    fun `an error keeps an invalid file's name on its one line`() {
        val named = namedFile("kotoml-\nother.toml: OK\n", "a = \n") ?: return
        val (_, terminal) = kotoml("check", file("b = 1\n"), named)
        assertTrue("\nother.toml: OK" !in terminal.err, terminal.err)
        assertTrue("kotoml-\\x0Aother.toml: OK\\x0A-" in terminal.err, terminal.err)
    }

    @Test
    fun `check --json names a file in its error as it was typed`() {
        val terminal = RecordingTerminal()
        val home = Path(file("a = 1\n")).parent ?: Path(".")
        runKotoml(listOf("check", "--json", "~/kotoml-missing.toml", file("b = 1\n")), terminal) { home }
        val missing = Json.parseToJsonElement(terminal.out).jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("~/kotoml-missing.toml"), missing.getValue("error").jsonObject["path"])
    }

    @Test
    fun `every command reads a file named from the home directory`() {
        val toml = Path(file("a = 1\n"))
        val tagged = Path(file("{\"a\": {\"type\": \"integer\", \"value\": \"1\"}}"))
        val home = toml.parent ?: Path(".")
        val runs = listOf(
            listOf("check", "~/${toml.name}") to "~/${toml.name}: OK\n",
            listOf("decode", "~/${toml.name}") to "{\"a\":{\"type\":\"integer\",\"value\":\"1\"}}\n",
            listOf("encode", "~/${tagged.name}") to "a = 1\n",
        )
        for ((args, out) in runs) {
            val terminal = RecordingTerminal()
            assertEquals(0, runKotoml(args, terminal) { home }, "$args: ${terminal.err}")
            assertEquals(out, terminal.out, "$args")
        }
    }

    @Test
    fun `get says when the home directory a file is named from is unknown`() {
        val terminal = RecordingTerminal()
        assertEquals(1, runKotoml(listOf("get", "-f", "~/a.toml", ".a"), terminal) { null })
        assertEquals(
            "error: ~/a.toml: the home directory is unknown, so ~ cannot be resolved\n",
            terminal.err,
        )
    }

    @Test
    fun `get takes its file through --file in every spelling`() {
        val toml = file("a = 1\n")
        for (args in listOf(listOf("-f", toml, ".a"), listOf("--file", toml, ".a"), listOf("--file=$toml", ".a"))) {
            val (code, terminal) = kotoml("get", *args.toTypedArray())
            assertEquals(0, code, terminal.err)
            assertEquals("1\n", terminal.out, args.toString())
        }
    }

    @Test
    fun `a file after the filter is a usage error`() {
        val toml = file("a = 1\n")
        val (code, terminal) = kotoml("get", ".a", toml)
        assertEquals(2, code)
        assertEquals("error: unexpected extra argument: $toml\n", terminal.err)
    }

    @Test
    fun `an index past the end exits 3`() {
        val (code, terminal) = kotoml("get", "-f", file("a = [1]\n"), ".a[2]")
        assertEquals(3, code)
        assertEquals("error: no index 2 at .a[2], it has 1\n", terminal.err)
    }

    @Test
    fun `check names each valid file it checks alone or among several`() {
        val good = file("a = 1\n")
        val other = file("b = 1\n")
        assertEquals("$good: OK\n", kotoml("check", good).second.out)
        val (code, terminal) = kotoml("check", good, other)
        assertEquals(0, code)
        assertEquals("$good: OK\n$other: OK\n", terminal.out)
        assertEquals("", terminal.err)
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
            terminal.out.trim(),
        )
        assertEquals("", terminal.err)
    }

    @Test
    fun `a bare kotoml prints its help on standard output and exits 0`() {
        val (code, terminal) = kotoml()
        assertEquals(0, code, terminal.err)
        assertEquals("", terminal.err)
        assertTrue("usage: kotoml" in terminal.out, terminal.out)
    }

    @Test
    fun `kotoml --version prints its name and version`() {
        val (code, terminal) = kotoml("--version")
        assertEquals(0, code, terminal.err)
        assertEquals("kotoml $VERSION\n", terminal.out)
    }

    @Test
    fun `kotoml --help prints its help on standard output`() {
        val (code, terminal) = kotoml("--help")
        assertEquals(0, code)
        assertTrue("usage: kotoml" in terminal.out, terminal.out)
    }

    @Test
    fun `kotoml -h prints its help on standard output`() {
        val (code, terminal) = kotoml("-h")
        assertEquals(0, code, terminal.err)
        assertTrue("usage: kotoml" in terminal.out, terminal.out)
    }

    @Test
    fun `kotoml --help-all prints the help of every command`() {
        val (code, terminal) = kotoml("--help-all")
        assertEquals(0, code, terminal.err)
        assertTrue("usage: kotoml get" in terminal.out, terminal.out)
    }

    @Test
    fun `an option spelled in part is unknown and suggests its full name`() {
        val (code, terminal) = kotoml("get", "--fil", "x", ".a")
        assertEquals(2, code)
        assertEquals("error: unknown option '--fil'. Did you mean --file?\n", terminal.err)
    }

    @Test
    fun `kotoml offers no --color option`() {
        val (helpCode, help) = kotoml("--help-all")
        assertEquals(0, helpCode, help.err)
        assertTrue("--color" !in help.out, help.out)
        val (code, _) = kotoml("--color=always", "--help")
        assertEquals(2, code)
    }

    @Test
    fun `decode reads a file`() {
        val (code, terminal) = kotoml("decode", file("a = 1\n"))
        assertEquals(0, code)
        assertEquals("{\"a\":{\"type\":\"integer\",\"value\":\"1\"}}\n", terminal.out)
    }

    @Test
    fun `encode names the file its JSON error is in`() {
        val bad = file("{\"a\":1}")
        val (code, terminal) = kotoml("encode", bad)
        assertEquals(1, code)
        assertEquals(
            "error: $bad: not toml-test's tagged JSON at $.a: expected a {\"type\":…,\"value\":…} object, a table " +
                "or an array\n",
            terminal.err,
        )
    }

    @Test
    fun `encode reads a file and ends its text with one newline`() {
        val (code, terminal) = kotoml("encode", file("{\"a\":{\"type\":\"integer\",\"value\":\"1\"}}"))
        assertEquals(0, code)
        assertEquals("a = 1\n", terminal.out)
    }
}
