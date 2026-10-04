package com.fromwau.kotoml

private const val SIGN_BIT = Long.MIN_VALUE

/**
 * Parses [text] as a float: an optional sign, then `inf`, `infinity` or `nan` in any case, or decimal digits
 * with an optional fraction and exponent. Returns null for anything else. Overflow is not an error: it yields an
 * infinity. The grammar is checked here because `toDoubleOrNull` alone also takes `NaN`, `Infinity` and, on the
 * JVM, suffixes such as `1f`.
 */
internal fun parseTomlFloat(text: String): Double? {
    var index = 0
    var negative = false
    if (index < text.length && (text[index] == '+' || text[index] == '-')) {
        negative = text[index] == '-'
        index++
    }
    val rest = text.substring(index)
    val special = when (rest.asciiLowercase()) {
        "inf", "infinity" -> Double.POSITIVE_INFINITY
        "nan" -> Double.NaN
        else -> null
    }
    if (special != null) return if (negative) Double.fromBits(special.toRawBits() xor SIGN_BIT) else special

    val integer = StringBuilder()
    while (index < text.length && text[index] in '0'..'9') integer.append(text[index++])
    val fraction = StringBuilder()
    if (index < text.length && text[index] == '.') {
        index++
        while (index < text.length && text[index] in '0'..'9') fraction.append(text[index++])
    }
    if (integer.isEmpty() && fraction.isEmpty()) return null
    val exponent = StringBuilder()
    if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
        index++
        if (index < text.length && (text[index] == '+' || text[index] == '-')) exponent.append(text[index++])
        val digitsStart = exponent.length
        while (index < text.length && text[index] in '0'..'9') exponent.append(text[index++])
        if (exponent.length == digitsStart) return null
    }
    if (index != text.length) return null

    val normalized = buildString {
        if (negative) append('-')
        append(integer.ifEmpty { "0" })
        append('.')
        append(fraction.ifEmpty { "0" })
        if (exponent.isNotEmpty()) {
            append('E')
            append(clampExponent(exponent.toString(), integer.length + fraction.length))
        }
    }
    return normalized.toDoubleOrNull()
}

/**
 * [exponent], kept parseable: one with more digits than an Int holds still means zero or infinity. A mantissa of
 * [mantissaDigits] digits lies between `10^-mantissaDigits` and `10^mantissaDigits`, so an exponent beyond
 * `mantissaDigits + 400` either way gives zero or infinity, and clamping it there keeps that result.
 */
private fun clampExponent(exponent: String, mantissaDigits: Int): String {
    val negative = exponent.startsWith('-')
    val digits = exponent.trimStart('+', '-').trimStart('0').ifEmpty { "0" }
    if (digits.length <= 6) return (if (negative) "-" else "") + digits
    val limit = mantissaDigits.toLong() + 400
    // More than 18 digits do not fit a Long, and are far beyond any limit.
    val magnitude = if (digits.length > 18) limit else minOf(digits.toLong(), limit)
    return (if (negative) "-" else "") + magnitude
}

/**
 * Parses [text] as an integer: one optional `+` or `-`, then at least one ASCII digit of [radix]. Returns null for
 * anything else, and for a number that does not fit a `Long`.
 */
internal fun parseTomlLong(text: String, radix: Int): Long? {
    var index = 0
    var negative = false
    if (text.isNotEmpty() && (text[0] == '+' || text[0] == '-')) {
        negative = text[0] == '-'
        index = 1
    }
    if (index == text.length) return null
    var value = 0L
    while (index < text.length) {
        val digit = asciiDigit(text[index], radix) ?: return null
        value = if (negative) {
            if (value < (Long.MIN_VALUE + digit) / radix) return null
            value * radix - digit
        } else {
            if (value > (Long.MAX_VALUE - digit) / radix) return null
            value * radix + digit
        }
        index++
    }
    return value
}

private fun asciiDigit(char: Char, radix: Int): Int? {
    val digit = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'z' -> char - 'a' + 10
        in 'A'..'Z' -> char - 'A' + 10
        else -> return null
    }
    return if (digit < radix) digit else null
}

private fun String.asciiLowercase(): String = buildString {
    for (char in this@asciiLowercase) append(if (char in 'A'..'Z') char + ('a' - 'A') else char)
}
