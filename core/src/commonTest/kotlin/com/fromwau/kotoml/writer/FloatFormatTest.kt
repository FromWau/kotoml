package com.fromwau.kotoml.writer

import com.fromwau.kotoml.readSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks float printing against Rust's own output, recorded by `snapshots/oracle/floats.rs`: shortest digits
 * for random bit patterns of both widths, and the full `Display`, `Debug` and TOML text for curated values.
 * Every target runs the same digit generation, so this pins the JVM and native output alike.
 *
 * Where Rust's TOML text is plain decimal at 1e16 and above or below 1e-5, kotoml prints Rust's `{:e}` instead.
 */
class FloatFormatTest {

    @Test
    fun `floats print the same digits and text as Rust`() {
        val failures = mutableListOf<String>()
        for (line in readSnapshot("oracle/floats.txt").lines()) {
            if (line.isEmpty()) continue
            val fields = line.split(' ')
            val expected = fields.drop(2).toMutableList()
            val actual = when (fields[0]) {
                "D" -> {
                    val value = Double.fromBits(fields[1].toULong(16).toLong())
                    if (takesExponent(value)) expected[2] = rustLowerExp(value)
                    listOf(rustDisplayAny(value), rustDebug(value), WriteTomlValue.of(value).toTomlValue())
                }
                "d" -> {
                    val value = Double.fromBits(fields[1].toULong(16).toLong())
                    listOf(rustLowerExp(value), rustDebug(value))
                }
                "f" -> {
                    val value = Float.fromBits(fields[1].toUInt(16).toInt())
                    listOf(rustLowerExp(value), rustDebug(value))
                }
                else -> {
                    val value = Float.fromBits(fields[1].toUInt(16).toInt())
                    if (takesExponent(value.toDouble())) expected[1] = rustLowerExp(value)
                    listOf(rustDisplayAny(value), WriteTomlValue.of(value).toTomlValue())
                }
            }
            if (actual != expected) failures += "${fields[0]} ${fields[1]}: expected $expected, got $actual"
        }
        assertEquals(emptyList(), failures)
    }

    private fun takesExponent(value: Double): Boolean =
        value.isFinite() && value != 0.0 && (abs(value) >= 1e16 || abs(value) < 1e-5)
}
