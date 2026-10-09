package com.fromwau.kotoml.app

import com.fromwau.kern.dirs.BaseDirsFactory
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path

internal actual fun homeDirectory(): Path? = BaseDirsFactory().create().getOrNull()?.home
