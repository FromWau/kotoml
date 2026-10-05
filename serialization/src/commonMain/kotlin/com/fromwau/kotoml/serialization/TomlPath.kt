package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Key
import com.fromwau.kotoml.TextPosition
import kotlinx.serialization.SerializationException

/**
 * A place in a document, built up while walking it and printed as `servers[1].port` only for an error. A key read
 * from text carries where it was written.
 */
internal class TomlPath private constructor(
    private val parent: TomlPath?,
    private val segment: String,
    private val keyPosition: TextPosition? = null,
) {
    /** How many keys and indices lead here from the root. */
    val depth: Int = (parent?.depth ?: -1) + 1

    /** Where the last key on this path that was read from text was written. */
    val position: TextPosition?
        get() = generateSequence(this) { it.parent }.firstNotNullOfOrNull { it.keyPosition }

    fun key(
        name: String,
        position: TextPosition? = null,
    ): TomlPath = TomlPath(this, Key(name).displaySpelling, position)

    fun index(index: Int): TomlPath = TomlPath(this, "[$index]")

    /** Every element of a list, or every value of a map, spelled `ports[]`. */
    fun element(): TomlPath = TomlPath(this, "[]")

    override fun toString(): String {
        val segments = generateSequence(this) { it.parent }
            .toList()
            .asReversed()
        return buildString {
            for (path in segments) {
                if (path.segment.isEmpty()) continue
                if (isNotEmpty() && !path.segment.startsWith('[')) append('.')
                append(path.segment)
            }
        }
    }

    companion object {
        val Root: TomlPath = TomlPath(null, "")
    }
}

// Thrown inside the Encoder and Decoder, whose contract has no other way out, and caught at Toml's public edge.
internal class DecodeFailure(val error: TomlDecodeError) : SerializationException(error.toString())

internal class EncodeFailure(val error: TomlEncodeError) : SerializationException(error.toString())
