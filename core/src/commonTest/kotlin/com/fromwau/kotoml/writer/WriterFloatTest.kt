package com.fromwau.kotoml.writer

import com.fromwau.kotoml.assertData
import kotlin.math.withSign
import kotlin.test.Test

class WriterFloatTest {

    private fun t(decoded: WriteTomlValue, expected: String) {
        assertData(expected, decoded.toTomlValue())
    }

    @Test
    fun zero() {
        t(WriteTomlValue.of(0.0), "0.0")
    }

    @Test
    fun negZero() {
        t(WriteTomlValue.of(-0.0), "-0.0")
    }

    @Test
    fun inf() {
        t(WriteTomlValue.of(Double.POSITIVE_INFINITY), "inf")
    }

    @Test
    fun negInf() {
        t(WriteTomlValue.of(Double.NEGATIVE_INFINITY), "-inf")
    }

    @Test
    fun nan() {
        t(WriteTomlValue.of(Double.NaN.withSign(1.0)), "nan")
    }

    @Test
    fun negNan() {
        t(WriteTomlValue.of(Double.NaN.withSign(-1.0)), "-nan")
    }
}
