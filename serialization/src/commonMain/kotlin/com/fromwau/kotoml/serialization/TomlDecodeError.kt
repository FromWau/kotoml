package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.IError
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlParseError
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.render
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why [Toml] could not turn TOML into a value.
 *
 * Every case that concerns one place in the document carries its [path][Located.path]: the dotted key path with
 * array indexes, such as `servers[1].port`, keys that are not bare spelled as TOML quotes them (`"a.b"`). The root
 * table is the empty path. Read from text or a parsed document, it also carries the
 * [position][Located.position] of the key it names, or of the nearest key above it, as a missing key has none.
 * [message] words the error for a person.
 *
 * ```kotlin
 * when (val decoded = Toml.decodeFromString<Config>(text)) {
 *     is Result.Success -> use(decoded.value)
 *     is Result.Error -> when (val error = decoded.error) {
 *         is TomlDecodeError.MissingKey -> println("${error.path} is required")
 *         else -> println(error.message)
 *     }
 * }
 * ```
 */
@Serializable
public sealed interface TomlDecodeError : IError {
    /**
     * The error as a person reads it, led by its path, such as
     * `database.pool: expected an integer, found the string "ten"`.
     */
    public val message: String
        get() = describe()

    /** The text is not TOML; [error] says where and why, and `error.render(text)` gives the message. */
    @Serializable
    @SerialName("Parse")
    public data class Parse(val error: TomlParseError) : TomlDecodeError

    /**
     * The type at [path] has no serializer, such as a class that is not `@Serializable` or a contextual type
     * `serializersModule` has none for; [type] is its simple name, such as `Color`.
     */
    @Serializable
    @SerialName("NoSerializer")
    public data class NoSerializer(
        val type: String,
        override val path: String = "",
        override val position: TextPosition? = null,
    ) : Located

    /** The type asked for, named by its [serialName], is neither a class nor a map, and a document is a table. */
    @Serializable
    @SerialName("UnsupportedRoot")
    public data class UnsupportedRoot(val serialName: String) : TomlDecodeError

    /**
     * The type at [path] is nullable. TOML has no null and a missing key means the field's default, so kotoml refuses
     * nullable types: give the field a non-null type, with a default where the key may be missing. One found from the
     * types before anything is read has no [position].
     */
    @Serializable
    @SerialName("NullableType")
    public data class NullableType(
        override val path: String,
        override val position: TextPosition? = null,
    ) : Located

    /** A serializer of your own asked for a null at [path], which TOML cannot hold. */
    @Serializable
    @SerialName("NullValue")
    public data class NullValue(
        override val path: String,
        override val position: TextPosition? = null,
    ) : Located

    /** A required field has no key at [path] and no default. */
    @Serializable
    @SerialName("MissingKey")
    public data class MissingKey(
        override val path: String,
        override val position: TextPosition? = null,
    ) : Located

    /**
     * The key at [path] names no field of the class; set `ignoreUnknownKeys` to skip such keys. [suggestion] is the
     * field name closest to it, or null when none is close.
     */
    @Serializable
    @SerialName("UnknownKey")
    public data class UnknownKey(
        override val path: String,
        val suggestion: String? = null,
        override val position: TextPosition? = null,
    ) : Located

    /**
     * The item at [path] is a [found], where the field reads a TOML [expected]. [value] is the found value as it is
     * written, such as `"ten"`, when it is a string, number, boolean or date-time.
     */
    @Serializable
    @SerialName("TypeMismatch")
    public data class TypeMismatch(
        override val path: String,
        val expected: TomlType,
        val found: TomlType,
        val value: String? = null,
        override val position: TextPosition? = null,
    ) : Located

    /** The date-time at [path] is a [found], where the field reads a [expected]. */
    @Serializable
    @SerialName("DatetimeKindMismatch")
    public data class DatetimeKindMismatch(
        override val path: String,
        val expected: DatetimeKind,
        val found: DatetimeKind,
        override val position: TextPosition? = null,
    ) : Located

    /** The time at [path] is a leap second, `:60`, which kotlinx-datetime cannot hold. */
    @Serializable
    @SerialName("LeapSecond")
    public data class LeapSecond(
        override val path: String,
        override val position: TextPosition? = null,
    ) : Located

    /** The number at [path], [value], does not fit the field's type, named by its serial name [target]. */
    @Serializable
    @SerialName("OutOfRange")
    public data class OutOfRange(
        override val path: String,
        val value: String,
        val target: String,
        override val position: TextPosition? = null,
    ) : Located

    /** The string at [path], [text], is not a valid [target], named by its serial name, such as `kotlin.Char`. */
    @Serializable
    @SerialName("Unparsable")
    public data class Unparsable(
        override val path: String,
        val text: String,
        val target: String,
        override val position: TextPosition? = null,
    ) : Located

    /**
     * The string at [path], [value], names no entry of the enum; [names] are the ones it has, case-sensitive.
     * [suggestion] is the name closest to [value], or null when none is close.
     */
    @Serializable
    @SerialName("UnknownEnumValue")
    public data class UnknownEnumValue(
        override val path: String,
        val value: String,
        val names: List<String>,
        val suggestion: String?,
        override val position: TextPosition? = null,
    ) : Located

