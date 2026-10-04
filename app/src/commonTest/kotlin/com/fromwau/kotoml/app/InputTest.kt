package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class InputTest {

    @Test
    fun `a path through a regular file is not found`() {
        val file = Path(SystemTemporaryDirectory, "kotoml-${Random.nextLong().toULong()}.toml")
        SystemFileSystem.sink(file).buffered().use { it.writeString("a = 1\n") }
        try {
            val path = "$file/x"
            assertEquals(Result.Error(AppError.ReadFailed(path, ReadFailure.NotFound)), readInput(path))
        } finally {
            SystemFileSystem.delete(file)
        }
    }
}
