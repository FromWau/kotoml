package com.fromwau.kotoml.app

import kotlinx.io.files.Path

/** The user's home directory, which a shell expands `~` to, or null when it cannot be found. */
internal expect fun homeDirectory(): Path?
