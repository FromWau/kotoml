package com.fromwau.kotoml.app

import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tab completion of `get`'s filter: the keys of the file `--file` names, as the shell scripts ask for them. */
class FilterCompletionTest {

    private val sample = """
        |title = "kotoml"
        |"quoted key" = 1
        |
        |[owner]
        |name = "Tom"
        |dob = 1979-05-27
        |
        |[[servers]]
        |host = "alpha"
        |
        |[[servers]]
        |host = "beta"
        |
        |[database]
        |ports = [8000, 8001]
        |temp = { cpu = 79.5 }
        |""".trimMargin()

    private val files = mutableListOf<Path>()

    private fun file(text: String = sample): String {
        val path = Path(SystemTemporaryDirectory, "kotoml-${Random.nextLong().toULong()}.toml")
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
        files += path
        return path.toString()
    }

    @AfterTest
    fun deleteFiles() {
        for (path in files) SystemFileSystem.delete(path)
    }

    /** What Tab offers for the filter [typed], with [before] the words between `get` and it. */
    private fun completions(
        typed: String,
        vararg before: String,
        home: Path? = null,
    ): List<String> {
        val terminal = RecordingTerminal()
        val words = listOf("get") + before + typed
        runKotoml(listOf("__complete", "--cursor", "${words.lastIndex}", "--") + words, terminal) { home }
        return terminal.out.lines().filter { it.isNotEmpty() }
    }

    @Test
    fun `the root offers every top level key with its type`() {
        assertEquals(
            listOf(
                ".title\tstring",
                ".\"quoted key\"\tinteger",
                ".owner\ttable",
                ".servers\tarray of tables",
                ".database\ttable",
            ),
            completions(".", "-f", file()),
        )
    }

    @Test
    fun `nothing typed yet offers the root keys`() {
        assertEquals(".title\tstring", completions("", "-f", file()).first())
    }

    @Test
    fun `a partial key offers the keys it starts`() {
        assertEquals(listOf(".owner\ttable"), completions(".ow", "-f", file()))
    }

    @Test
    fun `a table offers its own keys`() {
        assertEquals(listOf(".owner.name\tstring", ".owner.dob\tdate-time"), completions(".owner.", "-f", file()))
        assertEquals(listOf(".database.temp\tinline table"), completions(".database.te", "-f", file()))
        assertEquals(listOf(".database.temp.cpu\tfloat"), completions(".database.temp.", "-f", file()))
    }

    @Test
    fun `an array offers iteration and each index`() {
        // `[]` selects each element in turn, so it is described as an element is.
        assertEquals(
            listOf(".servers[]\ttable", ".servers[0]\ttable", ".servers[1]\ttable"),
            completions(".servers[", "-f", file()),
        )
        assertEquals(
            listOf(".database.ports[]\tinteger", ".database.ports[0]\tinteger", ".database.ports[1]\tinteger"),
            completions(".database.ports[", "-f", file()),
        )
    }

    @Test
    fun `a step reaching items of different types names each type`() {
        val text = "mixed = [1, \"a\"]\n\n[[s]]\np = 1\n\n[[s]]\np = \"x\"\n"
        assertEquals(listOf(".mixed[]\tinteger or string"), completions(".mixed[]", "-f", file(text)))
        assertEquals(listOf(".s[].p\tinteger or string"), completions(".s[].", "-f", file(text)))
    }

    @Test
    fun `a step over several arrays names each type once`() {
        val text = "none = []\n\n[[s]]\nm = [1, \"a\"]\n\n[[s]]\nm = [\"b\"]\n\n[[e]]\nm = []\n\n[[e]]\nm = [1]\n"
        assertEquals(".s[].m[]\tinteger or string", completions(".s[].m[", "-f", file(text)).first())
        // An empty array has no element to add a type, unless it is the only one there is.
        assertEquals(".e[].m[]\tinteger", completions(".e[].m[", "-f", file(text)).first())
        assertEquals(listOf(".none[]\tempty array"), completions(".none[", "-f", file(text)))
    }

    @Test
    fun `the home directory is looked up only for a path that starts with a tilde`() {
        var lookups = 0
        fun run(vararg args: String) = runKotoml(args.toList(), RecordingTerminal()) { lookups++; null }
        run("get", "-f", file(), ".title")
        run("__complete", "--cursor", "3", "--", "get", "-f", file(), ".")
        // `~bob` names another user's home, which kotoml leaves to the shell.
        run("get", "-f", "~bob/x.toml", ".title")
        assertEquals(0, lookups)
        run("__complete", "--cursor", "3", "--", "get", "-f", "~/x.toml", ".")
        assertEquals(1, lookups)
        // Once a run however many paths start with it.
        run("check", "~/a.toml", "~/b.toml")
        assertEquals(2, lookups)
    }

    @Test
    fun `an element offers its keys`() {
        assertEquals(listOf(".servers[1].host\tstring"), completions(".servers[1].", "-f", file()))
        assertEquals(listOf(".servers[].host\tstring"), completions(".servers[].", "-f", file()))
    }

    @Test
    fun `a stage after a pipe offers the keys of what the stage before it selects`() {
        assertEquals(listOf(".servers[] | .host\tstring"), completions(".servers[] | .", "-f", file()))
    }

    @Test
    fun `a dot inside a quoted key does not start a step`() {
        assertEquals(listOf(".\"a.b\".c\tinteger"), completions(".\"a.b\".", "-f", file("\"a.b\" = { c = 1 }\n")))
    }

    @Test
    fun `a file named from the home directory offers its keys`() {
        // The shell expands `~` before a real run, but completion hands the file over as typed.
        val path = Path(file())
        // A file made with no temporary directory set has no parent: it lies in the working directory.
        val home = path.parent ?: Path(".")
        assertEquals(listOf(".owner\ttable"), completions(".ow", "-f", "~/${path.name}", home = home))
    }

    @Test
    fun `other options may stand before the filter`() {
        assertEquals(listOf(".owner\ttable"), completions(".ow", "-r", "-f", file(), "--toml", "1.1"))
    }

    @Test
    fun `a value offers nothing to step into`() {
        assertEquals(emptyList(), completions(".title.", "-f", file()))
    }

    @Test
    fun `without a readable TOML file nothing is offered`() {
        assertEquals(emptyList(), completions("."))
        assertEquals(emptyList(), completions(".", "-f", "-"))
        assertEquals(emptyList(), completions(".", "-f", file("a = = 1\n")))
        assertEquals(emptyList(), completions(".", "-f", "${file()}.missing"))
    }
}
