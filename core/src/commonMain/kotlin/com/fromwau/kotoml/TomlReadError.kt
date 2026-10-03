package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Why [TomlDocument.read] produced no document: the bytes could not be read, or they are not TOML. */
@Serializable
public sealed interface TomlReadError : IError {
    /**
     * The bytes were read but are not TOML. [text] is what was read, decoded as UTF-8, for rendering the
     * error with `error.render(text)`; it is left out of the serialized form.
     */
    @Serializable
    @SerialName("Parse")
    public data class Parse(
        val error: TomlParseError,
        @Transient val text: String = "",
    ) : TomlReadError
}

/** Reading or writing failed in the source or sink itself; [detail] is the system's account, when it gave one. */
@Serializable
@SerialName("Io")
public data class TomlIoError(val detail: String?) : TomlReadError
