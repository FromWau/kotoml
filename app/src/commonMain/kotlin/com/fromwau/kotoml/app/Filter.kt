package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One step of a path: `.key`, `.[index]` or `.[]`. */
@Serializable
internal sealed interface Step {
    @Serializable
    @SerialName("Key")
    data class Key(val key: String) : Step

    /** A negative [index] counts from the end, as in jq. */
    @Serializable
    @SerialName("Index")
    data class Index(val index: Int) : Step

    @Serializable
    @SerialName("Iterate")
    data object Iterate : Step
}

internal fun Step.describe(): String = when (this) {
    is Step.Key -> ".\"$key\""
    is Step.Index -> ".[$index]"
    Step.Iterate -> ".[]"
}

/** A jq filter of paths joined by `|`: each stage runs on every output of the one before. */
internal data class Filter(val stages: List<List<Step>>) {
    /** Whether the filter can select more than one item, which only `.[]` does. */
    val streams: Boolean get() = stages.any { stage -> Step.Iterate in stage }
}

/**
 * Parses the jq subset kotoml understands: `.`, `.key`, `."quoted key"`, `.["quoted key"]`, `.[0]`, `.[-1]` and
 * `.[]`, chained (`.a.b[0]`) and joined with `|`. A bare key may hold `-`, as TOML bare keys do.
 */
internal fun parseFilter(text: String): Result<Filter, AppError.FilterSyntax> {
    val stages = mutableListOf<List<Step>>()
    var position = 0

    fun fail(expected: FilterExpected) = Err(AppError.FilterSyntax(text, position, expected))

    fun skipSpaces() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    var badEscape = false

    // A quoted key takes jq's escapes, which are JSON's.
    fun quoted(): String? {
        position++
        val key = StringBuilder()
        while (position < text.length && text[position] != '"') {
            if (text[position] != '\\') {
                key.append(text[position++])
                continue
            }
            val escaped = when (text.getOrNull(position + 1)) {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> text
                    .substring(minOf(position + 2, text.length), minOf(position + 6, text.length))
                    .takeIf { hex -> hex.length == 4 && hex.all { it.isHexDigit() } }
                    ?.toInt(16)
                    ?.toChar()
                    ?.toString()
                else -> null
            }
            if (escaped == null) {
                badEscape = true
                return null
            }
            key.append(escaped)
            position += if (text[position + 1] == 'u') 6 else 2
        }
        if (position >= text.length) return null
        position++
        return key.toString()
    }

    fun quoteFailure() = fail(if (badEscape) FilterExpected.Escape else FilterExpected.Quote)

    while (true) {
        skipSpaces()
        if (position >= text.length || text[position] != '.') return fail(FilterExpected.Dot)
        val steps = mutableListOf<Step>()
        position++
        var needsStep = false
        while (position < text.length) {
            val char = text[position]
            when {
                char == '"' -> steps += Step.Key(quoted() ?: return quoteFailure())
                char == '[' -> {
                    position++
                    skipSpaces()
                    when {
                        position < text.length && text[position] == ']' -> steps += Step.Iterate
                        position < text.length && text[position] == '"' -> {
                            steps += Step.Key(quoted() ?: return quoteFailure())
                            skipSpaces()
                        }
                        else -> {
                            val start = position
                            if (position < text.length && text[position] == '-') position++
                            while (position < text.length && text[position].isDigit()) position++
                            val index = text.substring(start, position).toIntOrNull()
                            if (index == null) {
                                position = start
                                return fail(FilterExpected.IndexOrKey)
                            }
                            steps += Step.Index(index)
                            skipSpaces()
                        }
                    }
                    if (position >= text.length || text[position] != ']') return fail(FilterExpected.CloseBracket)
                    position++
                }
                char.isBareKeyChar() -> {
                    val start = position
                    while (position < text.length && text[position].isBareKeyChar()) position++
                    steps += Step.Key(text.substring(start, position))
                }
                else -> break
            }
            needsStep = false
            if (position < text.length && text[position] == '.') {
                position++
                needsStep = true
            }
        }
        if (needsStep) return fail(FilterExpected.KeyOrBracket)
        stages += steps
        skipSpaces()
        if (position >= text.length) return Ok(Filter(stages))
        if (text[position] != '|') return fail(FilterExpected.PipeOrEnd)
        position++
    }
}

/** Whether jq reads this character as part of a bare key, as TOML's bare keys allow it too. */
internal fun Char.isBareKeyChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
