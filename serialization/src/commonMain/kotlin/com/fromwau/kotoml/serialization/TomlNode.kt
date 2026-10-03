package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Value

/** A value as the encoder wrote it, before it becomes document items or is merged into a document. */
internal sealed interface TomlNode {
    /**
     * @param readsBack whether a value a document already holds reads as the same Kotlin value as this one,
     *   for a type whose spellings differ from the one written, such as a duration in ISO-8601; null to
     *   compare the values themselves.
     */
    class Scalar(
        val value: Value,
        val readsBack: ((Value) -> Boolean)? = null,
    ) : TomlNode

    class Sequence(val elements: List<TomlNode>) : TomlNode

    /**
     * A class, a map or a polymorphic value, as key/node [entries] in order.
     *
     * @param omitted the fields the class knows that hold null, so a document has no key for them.
     * @param ownsAllKeys whether the value accounts for every key of its table, as a map does.
     * @param discriminator the key naming a polymorphic value's subclass, which leads [entries].
     */
    class Struct(
        val entries: Map<String, TomlNode>,
        val omitted: Set<String>,
        val ownsAllKeys: Boolean,
        val discriminator: String? = null,
    ) : TomlNode
}
