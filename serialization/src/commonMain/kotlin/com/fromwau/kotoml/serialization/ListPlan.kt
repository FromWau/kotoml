package com.fromwau.kotoml.serialization

import kotlin.math.abs

/** Where an element of a list being written comes from in the list the document holds. */
internal sealed interface ElementSource {
    /** The old element at [index] already holds the new value and stays as written. */
    class Kept(val index: Int) : ElementSource

    /** The old element at [index] shares values with the new one and is updated in place. */
    class Updated(val index: Int) : ElementSource

    /** A new element written at the place of the removed old element at [index]. */
    class Replacing(val index: Int) : ElementSource

    /** A new element with no old one at its place. */
    data object Inserted : ElementSource
}

/**
 * How a list being written maps onto the list a document holds: the source of each new element, in the new
 * list's order, and the old elements no new one keeps, updates or replaces, in ascending order.
 */
internal class ListPlan(
    val sources: List<ElementSource>,
    val removed: List<Int>,
) {
    /** Whether the old elements that stay come in another order than the document had them. */
    val movesElements: Boolean
        get() = sources
            .mapNotNull { it.oldIndex }
            .zipWithNext()
            .any { (before, after) -> before > after }
}

/** The old element this source takes its place, text or values from, or null for an inserted one. */
internal val ElementSource.oldIndex: Int?
    get() = when (this) {
        is ElementSource.Kept -> index
        is ElementSource.Updated -> index
        is ElementSource.Replacing -> index
        ElementSource.Inserted -> null
    }

/**
 * Plans writing [newCount] elements over a document's [oldCount].
 *
 * Each new element first keeps the first unused old element [isSame] finds equal. The others are updated from
 * the unused old element [similarity] scores highest, ties going to the nearest index and a score of 0 to none,
 * or, when [similarity] is null, from the unused old elements in order. A new element still without one takes
 * the place of an unused old element that follows the same kept element, and the old elements left are removed.
 */
internal fun planList(
    oldCount: Int,
    newCount: Int,
    isSame: (new: Int, old: Int) -> Boolean,
    similarity: ((new: Int, old: Int) -> Int)?,
): ListPlan {
    val sources = arrayOfNulls<ElementSource>(newCount)
    val used = BooleanArray(oldCount)
    fun take(
        new: Int,
        source: ElementSource,
    ) {
        sources[new] = source
        source.oldIndex?.let { used[it] = true }
    }

    for (new in 0 until newCount) {
        val old = (0 until oldCount).firstOrNull { !used[it] && isSame(new, it) } ?: continue
        take(new, ElementSource.Kept(old))
    }
    val unmatched = (0 until newCount).filter { sources[it] == null }
    if (similarity == null) {
        val unused = (0 until oldCount).filter { !used[it] }
        for ((new, old) in unmatched.zip(unused)) take(new, ElementSource.Updated(old))
    } else {
        for (new in unmatched) {
            val closest = (0 until oldCount)
                .filter { !used[it] }
                .map { old -> old to similarity(new, old) }
                .filter { (_, shared) -> shared > 0 }
                .maxWithOrNull(compareBy({ (_, shared) -> shared }, { (old, _) -> -abs(old - new) }))
            closest?.let { (old, _) -> take(new, ElementSource.Updated(old)) }
        }
    }

    val removedAfter = (0 until oldCount)
        .filter { !used[it] }
        .groupBy { old -> (old - 1 downTo 0).firstOrNull { used[it] } ?: -1 }
    var keptBefore = -1
    val insertedAfter = mutableMapOf<Int, MutableList<Int>>()
    for (new in 0 until newCount) {
        val old = sources[new]?.oldIndex
        if (old != null) keptBefore = old else insertedAfter.getOrPut(keptBefore) { mutableListOf() } += new
    }
    for ((kept, inserted) in insertedAfter) {
        val removed = removedAfter[kept].orEmpty()
        for ((new, old) in inserted.zip(removed)) take(new, ElementSource.Replacing(old))
    }

    return ListPlan(
        sources = sources.map { it ?: ElementSource.Inserted },
        removed = (0 until oldCount).filter { !used[it] },
    )
}
