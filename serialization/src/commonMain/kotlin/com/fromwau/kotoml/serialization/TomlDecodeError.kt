package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.IError
import com.fromwau.kotoml.TomlParseError
import com.fromwau.kotoml.TomlType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why [Toml] could not turn TOML into a value.
 *
 * Every case that concerns one place in the document carries its [path]: the dotted key path with array
 * indexes, such as `servers[1].port`, keys that are not bare spelled as TOML quotes them (`"a.b"`). The root
 * table is the empty path.
 *
 * ```kotlin
 * when (val decoded = Toml.decodeFromString<Config>(text)) {
 *     is Result.Success -> use(decoded.value)
 *     is Result.Error -> when (val error = decoded.error) {
 *         is TomlDecodeError.MissingKey -> println("${error.path} is required")
 *         else -> println(error)
 *     }
 * }
 * ```
 */
@Serializable
public sealed interface TomlDecodeError : IError {
    /** The text is not TOML; [error] says where and why, and `error.render(text)` gives the message. */
    @Serializable
    @SerialName("Parse")
    public data class Parse(val error: TomlParseError) : TomlDecodeError

    /** The type asked for has no serializer, such as a class that is not `@Serializable`; [type] names it. */
    @Serializable
    @SerialName("NoSerializer")
    public data class NoSerializer(val type: String) : TomlDecodeError

    /** The type asked for, named by its [serialName], is neither a class nor a map, and a document is a table. */
    @Serializable
    @SerialName("UnsupportedRoot")
    public data class UnsupportedRoot(val serialName: String) : TomlDecodeError

    /** A required field has no key at [path] and no default. */
    @Serializable
    @SerialName("MissingKey")
    public data class MissingKey(override val path: String) : Located

    /** The key at [path] names no field of the class; set `ignoreUnknownKeys` to skip such keys. */
    @Serializable
    @SerialName("UnknownKey")
    public data class UnknownKey(override val path: String) : Located

    /** The item at [path] is a [found], where the field reads a TOML [expected]. */
    @Serializable
    @SerialName("TypeMismatch")
    public data class TypeMismatch(
        override val path: String,
        val expected: TomlType,
        val found: TomlType,
    ) : Located

    /** The date-time at [path] is a [found], where the field reads a [expected]. */
    @Serializable
    @SerialName("DatetimeKindMismatch")
    public data class DatetimeKindMismatch(
        override val path: String,
        val expected: DatetimeKind,
        val found: DatetimeKind,
    ) : Located

    /** The time at [path] is a leap second, `:60`, which kotlinx-datetime cannot hold. */
    @Serializable
    @SerialName("LeapSecond")
    public data class LeapSecond(override val path: String) : Located

    /** The number at [path], [value], does not fit the field's type, named by its serial name [target]. */
    @Serializable
    @SerialName("OutOfRange")
    public data class OutOfRange(
        override val path: String,
        val value: String,
        val target: String,
    ) : Located

    /** The string at [path], [text], is not a valid [target], named by its serial name, such as `kotlin.Char`. */
    @Serializable
    @SerialName("Unparsable")
    public data class Unparsable(
        override val path: String,
        val text: String,
        val target: String,
    ) : Located

    /** The string at [path], [value], names no entry of the enum; [names] are the ones it has, case-sensitive. */
    @Serializable
    @SerialName("UnknownEnumValue")
    public data class UnknownEnumValue(
        override val path: String,
        val value: String,
        val names: List<String>,
    ) : Located

    /**
     * The table at [path] names the subclass [name] in its discriminator key, and there is no such subclass.
     * [names] lists a sealed class's subclasses, and is empty for an open polymorphic type.
     */
    @Serializable
    @SerialName("UnknownSubtype")
    public data class UnknownSubtype(
        override val path: String,
        val name: String,
        val names: List<String>,
    ) : Located

    /** The field at [path] is a map whose keys are neither strings nor enums, which a TOML table cannot hold. */
    @Serializable
    @SerialName("NonStringMapKey")
    public data class NonStringMapKey(override val path: String) : Located

    /** A serializer refused the item at [path] for its own reason; [detail] is its message, when it gave one. */
    @Serializable
    @SerialName("Serializer")
    public data class Serializer(
        override val path: String,
        val detail: String?,
    ) : Located

    /** An error at one place in the document. */
    @Serializable
    public sealed interface Located : TomlDecodeError {
        /** Where in the document, such as `servers[1].port`; empty for the root table. */
        public val path: String
    }
}

/** The four kinds of TOML date-time. */
public enum class DatetimeKind {
    /** `1979-05-27T07:32:00Z`, read as a `kotlin.time.Instant`. */
    OffsetDateTime,

    /** `1979-05-27T07:32:00`, read as a `kotlinx.datetime.LocalDateTime`. */
    LocalDateTime,

    /** `1979-05-27`, read as a `kotlinx.datetime.LocalDate`. */
    LocalDate,

    /** `07:32:00`, read as a `kotlinx.datetime.LocalTime`. */
    LocalTime,
}