    /**
     * The table at [path] names the subclass [name] in its discriminator key, and there is no such subclass.
     * [names] lists a sealed class's subclasses, and is empty for an open polymorphic type. [suggestion] is the
     * name closest to [name], or null when none is close.
     */
    @Serializable
    @SerialName("UnknownSubtype")
    public data class UnknownSubtype(
        override val path: String,
        val name: String,
        val names: List<String>,
        val suggestion: String?,
        override val position: TextPosition? = null,
    ) : Located

    /** The field at [path] is a map whose keys are not read from strings, which a TOML table's keys are. */
    @Serializable
    @SerialName("NonStringMapKey")
    public data class NonStringMapKey(
        override val path: String,
        override val position: TextPosition? = null,
    ) : Located

    /** The subclass of the polymorphic value at [path] has a field named like the discriminator key [key]. */
    @Serializable
    @SerialName("DiscriminatorConflict")
    public data class DiscriminatorConflict(
        override val path: String,
        val key: String,
        override val position: TextPosition? = null,
    ) : Located

    /** A serializer refused the item at [path] for its own reason; [detail] is its message, when it gave one. */
    @Serializable
    @SerialName("Serializer")
    public data class Serializer(
        override val path: String,
        val detail: String?,
        override val position: TextPosition? = null,
    ) : Located

    /** An error at one place in the document. */
    @Serializable
    public sealed interface Located : TomlDecodeError {
        /** Where in the document, such as `servers[1].port`; empty for the root table. */
        public val path: String

        /**
         * The line and column of the key at [path], or of the nearest key above it when that one has no place in
         * the text, such as a missing key or an array element; null when the document was built in code.
         */
        public val position: TextPosition?
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

private fun TomlDecodeError.describe(): String = when (this) {
    is TomlDecodeError.Parse -> {
        val at = error.position?.let { "line ${it.line}, column ${it.column}: " }.orEmpty()
        // Without the parsed text, render gives the reason alone.
        at + error.render("").trimEnd()
    }
    is TomlDecodeError.UnsupportedRoot -> "$serialName cannot be read from a document, which is a table"
    is TomlDecodeError.NoSerializer -> at(path, "no serializer for $type")
    is TomlDecodeError.NullableType -> at(path, "a nullable type, which TOML cannot hold; give it a default instead")
    is TomlDecodeError.NullValue -> at(path, "a serializer asked for a null, which TOML cannot hold")
    is TomlDecodeError.MissingKey -> "missing key $path"
    is TomlDecodeError.UnknownKey -> "unknown key $path" + suggestion.asSuggestion()
    is TomlDecodeError.TypeMismatch -> {
        val foundText = value?.shortened()?.let { "the ${found.words()} $it" } ?: found.withArticle()
        at(path, "expected ${expected.withArticle()}, found $foundText")
    }
    is TomlDecodeError.DatetimeKindMismatch ->
        at(path, "expected ${expected.withArticle()}, found ${found.withArticle()}")
    is TomlDecodeError.LeapSecond -> at(path, "a leap second, which kotlinx-datetime cannot hold")
    is TomlDecodeError.OutOfRange -> at(path, "$value does not fit in $target")
    is TomlDecodeError.Unparsable -> at(path, "\"$text\" is not a valid $target")
    is TomlDecodeError.UnknownEnumValue -> at(path, "unknown value \"$value\"" + suggestion.orChoices(names))
    is TomlDecodeError.UnknownSubtype -> at(path, "unknown subclass \"$name\"" + suggestion.orChoices(names))
    is TomlDecodeError.NonStringMapKey -> at(path, "a map whose keys are not strings, which a table's keys are")
    is TomlDecodeError.DiscriminatorConflict -> at(path, "the subclass has a field named like the type key $key")
    is TomlDecodeError.Serializer -> at(path, detail ?: "refused by its serializer")
}

/** [sentence] led by [path], or alone for the root. */
internal fun at(
    path: String,
    sentence: String,
): String = if (path.isEmpty()) sentence else "$path: $sentence"

private fun String?.asSuggestion(): String = this?.let { ", did you mean $it?" }.orEmpty()

private fun String?.orChoices(names: List<String>): String = when {
    this != null -> asSuggestion()
    names.isEmpty() -> ""
    else -> ", expected one of ${names.joinToString(", ")}"
}

// A long or multi-line value would swamp the sentence it is quoted in.
private fun String.shortened(): String? = when {
    '\n' in this || '\r' in this -> null
    length > MAX_QUOTED_VALUE -> take(MAX_QUOTED_VALUE) + "…"
    else -> this
}

private const val MAX_QUOTED_VALUE = 60

private fun TomlType.words(): String = when (this) {
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

private fun TomlType.withArticle(): String = words().withArticle()

private fun DatetimeKind.withArticle(): String = when (this) {
    DatetimeKind.OffsetDateTime -> "an offset date-time"
    DatetimeKind.LocalDateTime -> "a local date-time"
    DatetimeKind.LocalDate -> "a local date"
    DatetimeKind.LocalTime -> "a local time"
}

private fun String.withArticle(): String = if (first() in "aeiou") "an $this" else "a $this"
