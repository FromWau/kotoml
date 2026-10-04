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

/** Whether this process may read the file at [path]; kotlinx-io reports a refusal only as a sentence. */
internal expect fun isReadable(path: String): Boolean

/** Why the file at [path] could not be looked up, after kotlinx-io refused with the sentence [detail]. */
internal expect fun statFailure(
    path: String,
    detail: String?,
): ReadFailure

private fun readFile(path: Path): Result<ByteArray, ReadFailure> {
    val metadata = try {
        SystemFileSystem.metadataOrNull(path)
    } catch (error: IOException) {
        // On Kotlin/Native, every stat failure but a missing file throws, such as a path through a regular file.
        return Err(statFailure(path.toString(), error.message))
    } ?: return Err(ReadFailure.NotFound)
    if (metadata.isDirectory) return Err(ReadFailure.IsADirectory)
    if (!isReadable(path.toString())) return Err(ReadFailure.PermissionDenied)
    return try {
        Ok(SystemFileSystem.source(path).buffered().use { it.readByteArray() })
    } catch (error: IOException) {
        Err(ReadFailure.Io(error.message))
    }
}
