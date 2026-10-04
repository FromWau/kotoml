package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.getOrNull
import com.fromwau.kotoml.ArrayOfTables
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.Decor
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.Table
import com.fromwau.kotoml.TomlArray
import com.fromwau.kotoml.Value

/**
 * Which fields of an encoded value hold their default, told by the same value encoded without defaults. A
 * field at its default updates a key the document has but adds none, and what is added is written without
 * defaults, as `encodeToString` would write it.
 */
internal sealed interface Defaults {
    /** The format writes defaults, so no field counts as one. */
    data object Written : Defaults

    /**
     * The whole value is its field's default, so every field of a class in it holds its default too. A map has no
     * entries of its own to fall back on, so its keys are written in full.
     */
    data object All : Defaults

    /** [node] is the value encoded without defaults, which records the fields it left out at their default. */
    class Sparse(val node: TomlNode) : Defaults

    /** Whether [key] of [struct], the value encoded with defaults, holds its default. */
    fun isDefault(
        struct: TomlNode.Struct,
        key: String,
    ): Boolean = when (this) {
        Written -> false
        All -> !struct.ownsAllKeys
        is Sparse -> node is TomlNode.Struct && key in node.defaulted
    }

    /** The defaults of [key] in [full], the value encoded with defaults. */
    fun child(
        key: String,
        full: TomlNode.Struct,
    ): Defaults = when (this) {
        Written, All -> this
        is Sparse -> ((node as? TomlNode.Struct)?.entries?.get(key) ?: full.defaultedSparse[key])?.let(::Sparse) ?: All
    }

    fun element(index: Int): Defaults = when (this) {
        Written, All -> this
        // A list that left out elements no longer lines up with the full list, so its elements are written in full.
        is Sparse -> (node as? TomlNode.Sequence)
            ?.takeUnless { it.leavesOutDefaults }
            ?.elements
            ?.getOrNull(index)
            ?.let(::Sparse) ?: Written
    }

    /** What to write for [full]: the value without its defaults when the format leaves them out. */
    fun written(full: TomlNode): TomlNode = (this as? Sparse)?.node ?: full
}

/**
 * Writes an encoded value into an existing document, changing only what differs. A key that stays keeps its
 * spelling, its place and the text around its value; a value that reads the same as the one there is left
 * untouched.
 */
internal class DocumentMerger(private val writer: DocumentWriter) {

    fun mergeTable(
        table: Table,
        full: TomlNode.Struct,
        defaults: Defaults,
    ) {
        val subtypeChanged = full.discriminator?.let { key ->
            table.getItem(key)?.stringOrNull != (full.entries[key] as? TomlNode.Scalar)?.value?.stringOrNull
        } ?: false
        if (full.ownsAllKeys || subtypeChanged) table.removeKeysOutside(full.entries.keys)
        for ((key, child) in full.entries) {
            val existing = table.getItem(key)
            val childDefaults = defaults.child(key, full)
            when {
                existing != null -> mergeItem(table, key, existing, child, childDefaults)
                !defaults.isDefault(full, key) -> put(table, key, newItem(table, childDefaults.written(child)))
            }
        }
    }

    private fun mergeItem(
        parent: Table,
        key: String,
        existing: Item,
        full: TomlNode,
        defaults: Defaults,
    ) {
        val existingTable = existing.tableOrNull
        when {
            full is TomlNode.Struct && existingTable != null -> {
                mergeTable(existingTable, full, defaults)
                // An emptied dotted or implicit table prints nothing, which reads back as a missing key.
                if (existingTable.isEmpty() && existingTable.isHiddenWhenEmpty) {
                    put(parent, key, Item(writer.value(full)))
                }
            }
            existing.toInput()?.sameAs(full, defaults) == true -> {}
            full is TomlNode.Sequence && mergeList(existing, full, defaults) -> {}
            else -> put(parent, key, replacement(parent, existing, defaults.written(full)))
        }
    }

    /** Merges [full] into the list [existing] holds, element by element; false when [existing] holds no list. */
    private fun mergeList(
        existing: Item,
        full: TomlNode.Sequence,
        defaults: Defaults,
    ): Boolean {
        val tables = existing.arrayOfTablesOrNull
        val array = existing.valueOrNull?.arrayOrNull
        when {
            tables != null && full.isTableList() -> mergeArrayOfTables(tables, full, defaults)
            array != null -> mergeArray(array, full, defaults)
            else -> return false
        }
        return true
    }

