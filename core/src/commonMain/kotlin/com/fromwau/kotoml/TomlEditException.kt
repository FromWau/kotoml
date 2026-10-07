package com.fromwau.kotoml

/**
 * Thrown by a store in the `[]=` form, such as `doc["port"] = 8080L`, that fails, carrying the [error] the same store
 * returns as a `Result` when called as `set` or `put`. A failed store changes nothing.
 *
 * ```kotlin
 * try {
 *     doc["t"]["v"] = commented
 * } catch (e: TomlEditException) {
 *     e.error // CommentsWouldBeLost(keyPath = [t, v], count = 1)
 * }
 * ```
 */
public class TomlEditException(
    public val error: TomlEditError,
) : RuntimeException(error.message)
