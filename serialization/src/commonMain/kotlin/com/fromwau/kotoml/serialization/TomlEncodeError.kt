package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.IError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why [Toml] could not turn a value into TOML.
 *
 * Every case that concerns one place in the value carries its [path][Located.path], spelled as in
 * [TomlDecodeError]: `servers[1].port`, the root table being the empty path.
 *
 * ```kotlin
 * when (val encoded = Toml.encodeToString(settings)) {
 *     is Result.Success -> file.writeText(encoded.value)
 *     is Result.Error -> println(encoded.error)
 * }
 * ```
 */
@Serializable
public sealed interface TomlEncodeError : IError {
    /** The type has no serializer, such as a class that is not `@Serializable`; [type] names it. */
    @Serializable
    @SerialName("NoSerializer")
    public data class NoSerializer(val type: String) : TomlEncodeError

    /** The value, of the type named by [serialName], is neither a class nor a map, and a document is a table. */
    @Serializable
    @SerialName("UnsupportedRoot")
    public data class UnsupportedRoot(val serialName: String) : TomlEncodeError

    /** The map at [path] has a key that is neither a string nor an enum, which a TOML table cannot hold. */
    @Serializable
    @SerialName("NonStringMapKey")
    public data class NonStringMapKey(override val path: String) : Located

    /** The list at [path] holds a null, which a TOML array cannot hold. */
    @Serializable
    @SerialName("NullElement")
    public data class NullElement(override val path: String) : Located

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
