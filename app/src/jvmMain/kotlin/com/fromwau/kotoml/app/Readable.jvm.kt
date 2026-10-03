package com.fromwau.kotoml.app

import java.nio.file.Files
import java.nio.file.Paths

internal actual fun isReadable(path: String): Boolean = Files.isReadable(Paths.get(path))
