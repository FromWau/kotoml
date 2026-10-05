package com.fromwau.kotoml.app

import kotlin.system.exitProcess

internal actual fun exitWith(status: Int): Nothing = exitProcess(status)
