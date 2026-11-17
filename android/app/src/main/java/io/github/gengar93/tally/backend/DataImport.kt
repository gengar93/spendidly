package io.github.gengar93.tally.backend

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.Clock
import java.time.LocalDate
import java.util.zip.ZipInputStream

data class ImportSummary(val expensesAdded: Int, val expensesSkipped: Int, val categoriesAdded: Int, val categoriesUpdated: Int)

/**
 * A parsed import, not yet applied. [currency] is the code from a backup's `meta.json`; null for older
 * backups and plain CSV files, which carry no currency.
 */
class ImportFile internal constructor(
    internal val categories: List<Pair<String, String>>,
    internal val expenses: List<ImportRow>,
    val currency: String?,
)

/**
 * Reads a Tally bundle (a zip holding `categories.json` ([{"name", "description"}]) and/or `expenses.csv`
 * (Date,Amount,Category,Note), plus `meta.json` in newer backups) or the plain expenses CSV the app exports.
 */
internal fun readImportFile(input: InputStream): ImportFile {
    val bytes = input.readBytes()
    // Zip files start with "PK"; anything else is read as the CSV export.
    if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
        val expenses = parseExpenses(bytes.decodeToString())
        if (expenses.isEmpty()) fail("INVALID_IMPORT", "This file has no expenses to import.")
        return ImportFile(emptyList(), expenses, null)
    }
    var categoriesJson: String? = null
    var expensesCsv: String? = null
    var metaJson: String? = null
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.substringAfterLast('/').lowercase()
            if (entry.isDirectory || name.startsWith(".")) continue
            when {
                name == "categories.json" -> categoriesJson = zip.readBytes().decodeToString()
                name == "meta.json" -> metaJson = zip.readBytes().decodeToString()
                name.endsWith(".csv") -> expensesCsv = zip.readBytes().decodeToString()
            }
        }
    }
    if (categoriesJson == null && expensesCsv == null) {
        fail("INVALID_IMPORT", "This file has no categories.json or expenses.csv inside.")
    }
    val meta = metaJson?.let { runCatching { JSONObject(it) }.getOrNull() ?: fail("INVALID_IMPORT", "meta.json is not a JSON object.") }
    if ((meta?.optInt("format", bundleFormat) ?: bundleFormat) > bundleFormat) fail("INVALID_IMPORT", "This backup needs a newer version of Tally.")
    return ImportFile(
        categoriesJson?.let(::parseCategories).orEmpty(),
        expensesCsv?.let(::parseExpenses).orEmpty(),
        meta?.optString("currency")?.trim()?.ifEmpty { null },
    )
}

/** Reads and applies an import in one go, for bundles the app builds itself (demo data). */
internal fun importBundle(database: LocalDatabase, input: InputStream, clock: Clock = Clock.systemUTC()): ImportSummary =
    importBundle(database, readImportFile(input), clock)

/**
 * Categories are matched by name; descriptions in the file replace existing ones, and names only in the
 * expenses get empty descriptions. Expenses identical to an existing one are skipped, so re-importing is safe.
 * Amounts are taken as they are, whatever currency the file came from.
 */
internal fun importBundle(database: LocalDatabase, file: ImportFile, clock: Clock = Clock.systemUTC()): ImportSummary {
    val categories = file.categories
    val expenses = file.expenses

    return database.write {
        val ids = categories().associate { it.name.lowercase() to it.id }.toMutableMap()
        var added = 0
        var updated = 0
        for ((name, description) in categories) {
            val existing = ids[name.lowercase()]
            if (existing == null) {
                val id = newId()
                insertCategory(id, name, description)
                ids[name.lowercase()] = id
                added++
            } else if (description.isNotEmpty()) {
                execute("UPDATE categories SET description = ? WHERE id = ?", listOf(description, existing))
                updated++
            }
        }
        val seen = rows(
            "SELECT spent_on, amount_paise, COALESCE(category_id, ''), COALESCE(description, '') FROM expenses WHERE deleted_at IS NULL",
        ) { "${it.getText(0)}|${it.getLong(1)}|${it.getText(2)}|${it.getText(3)}" }.groupingBy { it }.eachCount().toMutableMap()
        var inserted = 0
        var skipped = 0
        for (row in expenses) {
            val categoryId = row.category?.let { name ->
                ids.getOrPut(name.lowercase()) {
                    newId().also {
                        insertCategory(it, name, "")
                        added++
                    }
                }
            }
            val key = "${row.date}|${row.paise}|${categoryId.orEmpty()}|${row.note.orEmpty()}"
            val remaining = seen[key] ?: 0
            if (remaining > 0) {
                seen[key] = remaining - 1
                skipped++
                continue
            }
            execute(
                "INSERT INTO expenses (id, amount_paise, spent_on, category_id, description, created_at, source_text) VALUES (?, ?, ?, ?, ?, ?, 'import')",
                listOf(newId(), row.paise, row.date, categoryId, row.note, timestamp(clock)),
            )
            inserted++
        }
        ImportSummary(inserted, skipped, added, updated)
    }
}

internal data class ImportRow(val date: String, val paise: Long, val category: String?, val note: String?)

private fun parseCategories(text: String): List<Pair<String, String>> {
    val array = try { JSONArray(text) } catch (_: Exception) { fail("INVALID_IMPORT", "categories.json is not a JSON list.") }
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = item.optString("name").trim().take(100)
        if (name.isEmpty()) null else name to item.optString("description").trim().take(500)
    }.distinctBy { it.first.lowercase() }
}

private fun parseExpenses(text: String): List<ImportRow> {
    val records = csvRecords(text.removePrefix("﻿"))
    if (records.isEmpty()) return emptyList()
    val header = records.first().map { it.trim().lowercase() }
    fun column(vararg names: String) = header.indexOfFirst { it in names }
    val date = column("date", "spent_on")
    val amount = column("amount")
    val category = column("category")
    val note = column("note", "description")
    if (date < 0 || amount < 0) fail("INVALID_IMPORT", "The expenses CSV needs Date and Amount columns.")
    return records.drop(1).mapIndexedNotNull { index, fields ->
        if (fields.all { it.isBlank() }) return@mapIndexedNotNull null
        val line = index + 2
        val day = fields.getOrNull(date)?.trim().orEmpty()
        try {
            LocalDate.parse(day)
        } catch (_: Exception) {
            fail("INVALID_IMPORT", "Line $line has an invalid date: \"$day\". Use YYYY-MM-DD.")
        }
        val paise = try {
            parseAmount(fields.getOrNull(amount)?.trim()?.replace(",", ""), allowZero = false)
        } catch (_: BackendProblem) {
            fail("INVALID_IMPORT", "Line $line has an invalid amount.")
        }
        ImportRow(
            day, paise,
            fields.getOrNull(category)?.trim()?.take(100)?.ifEmpty { null },
            fields.getOrNull(note)?.trim()?.take(500)?.ifEmpty { null },
        )
    }
}

/** RFC 4180 records: quoted fields, doubled quotes, and newlines inside quotes. */
private fun csvRecords(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c == '"' && text.getOrNull(i + 1) == '"') { field.append('"'); i++ }
            else if (c == '"') quoted = false
            else field.append(c)
        } else when (c) {
            '"' -> quoted = true
            ',' -> { fields.add(field.toString()); field.clear() }
            '\r' -> {}
            '\n' -> { fields.add(field.toString()); field.clear(); records.add(fields); fields = mutableListOf() }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || fields.isNotEmpty()) { fields.add(field.toString()); records.add(fields) }
    return records
}

