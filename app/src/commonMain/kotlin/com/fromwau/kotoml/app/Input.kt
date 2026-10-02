package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Every byte of standard input, or the system's account of why it could not be read. */
internal expect fun readStdin(): Result<ByteArray, ReadFailure>

/** Every byte of the file at [path], or of standard input when [path] is `-` or null. */
internal fun readInput(path: String?): Result<ByteArray, AppError.ReadFailed> {
    val name = path ?: "-"
    val read = if (name == "-") readStdin() else readFile(Path(name))
    return when (read) {
        is Result.Success -> read
        is Result.Error -> Err(AppError.ReadFailed(name, read.error))
    }
}

private fun readFile(path: Path): Result<ByteArray, ReadFailure> {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return Err(ReadFailure.NotFound)
    if (metadata.isDirectory) return Err(ReadFailure.IsADirectory)
    return try {
        Ok(SystemFileSystem.source(path).buffered().use { it.readByteArray() })
    } catch (error: IOException) {
        Err(ReadFailure.Io(error.message))
    }
}
