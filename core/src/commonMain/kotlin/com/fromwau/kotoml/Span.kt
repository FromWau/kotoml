package com.fromwau.kotoml

import kotlinx.serialization.Serializable

/**
 * A range of the parsed text: the bytes from [start] up to, not including, [end].
 *
 * Offsets count UTF-8 bytes of the input, not characters, so a span over `é` has a [length] of 2.
 */
@Serializable
public data class Span(
    val start: Int,
    val end: Int,
) : Comparable<Span> {

    /** The number of bytes covered; zero or less for a span that covers none. */
    public val length: Int get() = end - start

    override fun compareTo(other: Span): Int {
        val byStart = start.compareTo(other.start)
        return if (byStart != 0) byStart else end.compareTo(other.end)
    }

    /** Writes the span as a Rust range, `start..end`. */
    override fun toString(): String = "$start..$end"
}

/** The empty span at the start of the input, upstream's `Span::default()`. */
internal val Span.Companion.EMPTY: Span get() = Span(0, 0)

/** The empty span where this one starts. */
internal fun Span.before(): Span = Span(start, start)

/** The empty span where this one ends. */
internal fun Span.after(): Span = Span(end, end)

/** This span extended to the end of [after]. */
internal fun Span.append(after: Span): Span = Span(start, after.end)

/** This span moved [offset] bytes further into the input. */
internal operator fun Span.plus(offset: Int): Span = Span(start + offset, end + offset)
