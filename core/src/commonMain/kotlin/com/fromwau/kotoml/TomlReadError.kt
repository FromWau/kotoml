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
     * The error as a person reads it, led by the key path when it has one, such as
     * `server.ports: inline tables cannot end with a comma in TOML 1.0`.
     */
    public val message: String
        get() = describe()

    /**
     * The document's text holds half of a surrogate pair without the other half, which UTF-8 has no bytes for. It
     * stays in the document, and `toString()` prints it as is. [keyPath] names the first key or string holding one:
     * the keys leading to it, the key holding it last, array indices left out. It is null when only comments or
     * spacing hold one.
     */
    @Serializable
    @SerialName("NotUnicode")
    public data class NotUnicode(val keyPath: List<String>?) : TomlWriteError

    /**
     * The document nests deeper than [TomlDocument.NESTING_LIMIT], so its text would not parse back. It stays in the
     * document, and `toString()` prints it as is. [keyPath] names the keys leading to where it nests too deep, array
     * indices left out.
     */
    @Serializable
    @SerialName("TooDeep")
    public data class TooDeep(val keyPath: List<String>) : TomlWriteError

    /**
     * The document's [TomlDocument.version] is TOML 1.0, and it holds [feature], which only TOML 1.1 reads and which
     * has no TOML 1.0 form, such as an inline table with a trailing comma. It stays in the document, and `toString()`
     * prints it as is. [keyPath] names the key whose value holds it, array indices left out.
     */
    @Serializable
    @SerialName("NotInToml10")
    public data class NotInToml10(
        val keyPath: List<String>,
        val feature: Toml11Feature,
    ) : TomlWriteError
}

/**
 * Reading or writing failed in the source or sink itself, or it was closed; [detail] is the system's account, when it
 * gave one.
 */
@Serializable
@SerialName("Io")
public data class TomlIoError(val detail: String?) : TomlReadError, TomlWriteError
