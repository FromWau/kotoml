package com.fromwau.kotoml.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EACCES
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.F_OK
import platform.posix.R_OK
import platform.posix.access
import platform.posix.errno

internal actual fun isReadable(path: String): Boolean = access(path, R_OK) == 0

// kotlinx-io names the errno only in its sentence, so the lookup is asked again for the number.
@OptIn(ExperimentalForeignApi::class)
internal actual fun statFailure(
    path: String,
    detail: String?,
): ReadFailure = if (access(path, F_OK) == 0) ReadFailure.Io(detail) else readFailure(errno, detail)

/** The failure the system's errno [code] for a path lookup means; [detail] is kept for any but the named ones. */
internal fun readFailure(
    code: Int,
    detail: String?,
): ReadFailure = when (code) {
    // A path through a regular file names nothing, as the JVM reports it.
    ENOENT, ENOTDIR -> ReadFailure.NotFound
    EACCES -> ReadFailure.PermissionDenied
    else -> ReadFailure.Io(detail)
}
