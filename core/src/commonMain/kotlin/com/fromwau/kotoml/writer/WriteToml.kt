package com.fromwau.kotoml.writer

/**
 * Something that can be written as a TOML key.
 *
 * [TomlKey] implements it; [WriteTomlKey.of] adapts a plain string, choosing the default encoding.
 */
internal fun interface WriteTomlKey {
    /** Writes this key, encoded, to [writer]. */
    fun writeTomlKey(writer: StringBuilder)

    companion object {
        /** [key] in its default encoding: bare when it can be, otherwise quoted. */
        fun of(key: String): WriteTomlKey = TomlKeyBuilder(key).asDefault()
    }
}

/** This key as TOML text. */
internal fun WriteTomlKey.toTomlKey(): String = buildString { writeTomlKey(this) }

/**
 * Something that can be written as a TOML value.
 *
 * [TomlString] and [TomlInteger] implement it; the `of` functions adapt Kotlin's own values.
 */
internal fun interface WriteTomlValue {
    /** Writes this value, encoded, to [writer]. */
    fun writeTomlValue(writer: StringBuilder)

    companion object {
        /** `true` or `false`. */
        fun of(value: Boolean): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: Byte): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: Short): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: Int): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: Long): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: UByte): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: UShort): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: UInt): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /** A decimal integer. */
        fun of(value: ULong): WriteTomlValue = WriteTomlValue { it.append(value.toString()) }

        /**
         * A float in its shortest round-tripping form, with `.0` added to a whole number and `inf`, `nan` and
         * their negative forms for the special values. The sign of zero and of NaN is kept.
         */
        fun of(value: Float): WriteTomlValue = WriteTomlValue { writer ->
            writer.append(
                formatFloat(
                    isNegative = value.toRawBits() < 0,
                    isNaN = value.isNaN(),
                    isZero = value == 0.0f,
                    isWhole = value % 1.0f == 0.0f,
                    isInfinite = value.isInfinite(),
                    display = { toShortestDecimalString(value) },
                ),
            )
        }

        /**
         * A float in its shortest round-tripping form, with `.0` added to a whole number and `inf`, `nan` and
         * their negative forms for the special values. The sign of zero and of NaN is kept.
         */
        fun of(value: Double): WriteTomlValue = WriteTomlValue { writer ->
            writer.append(
                formatFloat(
                    isNegative = value.toRawBits() < 0,
                    isNaN = value.isNaN(),
                    isZero = value == 0.0,
                    isWhole = value % 1.0 == 0.0,
                    isInfinite = value.isInfinite(),
                    display = { toShortestDecimalString(value) },
                ),
            )
        }

        /** A one-character string, in its default encoding. */
        fun of(value: Char): WriteTomlValue = of(value.toString())

        /** A string in its default encoding, see [TomlStringBuilder.asDefault]. */
        fun of(value: String): WriteTomlValue = TomlStringBuilder(value).asDefault()

        /** An array, `[a, b, c]`. */
        fun of(values: Iterable<WriteTomlValue>): WriteTomlValue = WriteTomlValue { writer ->
            writer.openArray()
            var first = true
            for (value in values) {
                if (!first) {
                    writer.valueSeparator()
                    writer.space()
                }
                first = false
                writer.value(value)
            }
            writer.closeArray()
        }

        /**
         * An inline table, `{ a = 1, b = 2 }`, written in the map's iteration order. Upstream writes a
         * `BTreeMap` sorted and a `HashMap` in hash order; pass a sorted map for the former.
         */
        fun of(table: Map<out WriteTomlKey, WriteTomlValue>): WriteTomlValue = WriteTomlValue { writer ->
            writer.openInlineTable()
            var first = true
            for ((key, value) in table) {
                if (first) {
                    writer.space()
                } else {
                    writer.valueSeparator()
                    writer.space()
                }
                first = false
                writer.key(key)
                writer.space()
                writer.keyValueSeparator()
                writer.space()
                writer.value(value)
            }
            if (!first) writer.space()
            writer.closeInlineTable()
        }
    }
}

/** This value as TOML text. */
internal fun WriteTomlValue.toTomlValue(): String = buildString { writeTomlValue(this) }

private inline fun formatFloat(
    isNegative: Boolean,
    isNaN: Boolean,
    isZero: Boolean,
    isWhole: Boolean,
    isInfinite: Boolean,
    display: () -> String,
): String = when {
    isNaN -> if (isNegative) "-nan" else "nan"
    isZero -> if (isNegative) "-0.0" else "0.0"
    isInfinite -> if (isNegative) "-inf" else "inf"
    isWhole -> display() + ".0"
    else -> display()
}