    private fun mergeArrayOfTables(
        tables: ArrayOfTables,
        full: TomlNode.Sequence,
        defaults: Defaults,
    ) {
        val old = tables.toList()
        val oldInputs = old.map { it.toInput() }
        val plan = planList(
            oldCount = old.size,
            newCount = full.elements.size,
            isSame = { new, index -> oldInputs[index].sameAs(full.elements[new], defaults.element(new)) },
            similarity = { new, index -> oldInputs[index].sharedValues(full.elements[new], defaults.element(new)) },
        )
        for (index in plan.removed.asReversed()) tables.removeAt(index)
        val merged = plan.sources.mapIndexed { new, source ->
            val node = full.elements[new] as TomlNode.Struct
            val elementDefaults = defaults.element(new)
            when (source) {
                is ElementSource.Kept -> old[source.index]
                is ElementSource.Updated -> old[source.index].also { mergeTable(it, node, elementDefaults) }
                is ElementSource.Replacing -> writer.table(elementDefaults.writtenStruct(node)).apply {
                    decor = Decor(prefix = old[source.index].decor.prefix)
                    position = old[source.index].position
                }
                ElementSource.Inserted -> writer.table(elementDefaults.writtenStruct(node))
            }
        }
        positionTables(merged, plan)
        // A kept element may land in another's slot, and must not take that slot's header comments.
        for ((index, table) in merged.withIndex()) {
            if (index < tables.size) tables.replace(index, table, keepDecor = false) else tables.add(table)
        }
    }

    private fun mergeArray(
        array: TomlArray,
        full: TomlNode.Sequence,
        defaults: Defaults,
    ) {
        val old = array.toList()
        val oldInputs = old.map { it.toInput() }
        val plan = planList(
            oldCount = old.size,
            newCount = full.elements.size,
            isSame = { new, index -> oldInputs[index].sameAs(full.elements[new], defaults.element(new)) },
            similarity = if (full.isTableList()) {
                { new, index -> oldInputs[index].sharedValues(full.elements[new], defaults.element(new)) }
            } else {
                null
            },
        )
        val leadingPrefix = old.firstOrNull()?.decor?.prefix
        for (index in plan.removed.asReversed()) array.removeAt(index)
        val merged = plan.sources.mapIndexed { new, source ->
            val node = full.elements[new]
            val elementDefaults = defaults.element(new)
            when (source) {
                is ElementSource.Kept -> old[source.index]
                is ElementSource.Updated -> updatedElement(old[source.index], node, elementDefaults)
                is ElementSource.Replacing -> writer.value(elementDefaults.written(node)).apply {
                    decor = old[source.index].decor
                }
                ElementSource.Inserted -> writer.value(elementDefaults.written(node))
            }
        }
        for ((index, value) in merged.withIndex()) {
            if (index < array.size) array.replace(index, value, keepDecor = false) else array.add(value)
        }
        if (plan.movesElements) {
            array.carryLineEnds(
                merged.withIndex().mapNotNull { (new, value) -> plan.sources[new].oldIndex?.let { it to value } },
            )
        }
        val lead = merged.filterIndexed { new, _ -> plan.sources[new] != ElementSource.Inserted }.firstOrNull()
        val formerLead = merged.filterIndexed { new, _ -> plan.sources[new].oldIndex == 0 }.firstOrNull()
        if (lead != null) takeLeadingPlace(lead, formerLead, leadingPrefix)
        for (new in merged.indices.reversed()) {
            if (plan.sources[new] == ElementSource.Inserted) array.layOutInserted(new)
        }
    }

    /** [old] holding what [node] says, updated in place where it can be, or a new value in its place. */
    private fun updatedElement(
        old: Value,
        node: TomlNode,
        defaults: Defaults,
    ): Value {
        val table = old.inlineTableOrNull
        val array = old.arrayOrNull
        when {
            node is TomlNode.Struct && table != null -> mergeTable(table, node, defaults)
            node is TomlNode.Sequence && array != null -> mergeArray(array, node, defaults)
            else -> return writer.value(defaults.written(node)).apply { decor = old.decor }
        }
        return old
    }

