package com.fromwau.kotoml.writer

/**
 * The shortest decimal digits that read back as the same binary float, and where the decimal point goes:
 * the value is `0.digits × 10^exponent`.
 */
internal class ShortestDigits(val digits: String, val exponent: Int)

/**
 * A finite, non-zero `Double` in its shortest round-tripping digits, written without an exponent and without a
 * fraction when there is none, so `1.0` prints as `1` and `1e300` as `1` and 300 zeros.
 * The platform's own `toString` differs between the JVM and native targets, so the digits are computed here.
 */
internal fun toShortestDecimalString(value: Double): String {
    val negative = value.toRawBits() < 0
    val digits = shortestDigits(value)
    return (if (negative) "-" else "") + plainDecimal(digits)
}

/** A finite, non-zero `Float` in its shortest round-tripping digits, as for a `Double`. */
internal fun toShortestDecimalString(value: Float): String {
    val negative = value.toRawBits() < 0
    val digits = shortestDigits(value)
    return (if (negative) "-" else "") + plainDecimal(digits)
}

/**
 * A finite, non-zero `Double` in its shortest round-tripping digits: in exponent form such as `1e300` or `5e-324` at
 * 1e16 and above or below 1e-5, otherwise as [toShortestDecimalString].
 */
internal fun toShortestTomlFloat(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    if (magnitude in EXPONENT_BELOW..<EXPONENT_FROM) return toShortestDecimalString(value)
    return (if (value.toRawBits() < 0) "-" else "") + exponentForm(shortestDigits(value))
}

/** A finite, non-zero `Float` in its shortest round-tripping digits, as for a `Double`. */
internal fun toShortestTomlFloat(value: Float): String {
    val magnitude = kotlin.math.abs(value.toDouble())
    if (magnitude in EXPONENT_BELOW..<EXPONENT_FROM) return toShortestDecimalString(value)
    return (if (value.toRawBits() < 0) "-" else "") + exponentForm(shortestDigits(value))
}

private const val EXPONENT_FROM = 1e16
private const val EXPONENT_BELOW = 1e-5

private fun exponentForm(shortest: ShortestDigits): String {
    val digits = shortest.digits
    val mantissa = if (digits.length == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
    return mantissa + "e" + (shortest.exponent - 1)
}

internal fun plainDecimal(shortest: ShortestDigits): String {
    val digits = shortest.digits
    val point = shortest.exponent
    return when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point < digits.length -> digits.substring(0, point) + "." + digits.substring(point)
        else -> digits + "0".repeat(point - digits.length)
    }
}

internal fun shortestDigits(value: Double): ShortestDigits {
    val bits = value.toRawBits()
    val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
    val fraction = bits and 0xFFFFFFFFFFFFFL
    return if (biasedExponent == 0) {
        shortest(fraction, -1074, -1074, 53)
    } else {
        shortest(fraction or (1L shl 52), biasedExponent - 1075, -1074, 53)
    }
}

internal fun shortestDigits(value: Float): ShortestDigits {
    val bits = value.toRawBits()
    val biasedExponent = (bits ushr 23) and 0xFF
    val fraction = (bits and 0x7FFFFF).toLong()
    return if (biasedExponent == 0) {
        shortest(fraction, -149, -149, 24)
    } else {
        shortest(fraction or (1L shl 23), biasedExponent - 150, -149, 24)
    }
}

/**
 * Free-format shortest digits of `mantissa × 2^exponent` (Steele & White, as refined by Burger & Dybvig),
 * computed exactly with big integers. A boundary that is exactly halfway reads back to this value when the
 * mantissa is even, so it is then allowed as a candidate, matching round-half-to-even parsing.
 */
private fun shortest(mantissa: Long, exponent: Int, minExponent: Int, precision: Int): ShortestDigits {
    val even = mantissa and 1L == 0L
    val lowestNormal = 1L shl (precision - 1)
    // r / s is the value, mPlus / s and mMinus / s the distances to the midpoints with its neighbours.
    val r: BigNat
    val s: BigNat
    val mPlus: BigNat
    val mMinus: BigNat
    if (exponent >= 0) {
        if (mantissa != lowestNormal) {
            r = BigNat.of(mantissa).shiftLeft(exponent + 1)
            s = BigNat.of(2)
            mPlus = BigNat.of(1).shiftLeft(exponent)
            mMinus = BigNat.of(1).shiftLeft(exponent)
        } else {
            r = BigNat.of(mantissa).shiftLeft(exponent + 2)
            s = BigNat.of(4)
            mPlus = BigNat.of(1).shiftLeft(exponent + 1)
            mMinus = BigNat.of(1).shiftLeft(exponent)
        }
    } else {
        if (exponent == minExponent || mantissa != lowestNormal) {
            r = BigNat.of(mantissa).shiftLeft(1)
            s = BigNat.of(1).shiftLeft(1 - exponent)
            mPlus = BigNat.of(1)
            mMinus = BigNat.of(1)
        } else {
            r = BigNat.of(mantissa).shiftLeft(2)
            s = BigNat.of(1).shiftLeft(2 - exponent)
            mPlus = BigNat.of(2)
            mMinus = BigNat.of(1)
        }
    }

    // Scale so that the high end of the rounding interval lies in [0.1, 1): k is then the decimal exponent.
    var k = estimateExponent(mantissa, exponent)
    if (k >= 0) {
        s.multiplyPow10(k)
    } else {
        r.multiplyPow10(-k)
        mPlus.multiplyPow10(-k)
        mMinus.multiplyPow10(-k)
    }
    while (tooHigh(r, mPlus, s, even)) {
        s.multiplySmall(10)
        k += 1
    }
    while (true) {
        val scaled = r.copy().add(mPlus).multiplySmall(10)
        val belowTenth = if (even) scaled < s else scaled <= s
        if (!belowTenth) break
        r.multiplySmall(10)
        mPlus.multiplySmall(10)
        mMinus.multiplySmall(10)
        k -= 1
    }

    val digits = StringBuilder()
    while (true) {
        r.multiplySmall(10)
        mPlus.multiplySmall(10)
        mMinus.multiplySmall(10)
        var digit = 0
        while (r >= s) {
            r.subtract(s)
            digit++
        }
        val low = if (even) r <= mMinus else r < mMinus
        val high = tooHigh(r, mPlus, s, even)
        if (!low && !high) {
            digits.append('0' + digit)
            continue
        }
        val roundUp = when {
            low && !high -> false
            !low && high -> true
            else -> r.copy().shiftLeft(1) >= s
        }
        if (roundUp) digit++
        if (digit == 10) {
            k += carry(digits)
        } else {
            digits.append('0' + digit)
        }
        break
    }
    return ShortestDigits(digits.toString(), k)
}

