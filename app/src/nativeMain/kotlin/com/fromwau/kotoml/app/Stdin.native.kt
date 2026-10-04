package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.strerror

/**
 * Reads the next bytes of standard input into [buffer], exactly as written: the count read, 0 at the end, or
 * a negative number when the read failed.
 */
internal expect fun readStdinChunk(buffer: ByteArray): Int

@OptIn(ExperimentalForeignApi::class)
internal actual fun readStdin(): Result<ByteArray, ReadFailure> {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = readStdinChunk(buffer)
        if (count == 0) break
        if (count < 0 && errno == EINTR) continue
        if (count < 0) return Err(ReadFailure.Io(strerror(errno)?.toKString()))
        chunks += buffer.copyOf(count)
    }
    val bytes = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return Ok(bytes)
}
