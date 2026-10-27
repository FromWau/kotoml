package com.fromwau.kotoml.app

import com.fromwau.kern.fs.LONGEST_NAME_BYTES
import com.fromwau.kern.result.ResultError
import com.fromwau.klap.USAGE_ERROR_EXIT
import com.fromwau.kotoml.TomlParseError
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.render
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Why a kotoml command failed; `--json` prints it as it is. An error in an input names it as `path`, `-` for
 * standard input.
 */
@Serializable
internal sealed interface AppError : ResultError {
    /** [path] could not be read; `-` is standard input. */
    @Serializable
    @SerialName("ReadFailed")
    data class ReadFailed(val path: String, val failure: ReadFailure) : AppError

    /** The input at [path] is not TOML; [source] is kept to render the error, not to print it. */
    @Serializable
    @SerialName("InvalidToml")
    data class InvalidToml(
        val path: String,
        val error: TomlParseError,
        @Transient val source: String = "",
    ) : AppError

    /** The input at [path] is not JSON; [position] is where the JSON parser stopped, when it said. */
    @Serializable
    @SerialName("InvalidJson")
    data class InvalidJson(
        val path: String,
        val position: TextPosition?,
    ) : AppError

    /** The JSON at [path] is not toml-test's tagged form at the JSON path [at], for [reason]. */
    @Serializable
    @SerialName("NotTaggedJson")
    data class NotTaggedJson(
        val path: String,
        val at: String,
        val reason: TaggedJsonProblem,
    ) : AppError

    /**
     * The JSON at [path] nests deeper than [limit] levels of objects and arrays, a tagged value's own object not
     * counted.
     */
    @Serializable
    @SerialName("JsonTooDeep")
    data class JsonTooDeep(
        val path: String,
        val limit: Int,
    ) : AppError

    /** The filter does not parse: at [position], [expected] would have fitted. */
    @Serializable
    @SerialName("FilterSyntax")
    data class FilterSyntax(val filter: String, val position: Int, val expected: FilterExpected) : AppError

    /** The filter looks for [key] at [path], and the table there has no such key. */
    @Serializable
    @SerialName("NoSuchKey")
    data class NoSuchKey(val path: String, val key: String) : AppError

    /** The filter looks for [index] at [path], and the array there has [size] elements. */
    @Serializable
    @SerialName("NoSuchIndex")
    data class NoSuchIndex(val path: String, val index: Int, val size: Int) : AppError

    /** A filter step that a value of [itemType] cannot take. */
    @Serializable
    @SerialName("CannotApply")
    data class CannotApply(val step: Step, val itemType: TomlType) : AppError
}

/** Why reading an input failed. */
@Serializable
internal sealed interface ReadFailure : ResultError {
    @Serializable
    @SerialName("NotFound")
    data object NotFound : ReadFailure

    /** Something other than a regular file is at the path, such as a folder or a socket. */
    @Serializable
    @SerialName("NotAFile")
    data class NotAFile(val kind: FileKind) : ReadFailure

    @Serializable
    @SerialName("PermissionDenied")
    data object PermissionDenied : ReadFailure

    /** The path starts with `~`, and no home directory was found to resolve it to. */
    @Serializable
    @SerialName("HomeUnknown")
    data object HomeUnknown : ReadFailure

    /** A name in the path is [bytes] long in UTF-8, longer than the [LONGEST_NAME_BYTES] a file name may have. */
    @Serializable
    @SerialName("NameTooLong")
    data class NameTooLong(val bytes: Int) : ReadFailure

    /** The read failed for another reason; [detail] is the system's own account, when it gave one. */
    @Serializable
    @SerialName("Io")
    data class Io(val detail: String?) : ReadFailure
}

/** What stands where a file to read was expected. */
@Serializable
internal enum class FileKind {
    @SerialName("Directory")
    Directory,

    @SerialName("Socket")
    Socket,

