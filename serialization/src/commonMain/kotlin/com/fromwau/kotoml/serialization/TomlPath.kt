package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Key
import kotlinx.serialization.SerializationException

/** A place in a document, built up while walking it and printed as `servers[1].port` only for an error. */
internal class TomlPath private constructor(
    private val parent: TomlPath?,
    private val segment: String,
) {
    fun key(name: String): TomlPath = TomlPath(this, Key(name).displaySpelling)

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
