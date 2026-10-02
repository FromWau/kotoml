package com.fromwau.kotoml.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix._O_BINARY
import platform.posix._setmode
import platform.posix.read

@OptIn(ExperimentalForeignApi::class)
internal actual fun readStdinChunk(buffer: ByteArray): Int {
    // Windows opens standard input in text mode, which turns CRLF into LF; binary mode passes the bytes as written.
    _setmode(0, _O_BINARY)
    return buffer.usePinned { pinned -> read(0, pinned.addressOf(0), buffer.size.convert()) }
}