    @SerialName("CharacterDevice")
    CharacterDevice,

    @SerialName("BlockDevice")
    BlockDevice,

    /** A named pipe that could not be read as one. */
    @SerialName("Fifo")
    Fifo,

    /** Something the system does not name, as on Windows. */
    @SerialName("Other")
    Other,
}

/** What a filter needed where it stopped parsing. */
internal enum class FilterExpected {
    /** `.` to start a path. */
    Dot,

    /** A key, a quoted key, or `[`. */
    KeyOrBracket,

    /** A closing quote. */
    Quote,

    /** One of jq's escapes after `\\` in a quoted key. */
    Escape,

    /** An index, a quoted key, or `]`. */
    IndexOrKey,

    /** An index that fits in 32 bits. */
    IndexInRange,

    /** `]`. */
    CloseBracket,

    /** `|` or the end of the filter. */
    PipeOrEnd,
}

/** The exit status for a filter that found nothing where it looked. */
private const val NOTHING_FOUND_EXIT: Int = 3

/** The exit status for this error: 2 for a malformed invocation, 3 for nothing found, 1 for an input that failed. */
internal val AppError.exitCode: Int
    get() = when (this) {
        is AppError.FilterSyntax -> USAGE_ERROR_EXIT
        is AppError.NoSuchKey, is AppError.NoSuchIndex -> NOTHING_FOUND_EXIT
        else -> 1
    }

/**
 * How an error names the input at [path]. A line break in the name is escaped too, as it would start a line of its
 * own, one that reads like another file's.
 */
internal fun inputName(path: String): String =
    if (path == "-") "standard input" else path.escapingControls(keepLayout = false)

// What klap does to an error's detail: a control character from the input could rewrite the terminal.
internal fun String.escapingControls(keepLayout: Boolean = true): String = buildString {
    for (char in this@escapingControls) {
        val code = char.code
        val control = code in 0x00..0x1F || code == 0x7F
        if (control && !(keepLayout && (char == '\n' || char == '\t'))) {
            append("\\x")
            append(code.toString(16).padStart(2, '0').uppercase())
        } else {
            append(char)
        }
    }
}

/** This error as a person reads it. */
internal fun AppError.describe(): String = when (this) {
    is AppError.ReadFailed -> {
        val name = inputName(path)
        when (failure) {
            ReadFailure.NotFound -> "$name: no such file"
            is ReadFailure.NotAFile -> when (failure.kind) {
                FileKind.Directory -> "$name: is a directory"
                FileKind.Socket -> "$name: is a socket, not a file"
                FileKind.CharacterDevice -> "$name: is a character device, not a file"
                FileKind.BlockDevice -> "$name: is a block device, not a file"
                FileKind.Fifo -> "$name: is a FIFO, not a file"
                FileKind.Other -> "$name: is not a file"
            }
            ReadFailure.PermissionDenied -> "$name: permission denied"
            ReadFailure.HomeUnknown -> "$name: the home directory is unknown, so ~ cannot be resolved"
            is ReadFailure.NameTooLong ->
                "$name: a name in the path is ${failure.bytes} bytes, " +
                    "longer than the $LONGEST_NAME_BYTES a file name may have"
            is ReadFailure.Io -> "$name: cannot read" + (failure.detail?.let { ": $it" } ?: "")
        }
    }
    is AppError.InvalidToml -> "${inputName(path)}: ${error.render(source).trimEnd()}"
    is AppError.InvalidJson ->
        "${inputName(path)}: invalid JSON" + (position?.let { " at line ${it.line}, column ${it.column}" } ?: "")
    is AppError.NotTaggedJson -> "${inputName(path)}: not toml-test's tagged JSON at $at: ${reason.describe()}"
    is AppError.JsonTooDeep -> "${inputName(path)}: JSON nests deeper than $limit levels"
    is AppError.FilterSyntax -> buildString {
        append("invalid filter at character ${position + 1}, expected ")
        append(
            when (expected) {
                FilterExpected.Dot -> "`.`"
                FilterExpected.KeyOrBracket -> "a key or `[`"
                FilterExpected.Quote -> "a closing `\"`"
                FilterExpected.Escape ->
                    "an escape: `\\\"`, `\\\\`, `\\/`, `\\b`, `\\f`, `\\n`, `\\r`, `\\t` or `\\uXXXX`"
                FilterExpected.IndexOrKey -> "an index, a quoted key or `]`"
                FilterExpected.IndexInRange -> "an index from ${Int.MIN_VALUE} to ${Int.MAX_VALUE}"
                FilterExpected.CloseBracket -> "`]`"
                FilterExpected.PipeOrEnd -> "`|` or the end of the filter"
            },
        )
        append("\n  $filter\n  ")
        repeat(position) { append(' ') }
        append('^')
    }
    is AppError.NoSuchKey -> "no key \"$key\" at $path"
    is AppError.NoSuchIndex -> "no index $index at $path, it has $size"
    is AppError.CannotApply -> "cannot apply ${step.describe()} to ${itemType.words()}"
}

