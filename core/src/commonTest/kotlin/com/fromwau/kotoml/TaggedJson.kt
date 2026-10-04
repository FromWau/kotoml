package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.writer.rustLowerExp

/**
 * toml-test's tagged JSON: a table, an array, or a scalar given as `{"type": ..., "value": ...}`. Equality
 * follows toml-test's own comparison rules, see [sameScalar].
 */
internal sealed interface Tagged {
    data class Scalar(val type: String, val value: String) : Tagged

    data class Table(val entries: Map<String, Tagged>) : Tagged

    data class Array(val items: List<Tagged>) : Tagged
}

/** Value equality under toml-test's rules, not data-class equality. */
internal fun Tagged.sameAs(other: Tagged): Boolean = when (this) {
    is Tagged.Scalar -> other is Tagged.Scalar && sameScalar(this, other)
    is Tagged.Table -> other is Tagged.Table &&
        entries.keys == other.entries.keys &&
        entries.all { (key, value) -> value.sameAs(other.entries.getValue(key)) }
    is Tagged.Array -> other is Tagged.Array &&
        items.size == other.items.size &&
        items.indices.all { items[it].sameAs(other.items[it]) }
}

/**
 * toml-test's `DecodedScalar::eq`: floats compare as numbers, `inf` equals `+inf` and `nan` equals `nan`; an
 * offset date-time compares as an instant; local date-times, dates and times compare by value, whatever
 * precision the fraction is written to.
 */
private fun sameScalar(left: Tagged.Scalar, right: Tagged.Scalar): Boolean {
    if (left.type != right.type) return false
    return when (left.type) {
        "float" -> when {
            left.value == "nan" && right.value == "nan" -> true
            else -> {
                val a = parseTomlFloat(left.value) ?: return false
                val b = parseTomlFloat(right.value) ?: return false
                a == b
            }
        }
        "datetime" -> instant(left.value) == instant(right.value)
        "datetime-local", "date-local", "time-local" -> local(left.value) == local(right.value)
        else -> left.value == right.value
    }
}

private fun normalizeDatetime(text: String): String = text
    .replace(' ', 'T')
    .replace('t', 'T')
    .replace('z', 'Z')

private fun parseDatetime(text: String): Datetime? = when (val parsed = Datetime.parse(normalizeDatetime(text))) {
    is Result.Success -> parsed.value
    is Result.Error -> null
}

/** Seconds and nanoseconds since the epoch, or null when the text is not an offset date-time. */
private fun instant(text: String): Pair<Long, Long>? {
    val datetime = parseDatetime(text) ?: return null
    val date = datetime.date ?: return null
    val time = datetime.time ?: return null
    val offsetMinutes = when (val offset = datetime.offset ?: return null) {
        Offset.Z, Offset.Unknown -> 0
        is Offset.Custom -> offset.minutes
    }
    val days = daysFromCivil(date.year.toLong(), date.month.toLong(), date.day.toLong())
    val seconds = days * 86_400 +
        time.hour.toLong() * 3_600 +
        time.minute.toLong() * 60 +
        (time.second ?: 0).toLong() -
        offsetMinutes * 60L
    return seconds to (time.nanosecond ?: 0).toLong()
}

/** A local date, time or date-time with missing seconds and fraction read as zero. */
private fun local(text: String): List<Long>? {
    val datetime = parseDatetime(text) ?: return null
    if (datetime.offset != null) return null
    val date = datetime.date
    val time = datetime.time
    return listOf(
        date?.year?.toLong() ?: -1,
        date?.month?.toLong() ?: -1,
        date?.day?.toLong() ?: -1,
        time?.hour?.toLong() ?: -1,
        time?.minute?.toLong() ?: -1,
        time?.let { (it.second ?: 0).toLong() } ?: -1,
        time?.let { (it.nanosecond ?: 0).toLong() } ?: -1,
    )
}

// Howard Hinnant's days_from_civil.
private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

/** The tagged JSON of a document, as upstream's `tests/decoder.rs` builds it. */
internal fun TomlDocument.toTagged(): Tagged = root.toTagged()

private fun StandardTable.toTagged(): Tagged =
    Tagged.Table(associate { (key, item) -> key to item.toTagged() })

private fun Item.toTagged(): Tagged {
    valueOrNull?.let { return it.toTagged() }
    standardTableOrNull?.let { return it.toTagged() }
    arrayOfTablesOrNull?.let { tables -> return Tagged.Array(tables.map { it.toTagged() }) }
    error("no empty items")
}

