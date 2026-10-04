package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.assertEquals

/**
 * A value as Rust's derived `Debug` sees it, so upstream's `{:#?}` snapshots can be compared byte for byte.
 */
internal sealed interface Dbg {
    data class Struct(val name: String, val fields: List<Pair<String, Dbg>>) : Dbg

    data class Tuple(val name: String, val items: List<Dbg>) : Dbg

    data class Lit(val text: String) : Dbg

    data class Str(val value: String) : Dbg

    data class Seq(val items: List<Dbg>) : Dbg

    data class MapOf(val entries: List<Pair<Dbg, Dbg>>) : Dbg
}

internal fun struct(name: String, vararg fields: Pair<String, Dbg>): Dbg = Dbg.Struct(name, fields.toList())

internal fun tuple(name: String, vararg items: Dbg): Dbg = Dbg.Tuple(name, items.toList())

internal fun lit(text: Any): Dbg = Dbg.Lit(text.toString())

internal fun str(value: String): Dbg = Dbg.Str(value)

internal fun seq(items: List<Dbg>): Dbg = Dbg.Seq(items)

internal fun <T> option(value: T?, render: (T) -> Dbg): Dbg =
    if (value == null) lit("None") else tuple("Some", render(value))

internal fun <S, E : com.fromwau.kern.result.IError> Result<S, E>.debug(
    success: (S) -> Dbg,
    error: (E) -> Dbg,
): Dbg = when (this) {
    is Result.Success -> tuple("Ok", success(value))
    is Result.Error -> tuple("Err", error(this.error))
}

/** Renders like Rust's `{:#?}`. */
internal fun Dbg.pretty(): String = when (this) {
    is Dbg.Lit -> text
    is Dbg.Str -> rustDebugString(value)
    is Dbg.Struct -> if (fields.isEmpty()) {
        name
    } else {
        buildString {
            append(name)
            append(" {\n")
            for ((field, value) in fields) {
                append("    ")
                append(field)
                append(": ")
                append(value.pretty().indentNested())
                append(",\n")
            }
            append('}')
        }
    }
    is Dbg.Tuple -> if (items.isEmpty()) {
        name
    } else {
        buildString {
            append(name)
            append("(\n")
            for (item in items) {
                append("    ")
                append(item.pretty().indentNested())
                append(",\n")
            }
            append(')')
        }
    }
    is Dbg.Seq -> if (items.isEmpty()) {
        "[]"
    } else {
        buildString {
            append("[\n")
            for (item in items) {
                append("    ")
                append(item.pretty().indentNested())
                append(",\n")
            }
            append(']')
        }
    }
    is Dbg.MapOf -> if (entries.isEmpty()) {
        "{}"
    } else {
        buildString {
            append("{\n")
            for ((key, value) in entries) {
                append("    ")
                append(key.pretty().indentNested())
                append(": ")
                append(value.pretty().indentNested())
                append(",\n")
            }
            append('}')
        }
    }
}

private fun String.indentNested(): String = replace("\n", "\n    ")

/** Rust's `Debug` for `str`: quoted, with control and non-printable characters escaped. */
internal fun rustDebugString(value: String): String = buildString {
    append('"')
    var index = 0
    while (index < value.length) {
        val codePoint = codePointAt(value, index)
        index += if (codePoint > 0xFFFF) 2 else 1
        append(rustEscapeDebug(codePoint, escapeSingleQuote = false))
    }
    append('"')
}

internal fun rustEscapeDebug(codePoint: Int, escapeSingleQuote: Boolean): String = when {
    codePoint == 0 -> "\\0"
    codePoint == '\t'.code -> "\\t"
    codePoint == '\r'.code -> "\\r"
    codePoint == '\n'.code -> "\\n"
    codePoint == '\\'.code -> "\\\\"
    codePoint == '"'.code -> "\\\""
    codePoint == '\''.code && escapeSingleQuote -> "\\'"
    !isRustPrintable(codePoint) -> "\\u{${codePoint.toString(16)}}"
    else -> codePointToString(codePoint)
}

// Rust escapes control, format, surrogate, private-use and unassigned characters, every separator but the
// ASCII space, and grapheme extenders. Kotlin's categories cover the BMP; above it only the private-use planes
// and noncharacters are told apart.
private fun isRustPrintable(codePoint: Int): Boolean {
    if (codePoint == ' '.code) return true
    if (codePoint and 0xFFFE == 0xFFFE) return false
    if (codePoint > 0xFFFF) return codePoint < 0xF0000
    return when (codePoint.toChar().category) {
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.SURROGATE,
        CharCategory.PRIVATE_USE,
        CharCategory.UNASSIGNED,
        CharCategory.SPACE_SEPARATOR,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
        CharCategory.NON_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        -> false
        else -> true
    }
}

internal fun codePointAt(value: String, index: Int): Int {
    val high = value[index]
    if (high.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (value[index + 1].code - 0xDC00)
    }
    return high.code
}

internal fun codePointToString(codePoint: Int): String = if (codePoint > 0xFFFF) {
    val offset = codePoint - 0x10000
    charArrayOf(
        (0xD800 + (offset shr 10)).toChar(),
        (0xDC00 + (offset and 0x3FF)).toChar(),
    ).concatToString()
} else {
    codePoint.toChar().toString()
}

/**
 * snapbox's `str![[r#"..."#]]`: a literal holding a newline loses one leading and one trailing newline.
 */
internal fun inline(literal: String): String {
    if (!literal.contains('\n')) return literal
    return literal.removePrefix("\n").removeSuffix("\n")
}

/** snapbox's `assert_data_eq!(actual.to_debug(), str![[...]])`. */
internal fun assertDebug(expected: String, actual: Dbg) {
    assertData(expected, actual.pretty() + "\n")
}

/**
 * snapbox's `assert_data_eq!(actual, str![[...]].raw())` on plain text. Even a raw comparison turns CRLF and a
 * lone CR into LF on both sides.
 */
internal fun assertData(expected: String, actual: String) {
    assertEquals(normalizeNewlines(inline(expected)), normalizeNewlines(actual))
}

/** snapbox's `assert_data_eq!(actual, file![_].raw())`. */
internal fun assertFileData(expected: String, actual: String) {
    assertEquals(normalizeNewlines(expected), normalizeNewlines(actual))
}

internal fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
