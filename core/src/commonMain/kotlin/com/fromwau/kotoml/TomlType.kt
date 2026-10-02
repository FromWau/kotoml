package com.fromwau.kotoml

/** The kind of an [Item] or [Value]. */
public enum class TomlType {
    None,
    String,
    Integer,
    Float,
    Boolean,
    Datetime,
    Array,
    InlineTable,
    Table,
    ArrayOfTables,
}
