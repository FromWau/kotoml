package com.fromwau.kotoml.serialization

import com.fromwau.kern.fuzzy.didYouMean
import com.fromwau.kotoml.Decor
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.Value
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Decodes the item [input] at [path]; a null [input] stands for a key the document does not have.
 *
 * @param skippedKey a key of [input] that is not one of the class's fields, the discriminator of a
 *   polymorphic value.
 */
internal class NodeDecoder(
    private val toml: Toml,
    private val input: TomlInput?,
    private val path: TomlPath,
    private val isMapKey: Boolean = false,
    private val skippedKey: String? = null,
) : Decoder {
    override val serializersModule: SerializersModule
        get() = toml.serializersModule

    private fun mismatch(
        expected: TomlType,
        meant: List<String> = emptyList(),
    ): Nothing = throw DecodeFailure(mismatchAt(path, expected, input, meant))

    private fun scalar(): Value? = (input as? TomlInput.Scalar)?.value

    // TOML holds no null, so a serializer asking whether one is here is always told no.
    override fun decodeNotNullMark(): Boolean = true

    override fun decodeNull(): Nothing? = throw DecodeFailure(TomlDecodeError.NullValue("$path", path.position))

    override fun decodeBoolean(): Boolean = scalar()?.booleanOrNull ?: mismatch(TomlType.Boolean, BOOLEAN_NAMES)

    override fun decodeByte(): Byte =
        input.integerIn(path, "kotlin.Byte", Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()).toByte()

    override fun decodeShort(): Short =
        input.integerIn(path, "kotlin.Short", Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()).toShort()

    override fun decodeInt(): Int =
        input.integerIn(path, "kotlin.Int", Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toInt()

    override fun decodeLong(): Long = scalar()?.longOrNull ?: mismatch(TomlType.Integer)

    override fun decodeDouble(): Double = scalar()?.asDouble ?: mismatch(TomlType.Float, FLOAT_NAMES)

    override fun decodeFloat(): Float {
        val double = decodeDouble()
        return double.asFloatOrNull()
            ?: throw DecodeFailure(TomlDecodeError.OutOfRange("$path", "$double", "kotlin.Float", path.position))
    }

    override fun decodeChar(): Char {
        val text = decodeString()
        return text.singleOrNull()
            ?: throw DecodeFailure(TomlDecodeError.Unparsable("$path", text, "kotlin.Char", path.position))
    }

    override fun decodeString(): String = string()

    // A native date-time read as a string gives its TOML text, so a serializer that parses dates itself works.
    private fun string(meant: List<String> = emptyList()): String {
        val value = scalar()
        return value?.stringOrNull ?: value?.datetimeOrNull?.toString() ?: mismatch(TomlType.String, meant)
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val names = enumDescriptor.elementNames.toList()
        val name = string(names)
        val index = enumDescriptor.getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            val suggestion = didYouMean(name, names)
            throw DecodeFailure(TomlDecodeError.UnknownEnumValue("$path", name, names, suggestion, path.position))
        }
        return index
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder =
        if (descriptor.isUnsignedInteger) UnsignedDecoder(this, input, path) else this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = when (descriptor.kind) {
        StructureKind.LIST -> {
            val sequence = input as? TomlInput.Sequence ?: mismatch(TomlType.Array)
            ListDecoder(toml, sequence.elements, path)
        }
        StructureKind.MAP -> {
            val table = input as? TomlInput.Table ?: mismatch(TomlType.Table)
            if (!descriptor.getElementDescriptor(0).isStringLike(serializersModule)) {
                throw DecodeFailure(TomlDecodeError.NonStringMapKey("$path", path.position))
            }
            MapDecoder(toml, table, path)
        }
        is PolymorphicKind -> {
            val table = input as? TomlInput.Table ?: mismatch(TomlType.Table)
            PolymorphicDecoder(toml, table, descriptor, path)
        }
        else -> {
            val table = input as? TomlInput.Table ?: mismatch(TomlType.Table)
            ClassDecoder(toml, table, descriptor, path, skippedKey)
        }
    }

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
        throw DecodeFailure(TomlDecodeError.NullableType("$path", path.position))

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        val descriptor = deserializer.descriptor
        if (descriptor.isNullable) throw DecodeFailure(TomlDecodeError.NullableType("$path", path.position))
        try {
            val known = input?.let {
                knownValueOrNull(descriptor.serialName, it, path, nativeDatetime = !isMapKey)
            }
            @Suppress("UNCHECKED_CAST")
            return if (known != null) known as T else deserializer.deserialize(this)
        } catch (failure: DecodeFailure) {
            throw failure
        } catch (failure: Exception) {
            descriptor.missingContextualType(serializersModule)?.let { type ->
                throw DecodeFailure(TomlDecodeError.NoSerializer(type, "$path", path.position))
            }
            throw DecodeFailure(TomlDecodeError.Serializer("$path", failure.message, path.position))
        }
    }
}

