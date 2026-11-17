package io.github.gengar93.spendidly.backend

import org.json.JSONArray
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportSummary(val expenses: Int, val categories: Int)

/** The bundle format [readImportFile] understands; `meta.json` holds it with the currency. */
internal const val bundleFormat = 1

/**
 * Writes a Spendidly bundle that [readImportFile] reads back: `expenses.csv` (active expenses, newest first),
 * `categories.json` (names and descriptions in the user's order) and `meta.json` ({"format", "currency"}).
 * Chats and keys are not included.
 */
internal fun exportBundle(database: LocalDatabase, output: OutputStream, currency: AppCurrency): ExportSummary {
    val (categories, csv) = database.read { categories() to expensesCsv() }
    val json = JSONArray(categories.map { json("name" to it.name, "description" to it.description) })
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("expenses.csv")); zip.write(csv.text.toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("categories.json")); zip.write(json.toString(2).toByteArray()); zip.closeEntry()
        val meta = json("format" to bundleFormat, "currency" to currency.code)
        zip.putNextEntry(ZipEntry("meta.json")); zip.write(meta.toString(2).toByteArray()); zip.closeEntry()
    }
    return ExportSummary(csv.rows, categories.size)
}

/** Writes only the expenses, in the same CSV the bundle holds, for spreadsheets. */
internal fun exportCsv(database: LocalDatabase, output: OutputStream): ExportSummary {
    val csv = database.read { expensesCsv() }
    output.write(csv.text.toByteArray())
    output.flush()
    return ExportSummary(csv.rows, 0)
}

private class Csv(val text: String, val rows: Int)

/** Date,Amount,Category,Note with ISO dates, plain decimal amounts ("450.00") in any currency and RFC 4180 quoting. Uncategorized is left blank. */
private fun androidx.sqlite.SQLiteConnection.expensesCsv(): Csv {
    val lines = rows(
        """SELECT e.spent_on, e.amount_paise, c.name, e.description $expenseJoin
           WHERE e.deleted_at IS NULL ORDER BY e.spent_on DESC, e.created_at DESC, e.id DESC""",
    ) { listOf(it.getText(0), decimal(it.getLong(1)), it.textOrNull(2).orEmpty(), it.textOrNull(3).orEmpty()) }
    val text = buildString {
        append("Date,Amount,Category,Note\r\n")
        for (fields in lines) append(fields.joinToString(",", transform = ::csvField)).append("\r\n")
    }
    return Csv(text, lines.size)
}

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
