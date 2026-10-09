package com.fromwau.kotoml.app

import com.fromwau.kern.terminal.Terminal
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What Tab reads to complete `get`'s filter, when `-f` names something other than a TOML file. */
class CompletionInputTest {

    private class Recorded : Terminal {
        val out = StringBuilder()

        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) = Unit
    }

    @Test
    fun `a pipe named by --file is not read on Tab`() {
        val dir = Files.createTempDirectory("kotoml-fifo").toFile()
        try {
            val fifo = File(dir, "fifo")
            // Opening a pipe for reading blocks until something writes to it, which nothing does here.
            val made = runCatching { ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0 }.getOrDefault(false)
            if (!made) {
                println("test skipped because mkfifo is not installed")
                return
            }
            val terminal = Recorded()
            val tab = Thread {
                runKotoml(listOf("__complete", "--cursor", "3", "--", "get", "-f", fifo.path, "."), terminal) { null }
            }.apply {
                isDaemon = true
                start()
                join(10_000)
            }
            assertTrue(!tab.isAlive, "Tab blocked reading the pipe")
            assertEquals("", terminal.out.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
