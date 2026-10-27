package com.fromwau.kotoml.app

import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asserts that `get` refuses the input at [path] as no file of [kind], saying [sentence] after its name, and that
 * `--json` names the same [kind].
 */
internal fun assertNotAFile(
    path: String,
    kind: String,
    sentence: String,
) {
    val human = RecordingTerminal()
    assertEquals(1, runKotoml(listOf("get", "-f", path, ".a"), human))
    assertEquals("error: $path: $sentence\n", human.err)

    val json = RecordingTerminal()
    assertEquals(1, runKotoml(listOf("--json", "get", "-f", path, ".a"), json))
    assertEquals(
        "{\"error\":{\"type\":\"ReadFailed\",\"path\":\"$path\"," +
            "\"failure\":{\"type\":\"NotAFile\",\"kind\":\"$kind\"}},\"code\":1}",
        json.err.trim(),
    )
}

class NotAFileTest {

    @Test
    fun `a folder is no file and reads as a directory`() {
        val folder = Path(SystemTemporaryDirectory, "kotoml-folder-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(folder)
        try {
            assertNotAFile(folder.toString(), kind = "Directory", sentence = "is a directory")
        } finally {
            SystemFileSystem.delete(folder)
        }
    }
}
