package com.fromwau.kotoml.serialization

import com.fromwau.kern.fuzzy.didYouMean
import com.fromwau.kotoml.Item
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
 * Decodes the item [input] at [path]; a null [input] is an empty item, which holds no value.
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

    private fun mismatch(expected: TomlType): Nothing =
        throw DecodeFailure(TomlDecodeError.TypeMismatch("$path", expected, input?.type ?: TomlType.Missing))

    private fun scalar(): Value? = (input as? TomlInput.Scalar)?.value

    // TOML holds no null, so a serializer asking whether one is here is always told no.
    override fun decodeNotNullMark(): Boolean = true

    override fun decodeNull(): Nothing? = throw DecodeFailure(TomlDecodeError.NullValue("$path"))

    override fun decodeBoolean(): Boolean = scalar()?.booleanOrNull ?: mismatch(TomlType.Boolean)

    override fun decodeByte(): Byte =
        input.integerIn(path, "kotlin.Byte", Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()).toByte()

    override fun decodeShort(): Short =
        input.integerIn(path, "kotlin.Short", Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()).toShort()

    override fun decodeInt(): Int =
        input.integerIn(path, "kotlin.Int", Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toInt()

    override fun decodeLong(): Long = scalar()?.longOrNull ?: mismatch(TomlType.Integer)

    override fun decodeDouble(): Double {
        val value = scalar()
        return value?.doubleOrNull ?: value?.longOrNull?.toDouble() ?: mismatch(TomlType.Float)
    }

    override fun decodeFloat(): Float {
        val double = decodeDouble()
        val float = double.toFloat()
        val overflows = float.isInfinite() && double.isFinite()
        val underflows = float == 0f && double != 0.0
        if (overflows || underflows) {
            throw DecodeFailure(TomlDecodeError.OutOfRange("$path", "$double", "kotlin.Float"))
        }
        return float
    }

    override fun decodeChar(): Char {
        val text = decodeString()
        return text.singleOrNull() ?: throw DecodeFailure(TomlDecodeError.Unparsable("$path", text, "kotlin.Char"))
    }

    // A native date-time read as a string gives its TOML text, so a serializer that parses dates itself works.
    override fun decodeString(): String {
        val value = scalar()
        return value?.stringOrNull ?: value?.datetimeOrNull?.toString() ?: mismatch(TomlType.String)
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = decodeString()
        val index = enumDescriptor.getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            val names = enumDescriptor.elementNames.toList()
            throw DecodeFailure(TomlDecodeError.UnknownEnumValue("$path", name, names, didYouMean(name, names)))
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
                throw DecodeFailure(TomlDecodeError.NonStringMapKey("$path"))
            }
            MapDecoder(toml, table.entries, path)
        }
        is PolymorphicKind -> {
            val table = input as? TomlInput.Table ?: mismatch(TomlType.Table)
            PolymorphicDecoder(toml, table, descriptor, path)
        }
        else -> {
            val table = input as? TomlInput.Table ?: mismatch(TomlType.Table)
            ClassDecoder(toml, table.entries, descriptor, path, skippedKey)
        }
    }

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
        throw DecodeFailure(TomlDecodeError.NullableType("$path"))

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        if (deserializer.descriptor.isNullable) throw DecodeFailure(TomlDecodeError.NullableType("$path"))
        try {
            val known = input?.let {
                knownValueOrNull(deserializer.descriptor.serialName, it, path, nativeDatetime = !isMapKey)
            }
            @Suppress("UNCHECKED_CAST")
            return if (known != null) known as T else deserializer.deserialize(this)
        } catch (failure: DecodeFailure) {
            throw failure
        } catch (failure: Exception) {
            throw DecodeFailure(TomlDecodeError.Serializer("$path", failure.message))
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
    private val entries: Map<String, Item>,
    descriptor: SerialDescriptor,
    path: TomlPath,
    skippedKey: String?,
) : NodeCompositeDecoder(toml, path) {
    private var next = 0

    init {
        if (!toml.ignoreUnknownKeys) {
            val unknown = entries.keys.firstOrNull {
                it != skippedKey && descriptor.getElementIndex(it) == CompositeDecoder.UNKNOWN_NAME
            }
            if (unknown != null) throw DecodeFailure(TomlDecodeError.UnknownKey("${path.key(unknown)}"))
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
                else -> throw DecodeFailure(TomlDecodeError.MissingKey("${path.key(name)}"))
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder {
        val name = descriptor.getElementName(index)
        return NodeDecoder(toml, entries[name]?.toInput(), path.key(name))
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
    entries: Map<String, Item>,
    path: TomlPath,
) : NodeCompositeDecoder(toml, path) {
    private val keys = entries.keys.toList()
    private val items = entries.values.toList()
    private var next = 0

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = keys.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (next < keys.size * 2) next++ else CompositeDecoder.DECODE_DONE

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder {
        val key = keys[index / 2]
        return if (index % 2 == 0) {
            NodeDecoder(toml, TomlInput.Scalar(Value.String(key)), path.key(key), isMapKey = true)
        } else {
            NodeDecoder(toml, items[index / 2].toInput(), path.key(key))
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
        val keyPath = path.key(key)
        val item = table.entries[key] ?: throw DecodeFailure(TomlDecodeError.MissingKey("$keyPath"))
        name = item.stringOrNull
            ?: throw DecodeFailure(TomlDecodeError.TypeMismatch("$keyPath", TomlType.String, item.type))
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
        throw DecodeFailure(TomlDecodeError.UnknownSubtype("$path", name, known, didYouMean(name, known)))
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
            throw DecodeFailure(TomlDecodeError.DiscriminatorConflict("$path", key))
        }
        return super.decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeDecoder = NodeDecoder(toml, table, path, skippedKey = key)
}
