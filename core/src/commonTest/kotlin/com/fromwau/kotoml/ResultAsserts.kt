package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import kotlin.test.fail

/** The success value, failing the test with the error otherwise: Rust's `unwrap` in a test. */
internal fun <S, E : IError> Result<S, E>.unwrap(): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fail("expected success, got error:\n$error")
}

/** The error, failing the test on success: Rust's `unwrap_err` in a test. */
internal fun <S, E : IError> Result<S, E>.unwrapErr(): E = when (this) {
    is Result.Success -> fail("expected an error, got success: $value")
    is Result.Error -> error
}

internal fun <S, E : IError> Result<S, E>.isOk(): Boolean = this is Result.Success
