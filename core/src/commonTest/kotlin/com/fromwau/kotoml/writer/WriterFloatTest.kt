package com.fromwau.kotoml.writer

import com.fromwau.kotoml.assertSnapshot
import kotlin.math.withSign
import kotlin.test.Test

class WriterFloatTest {

    private fun t(decoded: WriteTomlValue, expected: String) {
        assertSnapshot(expected, decoded.toTomlValue())
    }

    @Test
    fun `zero writes with a zero fraction`() {
        t(WriteTomlValue.of(0.0), "0.0")
    }

    @Test
    fun `negative zero writes its sign and a zero fraction`() {
        t(WriteTomlValue.of(-0.0), "-0.0")
    }

    @Test
    fun `positive infinity writes as inf`() {
        t(WriteTomlValue.of(Double.POSITIVE_INFINITY), "inf")
    }

    @Test
    fun `negative infinity writes as minus inf`() {
        t(WriteTomlValue.of(Double.NEGATIVE_INFINITY), "-inf")
    }

    @Test
    fun `a positive NaN writes as nan`() {
        t(WriteTomlValue.of(Double.NaN.withSign(1.0)), "nan")
    }

    @Test
    fun `a negative NaN writes as minus nan`() {
        t(WriteTomlValue.of(Double.NaN.withSign(-1.0)), "-nan")
    }
}
