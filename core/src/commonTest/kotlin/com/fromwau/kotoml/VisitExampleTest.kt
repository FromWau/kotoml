package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** `toml_edit/examples/visit.rs`, which runs its tests with `cargo test`, as plain recursion over the tree. */
class VisitExampleTest {

    /** Where a visit stands among a `Cargo.toml`'s dependency tables. */
    private enum class VisitState {
        Root,
        Dependencies,
        SubDependencies,
        Target,
        TargetWithSpec,
        Other,
        ;

        fun descend(key: String): VisitState = when {
            (this == Root || this == TargetWithSpec) &&
                (key == "dependencies" || key == "build-dependencies" || key == "dev-dependencies") -> Dependencies
            this == Root && key == "target" -> Target
            this == Root || this == TargetWithSpec -> Other
            this == Target -> TargetWithSpec
            this == Dependencies -> SubDependencies
            this == SubDependencies -> SubDependencies
            else -> Other
        }
    }

    /** Collects the names of every dependency key under [table], whose entries are in [state]. */
    private fun collectDependencyNames(
        table: Table,
        state: VisitState,
        names: MutableSet<String>,
    ) {
        for ((key, item) in table.entries) {
            // Only the keys right under [dependencies] are wanted, so this does not always recurse.
            if (state == VisitState.Dependencies) {
                names += key
            } else {
                collectDependencyNames(item, state.descend(key), names)
            }
        }
    }

    private fun collectDependencyNames(
        item: Item,
        state: VisitState,
        names: MutableSet<String>,
    ) {
        item.valueOrNull?.let { collectDependencyNames(it, state, names) }
        item.standardTableOrNull?.let { collectDependencyNames(it, state, names) }
        item.arrayOfTablesOrNull?.forEach { collectDependencyNames(it, state, names) }
    }

    private fun collectDependencyNames(
        value: Value,
        state: VisitState,
        names: MutableSet<String>,
    ) {
        when (value) {
            is Value.Array -> value.value.forEach { collectDependencyNames(it, state, names) }
            is Value.InlineTable -> collectDependencyNames(value.value, state, names)
            is Value.String, is Value.Integer, is Value.Float, is Value.Boolean, is Value.Datetime -> {}
        }
    }

    /** Normalizes every dependency table to `dep = { version = "1.0", ... }`, leaving other tables alone. */
    private fun normalizeDependencyTables(
        table: StandardTable,
        state: VisitState,
    ) {
        normalizeDependencyEntries(table, state)

        // Turning regular tables into inline ones can leave explicit parent tables hanging, so they become
        // implicit.
        if (state == VisitState.Target || state == VisitState.TargetWithSpec) {
            table.setImplicit(true)
        }
    }

    private fun normalizeDependencyEntries(
        table: Table,
        state: VisitState,
    ) {
        for ((name, item) in table.entries) normalizeDependencyEntry(table.keyOrNull(name)!!, item, state.descend(name))
    }

    private fun normalizeDependencyEntry(
        key: Key,
        item: Item,
        state: VisitState,
    ) {
        when (state) {
            VisitState.Target, VisitState.TargetWithSpec, VisitState.Dependencies -> {
                // A top-level dependency row, or above it: inline tables become regular ones.
                val inlineTable = item.inlineTableOrNull
                if (inlineTable != null) {
                    val table = inlineTable.toStandardTable()
                    key.format()
                    item.assign(Item(table)).assertSuccess()
                }
            }
            VisitState.SubDependencies -> {
                // A single dependency: regular tables become inline ones.
                val table = item.standardTableOrNull
                if (table != null) {
                    val inlineTable = table.toInlineTable().assertSuccess()
                    key.format()
                    item.assign(Item(Value.InlineTable(inlineTable))).assertSuccess()
                }
            }
            else -> {}
        }

        item.valueOrNull?.let { normalizeDependencyValue(it, state) }
        item.standardTableOrNull?.let { normalizeDependencyTables(it, state) }
        item.arrayOfTablesOrNull?.forEach { normalizeDependencyTables(it, state) }
    }

    private fun normalizeDependencyValue(
        value: Value,
        state: VisitState,
    ) {
        when (value) {
            is Value.Array -> {
                // Arrays within dependencies go on one line.
                if (state == VisitState.Dependencies || state == VisitState.SubDependencies) {
                    val array = value.value
                    for (element in array) element.decor = Decor()
                    array.trailingComma = false
                    array.trailing = ""
                }
            }
            is Value.InlineTable -> normalizeDependencyEntries(value.value, state)
            is Value.String, is Value.Integer, is Value.Float, is Value.Boolean, is Value.Datetime -> {}
        }
    }

    private val input = """
[package]
name = "my-package"

[package.metadata.foo]
bar = 42

[dependencies]
atty = "0.2"
cargo-platform = { path = "crates/cargo-platform", version = "0.1.2" }

[dependencies.pretty_env_logger]
version = "0.4"
optional = true

[target.'cfg(windows)'.dependencies]
fwdansi = "1.1.0"

[target.'cfg(windows)'.dependencies.winapi]
version = "0.3"
features = [
"handleapi",
"jobapi",
]

[target.'cfg(unix)']
dev-dependencies = { miniz_oxide = "0.5" }

[dev-dependencies.cargo-test-macro]
path = "crates/cargo-test-macro"

[build-dependencies.flate2]
version = "0.4"
"""

    private val normalizedOutput = """
[package]
name = "my-package"

[package.metadata.foo]
bar = 42

[dependencies]
atty = "0.2"
cargo-platform = { path = "crates/cargo-platform", version = "0.1.2" }
pretty_env_logger = { version = "0.4", optional = true }

[target.'cfg(windows)'.dependencies]
fwdansi = "1.1.0"
winapi = { version = "0.3", features = ["handleapi", "jobapi"] }

[target.'cfg(unix)'.dev-dependencies]
miniz_oxide = "0.5"

[dev-dependencies]
cargo-test-macro = { path = "crates/cargo-test-macro" }

[build-dependencies]
flate2 = { version = "0.4" }
"""

    private fun dependencyNames(document: TomlDocument): Set<String> {
        val names = mutableSetOf<String>()
        collectDependencyNames(document.root, VisitState.Root, names)
        return names
    }

    private fun normalizeDependencies(document: TomlDocument) {
        normalizeDependencyTables(document.root, VisitState.Root)
    }

    @Test
    fun `a recursive walk collects every dependency name of a Cargo manifest`() {
        val document = TomlDocument.parse(input).assertSuccess()

        val names = dependencyNames(document)
        val expected = setOf(
            "atty",
            "cargo-platform",
            "pretty_env_logger",
            "fwdansi",
            "winapi",
            "miniz_oxide",
            "cargo-test-macro",
            "flate2",
        )
        assertEquals(expected, names)
    }

    @Test
    fun `a recursive edit makes dependency tables regular and each dependency inline`() {
        val document = TomlDocument.parse(input).assertSuccess()

        normalizeDependencies(document)
        assertEquals(normalizedOutput, document.toString())
    }
}
