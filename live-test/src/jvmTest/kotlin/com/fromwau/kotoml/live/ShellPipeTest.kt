package com.fromwau.kotoml.live

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The native `kotoml` in a pipeline of each shell: reading TOML piped into it, and writing into a reader that
 * stops early. A shell that is not installed is left out, with a note.
 */
class ShellPipeTest {

    private val binary = checkNotNull(System.getProperty("kotoml.binary")) { "kotoml.binary is not set" }
    private val outputs: File = Files.createTempDirectory("kotoml-pipe-out").toFile()

    @AfterTest
    fun cleanUp() {
        outputs.deleteRecursively()
    }

    /** What one run printed: standard output trimmed, and standard error as written. */
    private data class Ran(val out: String, val err: String)

    /** Runs [script] in [shell], with `$KOTOML` naming the binary. */
    private fun run(
        shell: Shell,
        script: String,
    ): Ran {
        val output = File(outputs, "stdout")
        val errors = File(outputs, "stderr")
        val process = ProcessBuilder(shell.command + script)
            .directory(files)
            .redirectOutput(output)
            .redirectError(errors)
            .apply { environment()["KOTOML"] = binary }
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        assertTrue(finished, "${shell.executable} did not finish: $script")
        return Ran(output.readText().replace("\r", "").trim(), errors.readText())
    }

    private enum class Shell(val executable: String, val command: List<String>) {
        BASH("bash", listOf("bash", "--norc", "--noprofile", "-c")),
        ZSH("zsh", listOf("zsh", "-f", "-c")),
        FISH("fish", listOf("fish", "--no-config", "-c")),
        POWERSHELL("pwsh", listOf("pwsh", "-NoProfile", "-NonInteractive", "-Command")),
    }

    private fun installed(): List<Shell> = Shell.entries.filter { it in installedShells }

    private companion object {
        // Written once for the class: more lines than a pipe holds, so a reader closes it while kotoml writes.
        val files: File by lazy {
            Files.createTempDirectory("kotoml-pipe").toFile().also { dir ->
                File(dir, "big.toml").writeText((1..25_000).joinToString("\n", postfix = "\n") { "k$it = $it" })
            }
        }

        @AfterAll
        @JvmStatic
        fun deleteFiles() {
            files.deleteRecursively()
        }

        // Looked up once per run, so each missing shell is reported once rather than by every test. Windows
        // names an executable with one of the extensions PATHEXT lists, `pwsh.exe` for one.
        val installedShells: Set<Shell> by lazy {
            val path = System.getenv("PATH").split(File.pathSeparator)
            val extensions = listOf("") + System.getenv("PATHEXT").orEmpty().split(';').filter { it.isNotEmpty() }
            Shell.entries.filterTo(mutableSetOf()) { shell ->
                val found = path.any { dir -> extensions.any { File(dir, shell.executable + it).canExecute() } }
                if (!found) println("tests skipped because ${shell.executable} shell not installed")
                found
            }
        }
    }

    @Test
    fun `every shell pipes TOML into get`() {
        for (shell in installed()) {
            val script = when (shell) {
                Shell.POWERSHELL -> "'a = 1' | & \$env:KOTOML get .a"
                else -> "printf 'a = 1\\n' | \"\$KOTOML\" get .a"
            }
            assertEquals(Ran("1", ""), run(shell, script), "$shell")
        }
    }

    @Test
    fun `every shell pipes get into a reader that stops after one line`() {
        for (shell in installed()) {
            val script = when (shell) {
                // PowerShell keeps a native program before `head` running once `head` exits, as with `yes`;
                // Select-Object is its own way to stop a pipeline after the first line.
                Shell.POWERSHELL -> "& \$env:KOTOML get -f big.toml '.[]' | Select-Object -First 1"
                else -> "\"\$KOTOML\" get -f big.toml '.[]' | head -1"
            }
            // A closed pipe ends kotoml quietly, so standard error stays empty.
            assertEquals(Ran("1", ""), run(shell, script), "$shell")
        }
    }

    @Test
    fun `a shell that reports a pipeline's statuses sees 141 from get`() {
        for (shell in installed().filter { it != Shell.POWERSHELL }) {
            val status = when (shell) {
                Shell.BASH -> "\${PIPESTATUS[0]}"
                Shell.ZSH -> "\${pipestatus[1]}"
                else -> "\$pipestatus[1]"
            }
            val script = "\"\$KOTOML\" get -f big.toml '.[]' | head -1 > /dev/null; echo $status"
            assertEquals(Ran("141", ""), run(shell, script), "$shell")
        }
    }
}