    private fun newItem(
        parent: Table,
        node: TomlNode,
    ): Item = if (parent is InlineTable) Item(writer.value(node)) else writer.item(node)

    /** The item replacing [existing]: a value keeps the old value's decor. */
    private fun replacement(
        parent: Table,
        existing: Item,
        node: TomlNode,
    ): Item {
        val old = existing.valueOrNull ?: return newItem(parent, node)
        val value = writer.value(node)
        value.decor = old.decor
        return Item(value)
    }

    private fun put(
        table: Table,
        key: String,
        item: Item,
    ) {
        when (table) {
            is StandardTable -> table[key] = item
            is InlineTable -> item.valueOrNull?.let { table[key] = it }
        }
    }
}

/** Whether the table prints nothing while it holds no keys: a dotted key's part, or a table deeper headers name. */
private val Table.isHiddenWhenEmpty: Boolean
    get() = isDotted || (this is StandardTable && isImplicit)

/** Removes the keys outside [kept] in one pass, so the lines above each removed key stay where they print. */
private fun Table.removeKeysOutside(kept: Set<String>) {
    when (this) {
        is StandardTable -> removeAll { (key, _) -> key !in kept }
        is InlineTable -> removeAll { (key, _) -> key !in kept }
    }
}

/** What to write for the list element [full]: a class stays a class without the fields at their default. */
private fun Defaults.writtenStruct(full: TomlNode.Struct): TomlNode.Struct = written(full) as? TomlNode.Struct ?: full

/**
 * Positions the [merged] elements of an array of tables so they print in their new order: moved elements trade
 * positions, and an inserted one takes the position of the element after it, printing just before that one.
 */
private fun positionTables(
    merged: List<StandardTable>,
    plan: ListPlan,
) {
    val isInserted = plan.sources.map { it == ElementSource.Inserted }
    if (plan.movesElements) renumber(merged.filterIndexed { new, _ -> !isInserted[new] })
    var nextPosition: Long? = null
    for (new in merged.indices.reversed()) {
        if (isInserted[new]) merged[new].position = nextPosition else nextPosition = merged[new].position
    }
}

/**
 * Hands the positions [elements] hold among the document's tables out again in the order of [elements], each
 * element keeping the tables under it right after it.
 */
private fun renumber(elements: List<StandardTable>) {
    val blocks = elements.map { element -> element.headerTables().sortedBy { it.position } }
    val positions = blocks
        .flatten()
        .mapNotNull { it.position }
        .sorted()
        .iterator()
    for (table in blocks.flatten()) table.position = positions.next()
}

/** This table and the tables nested in it that print a header and hold a position. */
private fun StandardTable.headerTables(): List<StandardTable> {
    val tables = mutableListOf<StandardTable>()
    fun collect(table: StandardTable) {
        if (table.position != null) tables += table
        for ((_, item) in table.entries) {
            item.standardTableOrNull?.let(::collect)
            item.arrayOfTablesOrNull?.forEach(::collect)
        }
    }
    collect(this)
    return tables
}

/**
 * Prints the value inserted at [index] in the line layout of its neighbours: it takes over the text in front of the
 * element after it, which gets the separator that text ends with, so a comment ending the line before stays on it.
 */
private fun TomlArray.layOutInserted(index: Int) {
    val value = this[index] ?: return
    // An element appended at the end was laid out by TomlArray.add.
    val next = this[index + 1] ?: return
    val nextPrefix = next.decor.prefix
    value.decor = Decor(prefix = nextPrefix)
    next.decor = next.decor.copy(prefix = nextPrefix?.let(::elementSeparator))
}

/**
 * Gives [lead], now the first element, the [leadingPrefix] the first old element had there, and [formerLead], the
 * value standing for that old element, the prefix [lead] had, when neither breaks a line: on one line the first
 * element's prefix sets the spacing after `[`, which belongs to the place, not to the element. [formerLead] is null
 * when the first old element was removed.
 */
private fun takeLeadingPlace(
    lead: Value,
    formerLead: Value?,
    leadingPrefix: String?,
) {
    val leadPrefix = lead.decor.prefix
    if (lead === formerLead || leadingPrefix.breaksLine() || leadPrefix.breaksLine()) return
    lead.decor = lead.decor.copy(prefix = leadingPrefix)
    formerLead?.let { it.decor = it.decor.copy(prefix = leadPrefix) }
}