/** Whether a map key of this type is read from a string, the only key a TOML table has. */
private fun SerialDescriptor.isStringLike(module: SerializersModule): Boolean {
    var descriptor = this
    while (true) {
        descriptor = when {
            descriptor.isInline -> descriptor.getElementDescriptor(0)
            descriptor.kind == SerialKind.CONTEXTUAL -> module.contextualDescriptorOrNull(descriptor) ?: return false
            else -> return descriptor.kind in STRING_LIKE_KINDS
        }
    }
}

private val STRING_LIKE_KINDS = setOf(PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM)

/** Whether this is a class with a field named [name]. */
internal fun SerialDescriptor.hasField(name: String): Boolean =
    (kind == StructureKind.CLASS || kind == StructureKind.OBJECT) &&
        getElementIndex(name) != CompositeDecoder.UNKNOWN_NAME

/** Decodes the elements of one class, list, map or polymorphic value, each through its own [NodeDecoder]. */
internal abstract class NodeCompositeDecoder(
    protected val toml: Toml,
    protected val path: TomlPath,
) : CompositeDecoder {
    override val serializersModule: SerializersModule
        get() = toml.serializersModule

    protected abstract fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder

    override fun endStructure(descriptor: SerialDescriptor) {}

    override fun decodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = child(descriptor, index).decodeBoolean()

    override fun decodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Byte = child(descriptor, index).decodeByte()

    override fun decodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Short = child(descriptor, index).decodeShort()

    override fun decodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Int = child(descriptor, index).decodeInt()

    override fun decodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Long = child(descriptor, index).decodeLong()

    override fun decodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Float = child(descriptor, index).decodeFloat()

    override fun decodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Double = child(descriptor, index).decodeDouble()

    override fun decodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Char = child(descriptor, index).decodeChar()

    override fun decodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): String = child(descriptor, index).decodeString()

    override fun decodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Decoder = child(descriptor, index).decodeInline(descriptor.getElementDescriptor(index))

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T = child(descriptor, index).decodeSerializableValue(deserializer)

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = child(descriptor, index).decodeNullableSerializableValue(deserializer)
}

