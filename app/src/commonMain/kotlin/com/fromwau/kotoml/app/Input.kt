package com.fromwau.kotoml.app

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.readBytes
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.mapError
import kotlinx.io.files.Path

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

// streams: a FIFO, such as the one `<(...)` names, is read to its end rather than refused.
private fun readFile(path: Path): Result<ByteArray, ReadFailure> =
    path.readBytes(streams = true).mapError { it.toReadFailure() }

private fun FileError.toReadFailure(): ReadFailure = when (this) {
    is FileError.NotFound -> ReadFailure.NotFound
    is FileError.NotRegularFile -> ReadFailure.NotAFile(type.toFileKind())
    is FileError.NameTooLong -> ReadFailure.NameTooLong(bytes)
    is FileError.Inaccessible -> when (cause) {
        IoCause.Denied -> ReadFailure.PermissionDenied
        IoCause.NotFound -> ReadFailure.NotFound
        else -> ReadFailure.Io(exception?.message)
    }
    else -> ReadFailure.Io(null)
}

private fun FileType.toFileKind(): FileKind = when (this) {
    FileType.Directory -> FileKind.Directory
    FileType.Socket -> FileKind.Socket
    FileType.CharacterDevice -> FileKind.CharacterDevice
    FileType.BlockDevice -> FileKind.BlockDevice
    FileType.Fifo -> FileKind.Fifo
    FileType.Regular, FileType.Symlink, FileType.Other -> FileKind.Other
}
