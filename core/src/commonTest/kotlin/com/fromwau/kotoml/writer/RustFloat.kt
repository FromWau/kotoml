package com.fromwau.kotoml.writer

/** Rust's `{:e}` for an `f64`: the shortest digits in scientific notation, such as `1.5e-7`. */
internal fun rustLowerExp(value: Double): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "inf" else "-inf"
    value == 0.0 -> if (value.toRawBits() < 0) "-0e0" else "0e0"
    else -> scientific(value.toRawBits() < 0, shortestDigits(value))
}

/** Rust's `{:e}` for an `f32`. */
internal fun rustLowerExp(value: Float): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "inf" else "-inf"
    value == 0.0f -> if (value.toRawBits() < 0) "-0e0" else "0e0"
    else -> scientific(value.toRawBits() < 0, shortestDigits(value))
}

/** Rust's `{:?}` for an `f64`: decimal with at least one fraction digit, scientific outside `[1e-4, 1e16)`. */
internal fun rustDebug(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    return when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "inf" else "-inf"
        value == 0.0 -> if (value.toRawBits() < 0) "-0.0" else "0.0"
        magnitude < 1e-4 || magnitude >= 1e16 -> rustLowerExp(value)
        else -> withFraction(toShortestDecimalString(value))
    }
}

/** Rust's `{:?}` for an `f32`. */
internal fun rustDebug(value: Float): String {
    val magnitude = kotlin.math.abs(value)
    return when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "inf" else "-inf"
        value == 0.0f -> if (value.toRawBits() < 0) "-0.0" else "0.0"
        magnitude < 1e-4f || magnitude >= 1e16f -> rustLowerExp(value)
        else -> withFraction(toShortestDecimalString(value))
    }
}

/** Rust's `{}` for an `f64`, special values included. */
internal fun rustDisplayAny(value: Double): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "inf" else "-inf"
    value == 0.0 -> if (value.toRawBits() < 0) "-0" else "0"
    else -> toShortestDecimalString(value)
}

/** Rust's `{}` for an `f32`, special values included. */
internal fun rustDisplayAny(value: Float): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "inf" else "-inf"
    value == 0.0f -> if (value.toRawBits() < 0) "-0" else "0"
    else -> toShortestDecimalString(value)
}

private fun withFraction(display: String): String = if ('.' in display) display else "$display.0"

private fun scientific(negative: Boolean, shortest: ShortestDigits): String {
    val digits = shortest.digits
    val mantissa = if (digits.length == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
    return (if (negative) "-" else "") + mantissa + "e" + (shortest.exponent - 1)
}
