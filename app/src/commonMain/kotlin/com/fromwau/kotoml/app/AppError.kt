package com.fromwau.kotoml.app

import com.fromwau.kern.result.IError
import com.fromwau.kotoml.TomlParseError
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.render
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Why a kotoml command failed; `--json` prints it as it is. */
@Serializable
internal sealed interface AppError : IError {
    /** [path] could not be read; `-` is standard input. */
    @Serializable
    @SerialName("ReadFailed")
    data class ReadFailed(val path: String, val failure: ReadFailure) : AppError

    /** The input is not TOML; [source] is kept to render the error, not to print it. */
    @Serializable
    @SerialName("InvalidToml")
    data class InvalidToml(val error: TomlParseError, @Transient val source: String = "") : AppError

    /** The input is not JSON; [detail] is the JSON parser's own account. */
    @Serializable
    @SerialName("InvalidJson")
    data class InvalidJson(val detail: String?) : AppError

    /** The JSON is not toml-test's tagged form, first noticed at [path]. */
    @Serializable
    @SerialName("NotTaggedJson")
    data class NotTaggedJson(val path: String) : AppError

    /** The filter does not parse: at [position], [expected] would have fitted. */
    @Serializable
    @SerialName("FilterSyntax")
    data class FilterSyntax(val filter: String, val position: Int, val expected: FilterExpected) : AppError

    /** A filter step that a value of [itemType] cannot take. */
    @Serializable
    @SerialName("CannotApply")
    data class CannotApply(val step: Step, val itemType: TomlType) : AppError
}

/** Why reading an input failed. */
@Serializable
internal sealed interface ReadFailure : IError {
    @Serializable
    @SerialName("NotFound")
    data object NotFound : ReadFailure

    @Serializable
    @SerialName("IsADirectory")
    data object IsADirectory : ReadFailure

    /** The system refused the read; [detail] is its own account, when it gave one. */
    @Serializable
    @SerialName("Io")
    data class Io(val detail: String?) : ReadFailure
}

/** What a filter needed where it stopped parsing. */
internal enum class FilterExpected {
    /** `.` to start a path. */
    Dot,

    /** A key, a quoted key, or `[`. */
    KeyOrBracket,

    /** A closing quote. */
    Quote,

    /** An index, a quoted key, or `]`. */
    IndexOrKey,

    /** `]`. */
    CloseBracket,

    /** `|` or the end of the filter. */
    PipeOrEnd,
}

/** The exit status for [error]: 2 for a malformed invocation, 1 for an input that failed. */
internal fun AppError.exitCode(): Int = if (this is AppError.FilterSyntax) 2 else 1

/** [error] as a person reads it. */
internal fun AppError.describe(): String = when (this) {
    is AppError.ReadFailed -> {
        val name = if (path == "-") "standard input" else path
        when (failure) {
            ReadFailure.NotFound -> "$name: no such file"
            ReadFailure.IsADirectory -> "$name: is a directory"
            is ReadFailure.Io -> "$name: cannot read" + (failure.detail?.let { ": $it" } ?: "")
        }
    }
    is AppError.InvalidToml -> error.render(source).trimEnd()
    is AppError.InvalidJson -> "invalid JSON" + (detail?.let { ": $it" } ?: "")
    is AppError.NotTaggedJson -> "not toml-test's tagged JSON at $path"
    is AppError.FilterSyntax -> buildString {
        append("invalid filter at character ${position + 1}, expected ")
        append(
            when (expected) {
                FilterExpected.Dot -> "`.`"
                FilterExpected.KeyOrBracket -> "a key or `[`"
                FilterExpected.Quote -> "a closing `\"`"
                FilterExpected.IndexOrKey -> "an index, a quoted key or `]`"
                FilterExpected.CloseBracket -> "`]`"
                FilterExpected.PipeOrEnd -> "`|` or the end of the filter"
            },
        )
        append("\n  $filter\n  ")
        repeat(position) { append(' ') }
        append('^')
    }
    is AppError.CannotApply -> "cannot apply ${step.describe()} to ${itemType.name.lowercase()}"
}
