package com.fromwau.kotoml.app

import platform.posix.R_OK
import platform.posix.access

internal actual fun isReadable(path: String): Boolean = access(path, R_OK) == 0
