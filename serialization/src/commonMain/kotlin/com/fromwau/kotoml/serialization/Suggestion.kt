package com.fromwau.kotoml.serialization

/**
 * The entry of [names] [written] most likely meant, ignoring case: one that differs only in case, else the only one
 * it begins, else the nearest within max(2, a third of the name's length) edits that is not a complete rewrite.
 * Null when none is that close; [written] itself is never suggested.
 */
internal fun closestName(
    written: String,
    names: List<String>,
): String? {
    val others = names.filter { it != written }
    others.firstOrNull { it.equals(written, ignoreCase = true) }?.let { return it }
    val needle = written.lowercase()
    others.filter { it.lowercase().startsWith(needle) }.distinct().singleOrNull()?.let { return it }
    return others
        .map { it to editDistance(needle, it.lowercase()) }
        .filter { (name, distance) ->
            // A distance as long as both words means they share nothing, however short they are.
            distance <= maxOf(2, name.length / 3) && distance < maxOf(needle.length, name.length)
        }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

/** How many single-character insertions, deletions or substitutions turn [from] into [to]. */
private fun editDistance(
    from: String,
    to: String,
): Int {
    var previous = IntArray(to.length + 1) { it }
    for (i in 1..from.length) {
        val current = IntArray(to.length + 1)
        current[0] = i
        for (j in 1..to.length) {
            val substitution = previous[j - 1] + if (from[i - 1] == to[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        previous = current
    }
    return previous[to.length]
}
