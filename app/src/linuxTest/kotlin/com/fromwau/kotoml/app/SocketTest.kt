@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kotoml.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.sa_family_tVar
import platform.posix.socket
import kotlin.random.Random
import kotlin.test.Test

class SocketTest {

    @Test
    fun `a socket is no file`() {
        val socket = Path(SystemTemporaryDirectory, "kotoml-${Random.nextLong().toULong()}.sock")
        socket.bindSocket()
        try {
            assertNotAFile(socket.toString(), kind = "Socket", sentence = "is a socket, not a file")
        } finally {
            SystemFileSystem.delete(socket)
        }
    }
}

/** Binds a unix domain socket at this path and closes it again, which leaves the socket file behind. */
private fun Path.bindSocket() {
    val name = toString().encodeToByteArray()
    memScoped {
        val address = alloc<sockaddr_un>()
        // The name and the NUL after it have to fit, or bind would read a name cut short.
        check(name.size < sizeOf<sockaddr_un>() - sizeOf<sa_family_tVar>()) { "too long for a socket: $this" }
        address.sun_family = AF_UNIX.convert()
        name.forEachIndexed { index, byte -> address.sun_path[index] = byte }
        val descriptor = socket(AF_UNIX, SOCK_STREAM, 0)
        check(descriptor >= 0)
        try {
            check(bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_un>().convert()) == 0)
        } finally {
            close(descriptor)
        }
    }
}
