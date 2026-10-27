package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.ResultError
import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why [Toml] could not turn a value into TOML.
 *
 * Every case that concerns one place in the value carries its [path][Located.path], spelled as in
 * [TomlDecodeError]: `servers[1].port`, the root table being the empty path. [message] words the error for a
 * person.
 *
 * ```kotlin
 * when (val encoded = Toml.encodeToString(settings)) {
 *     is Result.Success -> file.writeText(encoded.value)
 *     is Result.Error -> println(encoded.error)
 * }
 * ```
 */
@Serializable
public sealed interface TomlEncodeError : ResultError {
    /** The error as a person reads it, led by its path, such as `ports: a map key that is not a string`. */
    public val message: String
        get() = describe()

    /**
     * The type at [path] has no serializer, such as a class that is not `@Serializable` or a contextual type
     * `serializersModule` has none for; [type] is its simple name, such as `Color`.
     */
    @Serializable
    @SerialName("NoSerializer")
    public data class NoSerializer(
        val type: String,
        override val path: String = "",
    ) : Located

    /** The value, of the type named by [serialName], is neither a class nor a map, and a document is a table. */
    @Serializable
    @SerialName("UnsupportedRoot")
    public data class UnsupportedRoot(val serialName: String) : TomlEncodeError

    /** The map at [path] has a key not written as a string, such as a number, which a TOML table cannot hold. */
    @Serializable
    @SerialName("NonStringMapKey")
    public data class NonStringMapKey(override val path: String) : Located

    /**
     * The type at [path] is nullable. TOML has no null and a missing key means the field's default, so kotoml refuses
     * nullable types: give the field a non-null type, with a default where the key may be missing.
     */
    @Serializable
    @SerialName("NullableType")
    public data class NullableType(override val path: String) : Located

    /** A serializer of your own wrote a null at [path], which TOML cannot hold. */
    @Serializable
    @SerialName("NullValue")
    public data class NullValue(override val path: String) : Located

    /** The number at [path], [value], is past what a TOML integer holds, as a `ULong` above `Long.MAX_VALUE`. */
    @Serializable
    @SerialName("OutOfRange")
    public data class OutOfRange(
        override val path: String,
        val value: String,
    ) : Located

    /**
     * The polymorphic value at [path] cannot carry its discriminator: it is not a class, or it has a field
     * named like the discriminator key [key].
     */
    @Serializable
    @SerialName("DiscriminatorConflict")
    public data class DiscriminatorConflict(
        override val path: String,
        val key: String,
    ) : Located

    /**
     * The string or character at [path] holds half of a surrogate pair without the other half, which is not
     * Unicode text and which TOML cannot hold.
     */
    @Serializable
    @SerialName("NotUnicode")
    public data class NotUnicode(override val path: String) : Located

    /**
     * The value at [path] nests deeper than kotoml writes: classes, lists and maps one level less than
     * [TomlDocument.NESTING_LIMIT], so that what it writes reads back in either list style.
     */
    @Serializable
    @SerialName("TooDeep")
    public data class TooDeep(override val path: String) : Located

    /** A serializer refused the value at [path] for its own reason; [detail] is its message, when it gave one. */
    @Serializable
    @SerialName("Serializer")
    public data class Serializer(
        override val path: String,
        val detail: String?,
    ) : Located

    /** An error at one place in the value. */
    @Serializable
    public sealed interface Located : TomlEncodeError {
        /** Where in the value, such as `servers[1].port`; empty for the root table. */
        public val path: String
    }
}

private fun TomlEncodeError.describe(): String = when (this) {
    is TomlEncodeError.NoSerializer -> at(path, "no serializer for $type")
    is TomlEncodeError.UnsupportedRoot -> "$serialName cannot be written as a document, which is a table"
    is TomlEncodeError.NonStringMapKey -> at(path, "a map key that is not a string, which a table cannot hold")
    is TomlEncodeError.NullableType -> at(path, "a nullable type, which TOML cannot hold; give it a default instead")
    is TomlEncodeError.NullValue -> at(path, "a serializer wrote a null, which TOML cannot hold")
    is TomlEncodeError.OutOfRange -> at(path, "$value does not fit in a TOML integer or date-time")
    is TomlEncodeError.DiscriminatorConflict ->
        at(path, "the value cannot carry its type key $key: it is not a class, or has a field of that name")
    is TomlEncodeError.NotUnicode -> at(path, "half of a surrogate pair, which is not Unicode text")
    is TomlEncodeError.TooDeep ->
        at(path, "nests deeper than ${TomlDocument.NESTING_LIMIT} levels, so it would not parse back")
    is TomlEncodeError.Serializer -> at(path, detail ?: "refused by its serializer")
}
