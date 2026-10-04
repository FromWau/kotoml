package com.fromwau.kotoml.writer

import com.fromwau.kotoml.assertSnapshot
import kotlin.math.withSign
import kotlin.test.Test

class WriterFloatTest {

    private fun assertWrites(decoded: WriteTomlValue, expected: String) {
        assertSnapshot(expected, decoded.toTomlValue())
    }

    @Test
    fun `zero writes with a zero fraction`() {
        assertWrites(WriteTomlValue.of(0.0), "0.0")
    }

    @Test
    fun `negative zero writes its sign and a zero fraction`() {
        assertWrites(WriteTomlValue.of(-0.0), "-0.0")
    }

    @Test
    fun `positive infinity writes as inf`() {
        assertWrites(WriteTomlValue.of(Double.POSITIVE_INFINITY), "inf")
    }

    @Test
    fun `negative infinity writes as minus inf`() {
        assertWrites(WriteTomlValue.of(Double.NEGATIVE_INFINITY), "-inf")
    }

    @Test
    fun `a positive NaN writes as nan`() {
        assertWrites(WriteTomlValue.of(Double.NaN.withSign(1.0)), "nan")
    }

    @Test
    fun `a negative NaN writes as minus nan`() {
        assertWrites(WriteTomlValue.of(Double.NaN.withSign(-1.0)), "-nan")
    }
}
