package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionTest {

    @Test
    fun `a file this process may not read is refused as permission denied`() {
        val file = File.createTempFile("kotoml", ".toml")
        try {
            file.writeText("a = 1\n")
            file.setReadable(false, false)
            // Root reads every file, so there is nothing to refuse.
            if (file.canRead()) return
            assertEquals(
                Result.Error(AppError.ReadFailed(file.path, ReadFailure.PermissionDenied)),
                readInput(file.path),
            )
        } finally {
            file.delete()
        }
    }
}
