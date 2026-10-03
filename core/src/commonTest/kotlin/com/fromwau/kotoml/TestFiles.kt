package com.fromwau.kotoml

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Upstream's snapshot files, copied under `src/commonTest/snapshots`; PARITY lists the texts kotoml changed. */
internal fun readSnapshot(relativePath: String): String =
    readBytes("src/commonTest/snapshots/$relativePath").decodeToString()

/** Reads [path], relative to the module directory the tests run in, as bytes. */
internal fun readBytes(path: String): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

/** Whether [path] names an existing file. */
internal fun fileExists(path: String): Boolean = SystemFileSystem.exists(Path(path))

/** Every file under [directory], as paths relative to it with `/` separators, sorted. */
internal fun walkFiles(directory: String): List<String> {
    val files = mutableListOf<String>()
    fun walk(path: Path, relative: String) {
        for (child in SystemFileSystem.list(path)) {
            val name = child.name
            val childRelative = if (relative.isEmpty()) name else "$relative/$name"
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
                walk(child, childRelative)
            } else {
                files += childRelative
            }
        }
    }
    walk(Path(directory), "")
    return files.sorted()
}
