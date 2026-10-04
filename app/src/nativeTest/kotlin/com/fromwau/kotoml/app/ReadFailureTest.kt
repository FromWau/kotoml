package com.fromwau.kotoml.app

import platform.posix.EACCES
import platform.posix.ENAMETOOLONG
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadFailureTest {

    @Test
    fun `a missing file or a path through a regular file is not found`() {
        assertEquals(ReadFailure.NotFound, readFailure(ENOENT, "stat failed"))
        assertEquals(ReadFailure.NotFound, readFailure(ENOTDIR, "stat failed"))
    }

    @Test
    fun `a lookup the system refuses is permission denied`() {
        assertEquals(ReadFailure.PermissionDenied, readFailure(EACCES, "stat failed"))
    }

    @Test
    fun `any other lookup failure keeps the system's sentence`() {
        assertEquals(ReadFailure.Io("stat failed"), readFailure(ENAMETOOLONG, "stat failed"))
    }
}
