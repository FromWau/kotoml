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

    /** @param leavesOutDefaults whether an element was left out because it holds its default. */
    class Sequence(
        val elements: List<TomlNode>,
        val leavesOutDefaults: Boolean,
    ) : TomlNode

    /**
     * A class, a map or a polymorphic value, as key/node [entries] in order.
     *
     * @param ownsAllKeys whether the value accounts for every key of its table, as a map does.
     * @param defaulted the keys left out because their field holds its default and defaults are not written.
     * @param discriminator the key naming a polymorphic value's subclass, which leads [entries].
     * @param defaultedSparse for a key a run without defaults left out, its value encoded without defaults, which
     *   tells the fields of that default that differ from their own class's defaults.
     */
    class Struct(
        val entries: Map<String, TomlNode>,
        val ownsAllKeys: Boolean,
        val defaulted: Set<String>,
        val discriminator: String? = null,
        val defaultedSparse: Map<String, TomlNode> = emptyMap(),
    ) : TomlNode
}
