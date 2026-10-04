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

/** Why [TomlDocument.writeTo] wrote nothing, or not all of the document. */
@Serializable
public sealed interface TomlWriteError : IError {
    /**
     * The document's text holds half of a surrogate pair without the other half, which UTF-8 has no bytes for. It
     * stays in the document, and `toString()` prints it as is. [keyPath] names the first key or string holding one:
     * the keys leading to it, the key holding it last, array indices left out. It is null when only comments or
     * spacing hold one.
     */
    @Serializable
    @SerialName("NotUnicode")
    public data class NotUnicode(val keyPath: List<String>?) : TomlWriteError
}

/**
 * Reading or writing failed in the source or sink itself, or it was closed; [detail] is the system's account, when it
 * gave one.
 */
@Serializable
@SerialName("Io")
public data class TomlIoError(val detail: String?) : TomlReadError, TomlWriteError
