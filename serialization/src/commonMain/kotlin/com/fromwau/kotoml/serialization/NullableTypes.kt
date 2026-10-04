package com.fromwau.kotoml.serialization

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.modules.SerializersModule

/**
 * Where the first nullable type in [descriptor] is, or null when it has none: the fields of its classes, the element
 * types of its lists, the key and value types of its maps, and the subclasses [module] can name.
 */
internal fun firstNullableType(
    descriptor: SerialDescriptor,
    module: SerializersModule,
): TomlPath? = NullableTypeSearch(module).find(descriptor, TomlPath.Root)

private class NullableTypeSearch(private val module: SerializersModule) {
    // A type met again holds no nullable type, or the search would have stopped there: this ends self-reference.
    private val visited = mutableSetOf<SerialDescriptor>()

    fun find(
        descriptor: SerialDescriptor,
        path: TomlPath,
    ): TomlPath? {
        if (descriptor.isNullable) return path
        if (!visited.add(descriptor)) return null
        if (descriptor.isInline) return anyOf(descriptor.elementDescriptors, path)
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> fields(descriptor, path)
            StructureKind.LIST -> anyOf(descriptor.elementDescriptors, path.element())
            StructureKind.MAP -> descriptor.elementOrNull(0)?.let { find(it, path) }
                ?: descriptor.elementOrNull(1)?.let { find(it, path.element()) }
            PolymorphicKind.SEALED -> descriptor.elementOrNull(1)?.let { anyOf(it.elementDescriptors, path) }
            PolymorphicKind.OPEN -> anyOf(module.getPolymorphicDescriptors(descriptor), path)
            SerialKind.CONTEXTUAL -> module.contextualDescriptorOrNull(descriptor)?.let { find(it, path) }
            else -> null
        }
    }

    private fun fields(
        descriptor: SerialDescriptor,
        path: TomlPath,
    ): TomlPath? = (0 until descriptor.elementsCount).firstNotNullOfOrNull { index ->
        find(descriptor.getElementDescriptor(index), path.key(descriptor.getElementName(index)))
    }

    private fun SerialDescriptor.elementOrNull(index: Int): SerialDescriptor? =
        if (index < elementsCount) getElementDescriptor(index) else null

    private fun anyOf(
        descriptors: Iterable<SerialDescriptor>,
        path: TomlPath,
    ): TomlPath? = descriptors.firstNotNullOfOrNull { find(it, path) }
}

/** The descriptor this module gives the contextual [descriptor], or null when it has none. */
internal fun SerializersModule.contextualDescriptorOrNull(descriptor: SerialDescriptor): SerialDescriptor? = try {
    getContextualDescriptor(descriptor)
} catch (_: Exception) {
    // A contextual provider for generic types may fail without the type arguments only a value has.
    null
}
