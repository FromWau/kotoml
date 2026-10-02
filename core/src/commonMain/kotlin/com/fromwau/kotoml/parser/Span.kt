package com.fromwau.kotoml.parser

import kotlinx.serialization.Serializable

/**
 * A location within a [Source]: the bytes from [start] up to, not including, [end].
 *
 * Offsets count UTF-8 bytes of the input, not characters, so a span over `é` has a length of 2. A span is
 * not checked against any input when it is made; [Source.get] is where it meets one.
 */
@Serializable
public class Span(
    private val start: Int,
    private val end: Int,
) : Comparable<Span> {

    /** Whether the span covers no bytes. A span whose end lies before its start is empty too. */
    public fun isEmpty(): Boolean = end <= start

    /** The number of bytes covered. */
    public fun len(): Int = end - start

    /** The byte offset the span starts at. */
    public fun start(): Int = start

    /** The byte offset just past the span. */
    public fun end(): Int = end

    /** The empty span where this one starts. */
    public fun before(): Span = Span(start, start)

    /** The empty span where this one ends. */
    public fun after(): Span = Span(end, end)

    /** This span extended to the end of [after]. */
    public fun append(after: Span): Span = Span(start, after.end)

    /** This span moved [offset] bytes further into the input. */
    public operator fun plus(offset: Int): Span = Span(start + offset, end + offset)

    override fun compareTo(other: Span): Int {
        val byStart = start.compareTo(other.start)
        return if (byStart != 0) byStart else end.compareTo(other.end)
    }

    override fun equals(other: Any?): Boolean = other is Span && start == other.start && end == other.end

    override fun hashCode(): Int = 31 * start + end

    /** Writes the span as a Rust range, `start..end`. */
    override fun toString(): String = "$start..$end"

    public companion object {
        /** The empty span at the start of the input, upstream's `Span::default()`. */
        public val DEFAULT: Span = Span(0, 0)
    }
}
