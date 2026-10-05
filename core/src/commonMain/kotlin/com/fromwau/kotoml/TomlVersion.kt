package com.fromwau.kotoml

/**
 * The version of the TOML specification text is read against.
 *
 * [V1_1] is the default and what upstream `toml_edit` implements. [V1_0] additionally refuses what TOML 1.1 added:
 * newlines, comments and a trailing comma inside an inline table, the `\x` and `\e` escapes, and times written
 * without seconds. A document prints in the version it was parsed as, which [TomlDocument.version] changes.
 */
public enum class TomlVersion {
    /** TOML 1.0.0. */
    V1_0,

    /** TOML 1.1.0, the default. */
    V1_1,
}
