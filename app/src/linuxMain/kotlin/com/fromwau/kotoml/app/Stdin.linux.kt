package com.fromwau.kotoml.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.read

@OptIn(ExperimentalForeignApi::class)
internal actual fun readStdinChunk(buffer: ByteArray): Int =
    buffer.usePinned { pinned -> read(0, pinned.addressOf(0), buffer.size.convert()) }.toInt()