/** Whether the high end of the rounding interval reaches 1, given as `(r + mPlus) / s`. */
private fun tooHigh(r: BigNat, mPlus: BigNat, s: BigNat, even: Boolean): Boolean {
    val sum = r.copy().add(mPlus)
    return if (even) sum >= s else sum > s
}

/** Adds one to the last emitted digit, which rounded up to ten; returns how much the exponent grew. */
private fun carry(digits: StringBuilder): Int {
    var index = digits.length - 1
    while (index >= 0 && digits[index] == '9') {
        digits.setLength(index)
        index--
    }
    if (index < 0) {
        digits.append('1')
        return 1
    }
    digits[index] = digits[index] + 1
    return 0
}

// An estimate of ceil(log10(value)), off by at most one; the scaling loops correct it.
private fun estimateExponent(mantissa: Long, exponent: Int): Int {
    val bitLength = 64 - mantissa.countLeadingZeroBits()
    val log2 = bitLength - 1 + exponent
    return kotlin.math.ceil(log2 * 0.30102999566398114 - 1e-10).toInt()
}

/** A non-negative big integer, mutable for speed, just large enough for float digit generation. */
internal class BigNat private constructor(private var limbs: IntArray, private var size: Int) : Comparable<BigNat> {

    fun copy(): BigNat = BigNat(limbs.copyOf(), size)

    private fun limb(index: Int): Long = if (index < size) limbs[index].toLong() and MASK else 0L

    private fun ensure(capacity: Int) {
        if (limbs.size < capacity) limbs = limbs.copyOf(maxOf(capacity, limbs.size * 2))
    }

    private fun trim() {
        while (size > 0 && limbs[size - 1] == 0) size--
    }

    fun shiftLeft(bits: Int): BigNat {
        if (bits == 0 || size == 0) return this
        val limbShift = bits / 32
        val bitShift = bits % 32
        ensure(size + limbShift + 1)
        val newSize = size + limbShift + 1
        for (index in newSize - 1 downTo 0) {
            val source = index - limbShift
            val high = if (source in 0 until size) (limb(source) shl bitShift) and MASK else 0L
            val low = if (bitShift != 0 && source - 1 in 0 until size) limb(source - 1) ushr (32 - bitShift) else 0L
            limbs[index] = (high or low).toInt()
        }
        size = newSize
        trim()
        return this
    }

    fun multiplySmall(factor: Int): BigNat {
        var carry = 0L
        for (index in 0 until size) {
            val product = limb(index) * factor + carry
            limbs[index] = product.toInt()
            carry = product ushr 32
        }
        if (carry != 0L) {
            ensure(size + 1)
            limbs[size] = carry.toInt()
            size++
        }
        return this
    }

    fun multiplyPow10(power: Int): BigNat {
        var remaining = power
        while (remaining >= 9) {
            multiplySmall(1_000_000_000)
            remaining -= 9
        }
        var factor = 1
        repeat(remaining) { factor *= 10 }
        return multiplySmall(factor)
    }

    fun add(other: BigNat): BigNat {
        val length = maxOf(size, other.size)
        ensure(length + 1)
        var carry = 0L
        for (index in 0 until length) {
            val sum = limb(index) + other.limb(index) + carry
            limbs[index] = sum.toInt()
            carry = sum ushr 32
        }
        size = length
        if (carry != 0L) {
            limbs[size] = carry.toInt()
            size++
        }
        return this
    }

    /** Subtracts [other], which must not be larger than this. */
    fun subtract(other: BigNat): BigNat {
        var borrow = 0L
        for (index in 0 until size) {
            var difference = limb(index) - other.limb(index) - borrow
            borrow = if (difference < 0) 1L else 0L
            if (difference < 0) difference += 1L shl 32
            limbs[index] = difference.toInt()
        }
        trim()
        return this
    }

    override fun compareTo(other: BigNat): Int {
        if (size != other.size) return size.compareTo(other.size)
        for (index in size - 1 downTo 0) {
            val left = limb(index)
            val right = other.limb(index)
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL

        fun of(value: Long): BigNat {
            val number = BigNat(intArrayOf(value.toInt(), (value ushr 32).toInt()), 2)
            number.trim()
            return number
        }
    }
}