/**
 * Moves the end of each element's line along with the element, for an array whose elements each start a line: the
 * parser keeps the comment after an element's comma in front of the next element, or in [TomlArray.trailing] after
 * the last one. [placed] pairs each element that came from the document, in its new order, with its old index.
 */
private fun TomlArray.carryLineEnds(placed: List<Pair<Int, Value>>) {
    val prefixes = placed.associate { (old, value) -> old to value.decor.prefix.orEmpty() }
    val oldOrder = prefixes.keys.sorted()
    if (oldOrder.isEmpty() || prefixes.values.any { !it.breaksLine() } || !trailing.breaksLine()) return
    val following = oldOrder.drop(1).map(prefixes::getValue) + trailing
    val lineEnds = oldOrder.zip(following) { old, text -> old to text.substringBefore('\n') }.toMap()
    var lineEnd = prefixes.getValue(oldOrder.first()).substringBefore('\n')
    for ((old, value) in placed) {
        val prefix = prefixes.getValue(old)
        value.decor = value.decor.copy(prefix = lineEnd + prefix.substring(prefix.indexOf('\n')))
        lineEnd = lineEnds.getValue(old)
    }
    trailing = lineEnd + trailing.substring(trailing.indexOf('\n'))
}

private fun String?.breaksLine(): Boolean = this != null && '\n' in this

/** The text between two elements laid out as [prefix] lays out its element: a fresh line or a space. */
private fun elementSeparator(prefix: String): String =
    if ('\n' in prefix) "\n" + prefix.substringAfterLast('\n') else " "

/** How many of the keys [new] writes this table holds with the same value; 0 for anything but two tables. */
private fun TomlInput.sharedValues(
    new: TomlNode,
    defaults: Defaults,
): Int {
    if (this !is TomlInput.Table || new !is TomlNode.Struct) return 0
    return new.entries.count { (key, child) ->
        entries[key]?.toInput()?.sameAs(child, defaults.child(key, new)) == true
    }
}

/**
 * Whether merging [sparse], a value encoded without its defaults, into what the document holds here meets a key of a
 * field left out at its default, which the merge then needs the default value for. A list counts every pairing of a
 * new element with an old one, since the merge compares each with each.
 */
internal fun TomlInput.holdsKeyAtDefault(sparse: TomlNode): Boolean = when {
    this is TomlInput.Table && sparse is TomlNode.Struct ->
        sparse.defaulted.any { it in entries } ||
            sparse.entries.any { (key, child) -> entries[key]?.toInput()?.holdsKeyAtDefault(child) == true }
    this is TomlInput.Sequence && sparse is TomlNode.Sequence ->
        sparse.leavesOutDefaults || sparse.elements.any { new -> elements.any { old -> old.holdsKeyAtDefault(new) } }
    else -> false
}

/** Whether the document already holds what [full] would write, a missing key counting when it holds a default. */
private fun TomlInput.sameAs(
    full: TomlNode,
    defaults: Defaults,
): Boolean = when (full) {
    is TomlNode.Scalar -> this is TomlInput.Scalar && full.readsBack?.invoke(value) ?: value.sameAs(full.value)
    is TomlNode.Sequence -> this is TomlInput.Sequence &&
        elements.size == full.elements.size &&
        elements.indices.all { elements[it].sameAs(full.elements[it], defaults.element(it)) }
    is TomlNode.Struct -> this is TomlInput.Table &&
        full.entries.keys.containsAll(entries.keys) &&
        full.entries.all { (key, child) ->
            val item = entries[key]
            if (item == null) {
                defaults.isDefault(full, key)
            } else {
                item.toInput()?.sameAs(child, defaults.child(key, full)) == true
            }
        }
}

private fun Value.sameAs(other: Value): Boolean = when (this) {
    is Value.String -> value == other.stringOrNull
    is Value.Integer -> value == other.longOrNull
    is Value.Float -> other.doubleOrNull?.let { value.compareTo(it) == 0 } ?: false
    is Value.Boolean -> value == other.booleanOrNull
    // A date-time read into a string field writes back as its text, which still means this date-time.
    is Value.Datetime -> value == (other.datetimeOrNull ?: other.stringOrNull?.let { Datetime.parse(it).getOrNull() })
    else -> false
}
