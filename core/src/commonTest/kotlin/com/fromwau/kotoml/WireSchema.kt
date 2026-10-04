package com.fromwau.kotoml

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementNames

/**
 * The names a type puts on the wire: a sealed type's subtypes by serial name with their fields, one per line, an
 * enum's entries, one per line, or a class's fields.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun KSerializer<*>.wireSchema(): String {
    val descriptor = descriptor
    return when (descriptor.kind) {
        PolymorphicKind.SEALED -> {
            val subtypes = descriptor.getElementDescriptor(1)
            (0 until subtypes.elementsCount)
                .map { subtypes.getElementDescriptor(it).fields() }
                .sorted()
                .joinToString("\n")
        }
        SerialKind.ENUM -> descriptor.elementNames.joinToString("\n")
        else -> descriptor.fields()
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.fields(): String =
    "${serialName.substringAfterLast('.')}(${elementNames.joinToString(", ")})"
