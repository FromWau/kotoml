package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import kotlin.test.fail

/** The success value, failing the test with the error otherwise. */
internal fun <S, E : IError> Result<S, E>.getOrFail(): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fail("expected success, got error:\n$error")
}

/** The error, failing the test on success. */
internal fun <S, E : IError> Result<S, E>.errorOrFail(): E = when (this) {
    is Result.Success -> fail("expected an error, got success: $value")
    is Result.Error -> error
}