internal fun TomlType.words(): String = when (this) {
    TomlType.String -> "string"
    TomlType.Integer -> "integer"
    TomlType.Float -> "float"
    TomlType.Boolean -> "boolean"
    TomlType.Datetime -> "date-time"
    TomlType.Array -> "array"
    TomlType.InlineTable -> "inline table"
    TomlType.Table -> "table"
    TomlType.ArrayOfTables -> "array of tables"
}

/** Why JSON is not toml-test's tagged form. */
@Serializable
internal sealed interface TaggedJsonProblem {
    /** The root is not a JSON object. */
    @Serializable
    @SerialName("RootNotObject")
    data object RootNotObject : TaggedJsonProblem

    /** A bare JSON number, string, boolean or `null` stands where a tagged value, a table or an array belongs. */
    @Serializable
    @SerialName("Untagged")
    data object Untagged : TaggedJsonProblem

    /** [tomlType] is not one of toml-test's types. */
    @Serializable
    @SerialName("UnknownType")
    data class UnknownType(val tomlType: String) : TaggedJsonProblem

    /** A tagged value has no `"value"`. */
    @Serializable
    @SerialName("MissingValue")
    data object MissingValue : TaggedJsonProblem

    /** A tagged value's `"value"` is not a JSON string. */
    @Serializable
    @SerialName("ValueNotString")
    data object ValueNotString : TaggedJsonProblem

    /** A tagged value has [key] besides `"type"` and `"value"`. */
    @Serializable
    @SerialName("ExtraKey")
    data class ExtraKey(val key: String) : TaggedJsonProblem

    /** [value] is not valid TOML for [tomlType], or is a date-time of another kind. */
    @Serializable
    @SerialName("BadValue")
    data class BadValue(val tomlType: String, val value: String) : TaggedJsonProblem

    /** A string or key holds half of a surrogate pair, which UTF-8 cannot write. */
    @Serializable
    @SerialName("NotUnicode")
    data object NotUnicode : TaggedJsonProblem
}

internal fun TaggedJsonProblem.describe(): String = when (this) {
    TaggedJsonProblem.RootNotObject -> "the root must be a JSON object"
    TaggedJsonProblem.Untagged -> "expected a {\"type\":…,\"value\":…} object, a table or an array"
    is TaggedJsonProblem.UnknownType -> "unknown type \"$tomlType\""
    TaggedJsonProblem.MissingValue -> "\"value\" is missing"
    TaggedJsonProblem.ValueNotString -> "\"value\" must be a string"
    is TaggedJsonProblem.ExtraKey -> "unexpected key \"$key\""
    is TaggedJsonProblem.BadValue -> "\"$value\" is not a valid $tomlType"
    TaggedJsonProblem.NotUnicode -> "half of a surrogate pair, which UTF-8 cannot write"
}
