package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.io.IOException

internal actual fun readStdin(): Result<ByteArray, ReadFailure> = try {
    Ok(System.`in`.readBytes())
} catch (error: IOException) {
    Err(ReadFailure.Io(error.message))
}