private class ClassDecoder(
    toml: Toml,
    private val table: TomlInput.Table,
    descriptor: SerialDescriptor,
    path: TomlPath,
    skippedKey: String?,
) : NodeCompositeDecoder(toml, path) {
    private val entries = table.entries
    private var next = 0

    init {
        if (!toml.ignoreUnknownKeys) {
            val unknown = entries.keys.firstOrNull {
                it != skippedKey && descriptor.getElementIndex(it) == CompositeDecoder.UNKNOWN_NAME
            }
            if (unknown != null) {
                val keyPath = path.key(unknown, table.positionOf(unknown))
                val suggestion = didYouMean(unknown, descriptor.elementNames.toList())
                throw DecodeFailure(TomlDecodeError.UnknownKey("$keyPath", suggestion, keyPath.position))
            }
        }
    }

    // A missing key is skipped when its field has a default.
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (next < descriptor.elementsCount) {
            val index = next++
            val name = descriptor.getElementName(index)
            when {
                name in entries -> return index
                descriptor.isElementOptional(index) -> continue
                else -> throw DecodeFailure(TomlDecodeError.MissingKey("${path.key(name)}", path.position))
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder {
        val name = descriptor.getElementName(index)
        return NodeDecoder(toml, entries[name]?.toInput(), path.key(name, table.positionOf(name)))
    }
}

private class ListDecoder(
    toml: Toml,
    private val elements: List<TomlInput>,
    path: TomlPath,
) : NodeCompositeDecoder(toml, path) {
    private var next = 0

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = elements.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (next < elements.size) next++ else CompositeDecoder.DECODE_DONE

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder = NodeDecoder(toml, elements[index], path.index(index))
}

/** A map's elements alternate: a key at each even index, its value at the odd index after it. */
private class MapDecoder(
    toml: Toml,
    private val table: TomlInput.Table,
    path: TomlPath,
) : NodeCompositeDecoder(toml, path) {
    private val keys = table.entries.keys.toList()
    private val items = table.entries.values.toList()
    private var next = 0

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = keys.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (next < keys.size * 2) next++ else CompositeDecoder.DECODE_DONE

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder {
        val key = keys[index / 2]
        val keyPath = path.key(key, table.positionOf(key))
        return if (index % 2 == 0) {
            NodeDecoder(toml, TomlInput.Scalar(Value.String(key)), keyPath, isMapKey = true)
        } else {
            NodeDecoder(toml, items[index / 2].toInput(), keyPath)
        }
    }
}

/**
 * A polymorphic value is its subclass's serial name at index 0, read from the discriminator key, and the value
 * itself at index 1, read from the same table without that key.
 */
private class PolymorphicDecoder(
    toml: Toml,
    private val table: TomlInput.Table,
    descriptor: SerialDescriptor,
    path: TomlPath,
) : NodeCompositeDecoder(toml, path) {
    private val key = toml.classDiscriminator
    private val name: String
    private var next = 0

    init {
        val keyPath = path.key(key, table.positionOf(key))
        val item = table.entries[key] ?: throw DecodeFailure(TomlDecodeError.MissingKey("$keyPath", path.position))
        name = item.stringOrNull ?: throw DecodeFailure(mismatchAt(keyPath, TomlType.String, item.toInput()))
        checkSubtype(descriptor)
    }

    private fun checkSubtype(descriptor: SerialDescriptor) {
        val known = when (descriptor.kind) {
            PolymorphicKind.SEALED -> {
                val names = descriptor.getElementDescriptor(1).elementNames.toList()
                if (name in names) return
                names
            }
            else -> {
                val base = descriptor.capturedKClass ?: return
                if (serializersModule.getPolymorphic(base, name) != null) return
                emptyList()
            }
        }
        val suggestion = didYouMean(name, known)
        throw DecodeFailure(TomlDecodeError.UnknownSubtype("$path", name, known, suggestion, path.position))
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (next < 2) next++ else CompositeDecoder.DECODE_DONE

    override fun decodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): String = name

    // A field named like the key would be read from the discriminator, so it is refused as writing refuses it.
    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        if (deserializer.descriptor.hasField(key)) {
            throw DecodeFailure(TomlDecodeError.DiscriminatorConflict("$path", key, path.position))
        }
        return super.decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder = NodeDecoder(toml, table, path, skippedKey = key)
}

/** What a `Double` field reads this value as: a float, or an integer converted. */
internal val Value.asDouble: Double?
    get() = doubleOrNull ?: longOrNull?.toDouble()

/** What a `Float` field reads this number as, or null when a `Float` would round it to zero or to infinity. */
internal fun Double.asFloatOrNull(): Float? {
    val float = toFloat()
    val overflows = float.isInfinite() && isFinite()
    val underflows = float == 0f && this != 0.0
    return float.takeUnless { overflows || underflows }
}

/**
 * The error for [input] at [path] not being [expected]: [TomlDecodeError.MissingKey] when nothing is there.
 * [meant] are the values of the expected type a string or boolean found instead may be a slip for.
 */
internal fun mismatchAt(
    path: TomlPath,
    expected: TomlType,
    input: TomlInput?,
    meant: List<String> = emptyList(),
): TomlDecodeError = if (input == null) {
    TomlDecodeError.MissingKey("$path", path.position)
} else {
    val suggestion = input.closestOf(meant, signed = expected == TomlType.Float)
    TomlDecodeError.TypeMismatch("$path", expected, input.type, input.spelling(), suggestion, path.position)
}

/**
 * Which of [meant] a string or boolean is most likely a slip for, ranked among TOML's keywords; a word closer to
 * a keyword the field does not read suggests nothing. A [signed] word keeps its sign, as in `-inf`.
 */
private fun TomlInput.closestOf(
    meant: List<String>,
    signed: Boolean,
): String? {
    val value = (this as? TomlInput.Scalar)?.value ?: return null
    val written = value.stringOrNull ?: value.booleanOrNull?.toString() ?: return null
    val sign = written.take(1).takeIf { signed && it in SIGNS }.orEmpty()
    val word = written.removePrefix(sign)
    val closest = if (word in meant) word else didYouMean(word, KEYWORDS + meant, preferred = meant)
    return closest?.takeIf { it in meant }?.let { sign + it }
}

private val BOOLEAN_NAMES = listOf("true", "false")
private val FLOAT_NAMES = listOf("inf", "nan")
private val KEYWORDS = BOOLEAN_NAMES + FLOAT_NAMES
private val SIGNS = listOf("+", "-")

/** A scalar as it is written, without the text around it; null for a table or an array. */
private fun TomlInput.spelling(): String? = (this as? TomlInput.Scalar)
    ?.value
    ?.copy()
    ?.also { it.decor = Decor() }
    ?.toString()

/** The simple name of the contextual type this describes, when the module has no serializer for it. */
internal fun SerialDescriptor.missingContextualType(module: SerializersModule): String? {
    if (kind != SerialKind.CONTEXTUAL || module.contextualDescriptorOrNull(this) != null) return null
    return capturedKClass?.simpleName ?: serialName
}
