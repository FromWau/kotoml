@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)

package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.ENXIO
import platform.posix.O_NONBLOCK
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.mkfifo
import platform.posix.open
import platform.posix.usleep
import platform.posix.write
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class FifoTest {

    @Test
    fun `a named pipe is read whole`() {
        val fifo = Path(SystemTemporaryDirectory, "kotoml-${Random.nextLong().toULong()}.fifo")
        check(mkfifo(fifo.toString(), 0b110_000_000.convert()) == 0) { "could not make the FIFO $fifo" }
        val writer = Worker.start()
        val reader = Worker.start()
        try {
            val writing = writer.execute(TransferMode.SAFE, { fifo.toString() }) { writeOnceRead(it, "a = 1\n") }
            val reading = reader.execute(TransferMode.SAFE, { fifo.toString() }) { readInput(it) }
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (reading.state == FutureState.SCHEDULED && deadline.hasNotPassedNow()) usleep(1_000u)
            if (reading.state == FutureState.SCHEDULED) {
                // Opening the other end lets the blocked read finish, so its worker can be stopped.
                close(open(fifo.toString(), O_WRONLY or O_NONBLOCK))
                fail("reading the pipe blocked")
            }
            val read = reading.result
            writing.result
            assertEquals("a = 1\n", (read as? Result.Success)?.value?.decodeToString(), "$read")
        } finally {
            writer.requestTermination().result
            reader.requestTermination().result
            SystemFileSystem.delete(fifo)
        }
    }
}

/** Writes [text] into the FIFO at [path] once something reads it, giving up after a while so no test hangs. */
private fun writeOnceRead(
    path: String,
    text: String,
) {
    val bytes = text.encodeToByteArray()
    val deadline = TimeSource.Monotonic.markNow() + 10.seconds
    var descriptor = open(path, O_WRONLY or O_NONBLOCK)
    while (descriptor < 0 && errno == ENXIO && deadline.hasNotPassedNow()) {
        usleep(1_000u)
        descriptor = open(path, O_WRONLY or O_NONBLOCK)
    }
    if (descriptor < 0) return
    val written = bytes.usePinned { write(descriptor, it.addressOf(0), bytes.size.convert()) }
    close(descriptor)
    check(written == bytes.size.toLong()) { "wrote $written of ${bytes.size} bytes" }
}
