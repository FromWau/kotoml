package com.fromwau.kotoml.writer

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A sink for text that can refuse it, Rust's `core::fmt::Write`.
 *
 * Every TOML writing function here goes through [writeStr] and stops at the first refusal, returning it. A
 * writer that cannot fail, such as [StringWriter], always answers `Ok(Unit)`.
 */
public fun interface FmtWrite {
    /** Appends [text], or refuses it with [FmtError]. */
    public fun writeStr(text: String): EmptyResult<FmtError>
}

/** A writer refused text. It carries no detail, as Rust's `fmt::Error` carries none. */
public data object FmtError : IError

/** An [FmtWrite] that collects everything into a string, read back with [toString]. */
public class StringWriter : FmtWrite {
    private val buffer = StringBuilder()

    override fun writeStr(text: String): EmptyResult<FmtError> {
        buffer.append(text)
        return Ok(Unit)
    }

    /** Everything written so far. */
    override fun toString(): String = buffer.toString()
}

/** Writes `[`, opening a table header. */
public fun FmtWrite.openTableHeader(): EmptyResult<FmtError> = writeStr("[")

/** Writes `]`, closing a table header. */
public fun FmtWrite.closeTableHeader(): EmptyResult<FmtError> = writeStr("]")

/** Writes `[[`, opening an array-of-tables header. */
public fun FmtWrite.openArrayOfTablesHeader(): EmptyResult<FmtError> = writeStr("[[")

/** Writes `]]`, closing an array-of-tables header. */
public fun FmtWrite.closeArrayOfTablesHeader(): EmptyResult<FmtError> = writeStr("]]")

/** Writes `{`, opening an inline table. */
public fun FmtWrite.openInlineTable(): EmptyResult<FmtError> = writeStr("{")

/** Writes `}`, closing an inline table. */
public fun FmtWrite.closeInlineTable(): EmptyResult<FmtError> = writeStr("}")

/** Writes `[`, opening an array. */
public fun FmtWrite.openArray(): EmptyResult<FmtError> = writeStr("[")

/** Writes `]`, closing an array. */
public fun FmtWrite.closeArray(): EmptyResult<FmtError> = writeStr("]")

/** Writes `.`, between the parts of a dotted key. */
public fun FmtWrite.keySep(): EmptyResult<FmtError> = writeStr(".")

/** Writes `=`, between a key and its value. */
public fun FmtWrite.keyvalSep(): EmptyResult<FmtError> = writeStr("=")

/**
 * Writes [value] as an encoded TOML key. To choose its encoding, build it with [TomlKeyBuilder].
 */
public fun FmtWrite.key(value: WriteTomlKey): EmptyResult<FmtError> = value.writeTomlKey(this)

/**
 * Writes [value] as an encoded TOML scalar, array or inline table. To choose an encoding, build it with
 * [TomlStringBuilder] or [TomlIntegerFormat].
 *
 * A float keeps the sign of a NaN, writing `-nan`, for the sake of format-preserving editing. For general
 * serialization, drop the sign first: in most cases it is indeterminate.
 */
public fun FmtWrite.value(value: WriteTomlValue): EmptyResult<FmtError> = value.writeTomlValue(this)

/** Writes `,`, between values. */
public fun FmtWrite.valSep(): EmptyResult<FmtError> = writeStr(",")

/** Writes one space. */
public fun FmtWrite.space(): EmptyResult<FmtError> = writeStr(" ")

/** Writes `#`, opening a comment. */
public fun FmtWrite.openComment(): EmptyResult<FmtError> = writeStr("#")

/** Writes a line feed. */
public fun FmtWrite.newline(): EmptyResult<FmtError> = writeStr("\n")

/** Stops the calling function at a refused write, returning the refusal: Rust's `?` on `fmt::Result`. */
internal inline fun EmptyResult<FmtError>.orBail(bail: (Result.Error<FmtError>) -> Nothing) {
    if (this is Result.Error) bail(this)
}
