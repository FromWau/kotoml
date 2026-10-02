package com.fromwau.kotoml

import com.fromwau.kotoml.visit.Visit
import com.fromwau.kotoml.visit.visitTableLikeKv
import com.fromwau.kotoml.visitmut.VisitMut
import com.fromwau.kotoml.visitmut.visitTableLikeKvMut
import com.fromwau.kotoml.visitmut.visitTableMut
import kotlin.test.Test
import kotlin.test.assertEquals

/** `toml_edit/examples/visit.rs`, which runs its tests with `cargo test`. */
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

    /** Collects the names of every dependency key. */
    private class DependencyNameVisitor : Visit {
        var state = VisitState.Root
        val names = mutableSetOf<String>()

        override fun visitTableLikeKv(key: String, node: Item) {
            if (state == VisitState.Dependencies) {
                names += key
            } else {
                // Only the keys right under [dependencies] are wanted, so this does not always recurse.
                val oldState = state
                state = state.descend(key)
                visitTableLikeKv(this, key, node)
                state = oldState
            }
        }
    }

    /** Normalizes every dependency table to `dep = { version = "1.0", ... }`, leaving other tables alone. */
    private class NormalizeDependencyTablesVisitor : VisitMut {
        var state = VisitState.Root

        override fun visitTableMut(node: Table) {
            visitTableMut(this, node)

            // Turning regular tables into inline ones can leave explicit parent tables hanging, so they become
            // implicit.
            if (state == VisitState.Target || state == VisitState.TargetWithSpec) {
                node.setImplicit(true)
            }
        }

        override fun visitTableLikeKvMut(key: KeyMut, node: Item) {
            val oldState = state
            state = state.descend(key.get())

            when (state) {
                VisitState.Target, VisitState.TargetWithSpec, VisitState.Dependencies -> {
                    // A top-level dependency row, or above it: inline tables become regular ones.
                    val inlineTable = node.asInlineTable()
                    if (inlineTable != null) {
                        val table = inlineTable.intoTable()
                        key.fmt()
                        node.assign(Item.from(table))
                    }
                }
                VisitState.SubDependencies -> {
                    // A single dependency: regular tables become inline ones.
                    val table = node.asTable()
                    if (table != null) {
                        val inlineTable = table.intoInlineTable()
                        key.fmt()
                        node.assign(Item.from(Value.InlineTable(inlineTable)))
                    }
                }
                else -> {}
            }

            visitTableLikeKvMut(this, key, node)

            state = oldState
        }

        override fun visitArrayMut(node: TomlArray) {
            // Arrays within dependencies go on one line.
            if (state == VisitState.Dependencies || state == VisitState.SubDependencies) {
                node.fmt()
            }
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

    private val visitMutOutput = """
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

    private fun visitExample(document: DocumentMut): Set<String> {
        val visitor = DependencyNameVisitor()
        visitor.visitDocument(document)
        return visitor.names
    }

    private fun visitMutExample(document: DocumentMut) {
        val visitor = NormalizeDependencyTablesVisitor()
        visitor.visitDocumentMut(document)
    }

    @Test
    fun visitCorrect() {
        val document = DocumentMut.parse(input).unwrap()

        val names = visitExample(document)
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
    fun visitMutCorrect() {
        val document = DocumentMut.parse(input).unwrap()

        visitMutExample(document)
        assertEquals(visitMutOutput, document.toString())
    }
}
