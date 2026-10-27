package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Inputs that are no regular file: a socket is refused, a pipe is read. */
class SpecialFileTest {

    @Test
    fun `a socket is no file`() {
        val dir = Files.createTempDirectory("kotoml-socket").toFile()
        try {
            val socket = File(dir, "sock")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket.path))
                assertNotAFile(socket.path, kind = "Socket", sentence = "is a socket, not a file")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a named pipe is read whole`() {
        val dir = Files.createTempDirectory("kotoml-fifo").toFile()
        try {
            val fifo = File(dir, "fifo")
            val made = runCatching { ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0 }.getOrDefault(false)
            if (!made) {
                println("test skipped because mkfifo is not installed")
                return
            }
            // Each side's open waits for the other's, so a read that never opens the pipe must not hang the suite.
            val writer = Thread { fifo.writeText("a = 1\n") }.apply {
                isDaemon = true
                start()
            }
            var read: Result<ByteArray, AppError.ReadFailed>? = null
            val reader = Thread { read = readInput(fifo.path) }.apply {
                isDaemon = true
                start()
                join(10_000)
            }
            assertFalse(reader.isAlive, "reading the pipe blocked")
            writer.join(10_000)
            assertEquals("a = 1\n", (read as? Result.Success)?.value?.decodeToString(), "$read")
        } finally {
            dir.deleteRecursively()
        }
    }
}
