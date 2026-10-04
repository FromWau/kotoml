package com.fromwau.kotoml.writer

/** Writes `[`, opening a table header. */
internal fun StringBuilder.openTableHeader() {
    append("[")
}

/** Writes `]`, closing a table header. */
internal fun StringBuilder.closeTableHeader() {
    append("]")
}

/** Writes `{`, opening an inline table. */
internal fun StringBuilder.openInlineTable() {
    append("{")
}

/** Writes `}`, closing an inline table. */
internal fun StringBuilder.closeInlineTable() {
    append("}")
}

/** Writes `[`, opening an array. */
internal fun StringBuilder.openArray() {
    append("[")
}

/** Writes `]`, closing an array. */
internal fun StringBuilder.closeArray() {
    append("]")
}

/** Writes `.`, between the parts of a dotted key. */
internal fun StringBuilder.keySeparator() {
    append(".")
}

/** Writes `=`, between a key and its value. */
internal fun StringBuilder.keyValueSeparator() {
    append("=")
}

/**
 * Writes [value] as an encoded TOML key. To choose its encoding, build it with [TomlKeyBuilder].
 */
internal fun StringBuilder.key(value: WriteTomlKey) {
    value.writeTomlKey(this)
}

/**
 * Writes [value] as an encoded TOML scalar, array or inline table. To choose an encoding, build it with
 * [TomlStringBuilder] or [TomlIntegerFormat].
 *
 * A float keeps the sign of a NaN, writing `-nan`, for the sake of format-preserving editing. For general
 * serialization, drop the sign first: in most cases it is indeterminate.
 */
internal fun StringBuilder.value(value: WriteTomlValue) {
    value.writeTomlValue(this)
}

/** Writes `,`, between values. */
internal fun StringBuilder.valueSeparator() {
    append(",")
}

/** Writes one space. */
internal fun StringBuilder.space() {
    append(" ")
}

/** Writes a line feed. */
internal fun StringBuilder.newline() {
    append("\n")
}
