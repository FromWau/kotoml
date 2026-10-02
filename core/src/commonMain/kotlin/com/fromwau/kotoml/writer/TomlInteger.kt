package com.fromwau.kotoml.writer

import com.fromwau.kern.result.EmptyResult

/**
 * How an integer is written: decimal, hexadecimal in either case, octal or binary.
 *
 * ```kotlin
 * val number = TomlIntegerFormat().asHexLower().format(10L) ?: TomlInteger(10L)
 * number.toTomlValue() // "0xa"
 * ```
 */
public class TomlIntegerFormat private constructor(private val radix: Radix) {

    /** Decimal, the default. */
    public constructor() : this(Radix.Decimal)

    /** Decimal. */
    public fun asDecimal(): TomlIntegerFormat = TomlIntegerFormat(Radix.Decimal)

    /** Hexadecimal with upper-case digits, `0xFF`. */
    public fun asHexUpper(): TomlIntegerFormat = TomlIntegerFormat(Radix.HexUpper)

    /** Hexadecimal with lower-case digits, `0xff`. */
    public fun asHexLower(): TomlIntegerFormat = TomlIntegerFormat(Radix.HexLower)

    /** Octal, `0o17`. */
    public fun asOctal(): TomlIntegerFormat = TomlIntegerFormat(Radix.Octal)

    /** Binary, `0b101`. */
    public fun asBinary(): TomlIntegerFormat = TomlIntegerFormat(Radix.Binary)

    /** [value] in this format, or null when it cannot be: TOML writes only decimal integers with a sign. */
    public fun format(value: Long): TomlInteger? =
        if (value < 0 && radix != Radix.Decimal) null else TomlInteger(value < 0, value.absoluteMagnitude(), this)

    /** [value] in this format. */
    public fun format(value: ULong): TomlInteger = TomlInteger(false, value, this)

    internal fun write(negative: Boolean, magnitude: ULong): String = when (radix) {
        Radix.Decimal -> (if (negative) "-" else "") + magnitude.toString()
        Radix.HexUpper -> "0x" + magnitude.toString(16).uppercase()
        Radix.HexLower -> "0x" + magnitude.toString(16)
        Radix.Octal -> "0o" + magnitude.toString(8)
        Radix.Binary -> "0b" + magnitude.toString(2)
    }

    override fun toString(): String = "TomlIntegerFormat(radix=$radix)"

    private enum class Radix { Decimal, HexUpper, HexLower, Octal, Binary }
}

/** An integer with its [TomlIntegerFormat] applied. Write it with [FmtWrite.value]. */
public class TomlInteger internal constructor(
    private val negative: Boolean,
    private val magnitude: ULong,
    private val format: TomlIntegerFormat,
) : WriteTomlValue {

    /** [value] in decimal. */
    public constructor(value: Long) : this(value < 0, value.absoluteMagnitude(), TomlIntegerFormat())

    override fun writeTomlValue(writer: FmtWrite): EmptyResult<FmtError> =
        writer.writeStr(format.write(negative, magnitude))

    override fun toString(): String = "TomlInteger(${format.write(negative, magnitude)})"

    public companion object {
        /** [value] in decimal. A constructor would clash on the JVM with the `Long` one. */
        public fun of(value: ULong): TomlInteger = TomlInteger(false, value, TomlIntegerFormat())
    }
}

// The magnitude of Long.MIN_VALUE does not fit a Long, so it is taken in two's complement.
private fun Long.absoluteMagnitude(): ULong = if (this < 0) (-this).toULong() else toULong()