private fun Value.toTagged(): Tagged = when (this) {
    is Value.Integer -> Tagged.Scalar("integer", value.toString())
    is Value.String -> Tagged.Scalar("string", value)
    is Value.Float -> Tagged.Scalar("float", taggedFloat(value))
    is Value.Boolean -> Tagged.Scalar("bool", value.toString())
    is Value.Datetime -> when (val datetime = value) {
        is Datetime.OffsetDateTime -> Tagged.Scalar("datetime", datetime.copy(time = datetime.time.full()).toString())
        is Datetime.LocalDateTime -> {
            Tagged.Scalar("datetime-local", datetime.copy(time = datetime.time.full()).toString())
        }
        is Datetime.LocalDate -> Tagged.Scalar("date-local", datetime.toString())
        is Datetime.LocalTime -> Tagged.Scalar("time-local", datetime.copy(time = datetime.time.full()).toString())
    }
    is Value.Array -> Tagged.Array(array.map { it.toTagged() })
    is Value.InlineTable -> Tagged.Table(table.associate { (key, value) -> key to value.toTagged() })
}

private fun taggedFloat(value: Double): String = when {
    value.isNaN() -> "nan"
    value.isInfinite() -> if (value < 0) "-inf" else "inf"
    else -> rustLowerExp(value)
}

/** A document holding [tagged]'s values, as upstream's `tests/encoder.rs` builds it; null when it cannot. */
internal fun Tagged.toDocument(): TomlDocument? {
    if (this !is Tagged.Table) return null
    val table = StandardTable()
    for ((key, value) in entries) table[key] = Item(value.toValue() ?: return null)
    return TomlDocument(table)
}

private fun Tagged.toValue(): Value? = when (this) {
    is Tagged.Scalar -> when (type) {
        "string" -> Value.String(value)
        "integer" -> parseTomlLong(value, 10)?.let { Value.Integer(it) }
        "float" -> parseTomlFloat(value)?.let { Value.Float(it) }
        "bool" -> when (value) {
            "true" -> Value.Boolean(true)
            "false" -> Value.Boolean(false)
            else -> null
        }
        else -> when (val parsed = Datetime.parse(value)) {
            is Result.Success -> Value.Datetime(parsed.value)
            is Result.Error -> null
        }
    }
    is Tagged.Table -> {
        val table = InlineTable()
        for ((key, value) in entries) table[key] = value.toValue() ?: return null
        Value.InlineTable(table)
    }
    is Tagged.Array -> {
        val array = TomlArray()
        for (item in items) array.add(item.toValue() ?: return null)
        Value.Array(array)
    }
}

/** Parses toml-test's JSON, which only holds objects, arrays and strings. */
internal fun parseTaggedJson(text: String): Tagged {
    val parser = JsonParser(text)
    val value = parser.value()
    parser.skipWhitespace()
    check(parser.atEnd()) { "trailing JSON" }
    return value
}

private class JsonParser(private val text: String) {
    private var position = 0

    fun atEnd(): Boolean = position >= text.length

    fun skipWhitespace() {
        while (position < text.length && text[position] in " \t\r\n") position++
    }

    fun value(): Tagged {
        skipWhitespace()
        return when (text[position]) {
            '{' -> obj()
            '[' -> array()
            else -> error("unexpected JSON at $position")
        }
    }

    private fun obj(): Tagged {
        position++
        val entries = linkedMapOf<String, Any>()
        skipWhitespace()
        if (text[position] == '}') {
            position++
            return Tagged.Table(emptyMap())
        }
        while (true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            check(text[position] == ':')
            position++
            skipWhitespace()
            entries[key] = if (text[position] == '"') string() else value()
            skipWhitespace()
            if (text[position] == ',') {
                position++
                continue
            }
            check(text[position] == '}')
            position++
            break
        }
        val type = entries["type"]
        val value = entries["value"]
        if (entries.size == 2 && type is String && value is String) return Tagged.Scalar(type, value)
        return Tagged.Table(entries.mapValues { it.value as Tagged })
    }

    private fun array(): Tagged {
        position++
        val items = mutableListOf<Tagged>()
        skipWhitespace()
        if (text[position] == ']') {
            position++
            return Tagged.Array(items)
        }
        while (true) {
            items += value()
            skipWhitespace()
            if (text[position] == ',') {
                position++
                continue
            }
            check(text[position] == ']')
            position++
            break
        }
        return Tagged.Array(items)
    }

    private fun string(): String {
        check(text[position] == '"')
        position++
        val builder = StringBuilder()
        while (true) {
            val char = text[position++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> when (val escape = text[position++]) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    'u' -> {
                        builder.append(text.substring(position, position + 4).toInt(16).toChar())
                        position += 4
                    }
                    else -> builder.append(escape)
                }
                else -> builder.append(char)
            }
        }
    }
}

/** The time with its second and fraction written out, as toml-test compares them. */
private fun Time.full(): Time = copy(second = second ?: 0, nanosecond = nanosecond ?: 0)
