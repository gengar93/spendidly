package io.github.gengar93.spendidly.backend

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.json.JSONObject
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Days follow the phone's time zone. */
internal val spendingZone: ZoneId get() = ZoneId.systemDefault()
private val amountPattern = Regex("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?")
private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val expenseFields = setOf("amount", "spent_on", "category_id", "description")
private const val expenseColumns = "e.id, e.amount_paise, e.spent_on, e.category_id, c.name, e.description, e.created_at, e.deleted_at"
internal const val expenseJoin = "FROM expenses e LEFT JOIN categories c ON c.id = e.category_id"

data class Category(val id: String, val name: String, val description: String)

data class ExpenseItem(
    val id: String,
    val amountPaise: Long,
    val date: LocalDate,
    val categoryId: String?,
    val categoryName: String,
    val note: String?,
)

internal data class Expense(
    val id: String,
    val amountPaise: Long,
    val date: String,
    val categoryId: String?,
    val categoryName: String,
    val description: String?,
    val createdAt: String,
    val deletedAt: String?,
) {
    fun json() = json(
        "id" to id, "amount" to decimal(amountPaise), "currency" to currentCurrency.modelName, "spent_on" to date,
        "category_id" to categoryId, "category_name" to categoryName, "description" to description,
        "created_at" to createdAt, "deleted_at" to deletedAt,
    )
}

/** Local operations. Call from a worker thread. JSON arguments and results follow the web contracts. */
class ExpenseStore internal constructor(
    private val database: LocalDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val queries = ExpenseQueries(database, clock)

    fun getCategories(): List<Category> = database.read { categories() }

    fun createCategory(name: String, description: String = ""): JSONObject =
        saveCategory(null, name, description)

    fun updateCategory(id: String, name: String, description: String): JSONObject =
        saveCategory(id, name, description)

    private fun saveCategory(id: String?, name: String, description: String): JSONObject = result {
        val cleanName = name.trim()
        val cleanDescription = description.trim()
        if (cleanName.isEmpty() || cleanName.length > 100 || cleanDescription.length > 500) {
            fail("INVALID_CATEGORY", "Use a category name of 1–100 characters and a description of up to 500 characters.")
        }
        database.write {
            if (id != null && !hasCategory(id)) fail("CATEGORY_NOT_FOUND", "No category has that ID.")
            val duplicate = rows("SELECT id FROM categories WHERE name = ?", listOf(cleanName)) { it.getText(0) }.firstOrNull()
            if (duplicate != null && duplicate != id) fail("CATEGORY_EXISTS", "A category already has that name.")
            val categoryId = id ?: newId()
            if (id == null) {
                insertCategory(categoryId, cleanName, cleanDescription)
            } else {
                execute("UPDATE categories SET name = ?, description = ? WHERE id = ?", listOf(cleanName, cleanDescription, id))
            }
            success("category" to json("id" to categoryId, "name" to cleanName, "description" to cleanDescription))
        }
    }

    /** Saves the user's category order. Categories missing from [ids] keep their relative order after the listed ones. */
    fun reorderCategories(ids: List<String>) = database.write {
        val current = categories().map { it.id }
        val ordered = ids.filter { it in current }.distinct().let { it + (current - it.toSet()) }
        ordered.forEachIndexed { index, id -> execute("UPDATE categories SET position = ? WHERE id = ?", listOf(index, id)) }
    }

    /** All active expenses, newest first, for on-device screens. */
    fun snapshot(): List<ExpenseItem> = database.read {
        selectExpenses("WHERE e.deleted_at IS NULL ORDER BY e.spent_on DESC, e.created_at DESC, e.id DESC", emptyList())
            .map { ExpenseItem(it.id, it.amountPaise, LocalDate.parse(it.date), it.categoryId, it.categoryName, it.description) }
    }

    /** Undoes a soft deletion made from the app. */
    fun restoreExpense(id: String) = database.write {
        execute("UPDATE expenses SET deleted_at = NULL WHERE id = ?", listOf(id))
    }

    /** Removes a category; its expenses become Uncategorized. */
    fun deleteCategory(id: String) = database.write {
        execute("UPDATE expenses SET category_id = NULL WHERE category_id = ?", listOf(id))
        execute("DELETE FROM categories WHERE id = ?", listOf(id))
    }

    fun addExpense(args: JSONObject): JSONObject = insert(args, sourceText = "", requireDescription = false)

    internal fun addChatExpense(args: JSONObject, sourceText: String): JSONObject =
        insert(args, sourceText, requireDescription = true)

    private fun insert(args: JSONObject, sourceText: String, requireDescription: Boolean): JSONObject = result {
        args.only(expenseFields)
        val paise = entryAmount(args.value("amount"))
        val date = if (args.has("spent_on")) parseDate(args.value("spent_on")) else LocalDate.now(clock.withZone(spendingZone)).toString()
        val description = parseDescription(args.value("description"), requireDescription)
        database.write {
            val id = newId()
            val category = categoryId(args.value("category_id"))
            execute(
                "INSERT INTO expenses (id, amount_paise, spent_on, category_id, description, created_at, source_text) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(id, paise, date, category, description, timestamp(clock), sourceText),
            )
            success("expense" to requireExpense(id).json())
        }
    }

    fun updateExpense(args: JSONObject): JSONObject = result {
        args.only(setOf("expense_id", "changes"))
        val id = expenseId(args)
        val changes = args.optJSONObject("changes")
            ?: fail("INVALID_ARGUMENTS", "Provide a nonempty changes object.")
        if (changes.length() == 0) fail("INVALID_ARGUMENTS", "Provide a nonempty changes object.")
        changes.only(expenseFields)
        database.write {
            val expense = requireExpense(id)
            val paise = if (changes.has("amount")) entryAmount(changes.value("amount")) else expense.amountPaise
            val date = if (changes.has("spent_on")) parseDate(changes.value("spent_on")) else expense.date
            val category = if (changes.has("category_id")) categoryId(changes.value("category_id")) else expense.categoryId
            val description = if (changes.has("description")) {
                val value = changes.value("description")
                if (value == null) null else parseDescription(value, required = true)
            } else expense.description
            execute(
                "UPDATE expenses SET amount_paise = ?, spent_on = ?, category_id = ?, description = ? WHERE id = ?",
                listOf(paise, date, category, description, id),
            )
            success("expense" to requireExpense(id).json())
        }
    }

    /** Returns the current active expense as a preview for a user-initiated deletion. */
    fun previewDeletion(id: String): JSONObject = result {
        database.read { success("expense" to requireExpense(id).json()) }
    }

    /** The caller must have shown and received confirmation for this exact preview. */
    fun deleteExpense(preview: JSONObject): JSONObject = result {
        val id = preview.value("id") as? String ?: fail("INVALID_ARGUMENTS", "Provide the confirmed expense preview.")
        database.write {
            val current = requireExpense(id)
            val snapshot = current.json()
            if (snapshot.length() != preview.length() ||
                snapshot.keys().asSequence().any { !preview.has(it) || snapshot.value(it) != preview.value(it) }) {
                fail("EXPENSE_CHANGED", "The expense changed. Review it again before deleting.")
            }
            softDelete(current)
        }
    }

    internal fun deletionTarget(args: JSONObject): Expense {
        args.only(setOf("expense_id"))
        val id = expenseId(args)
        return database.read { requireExpense(id) }
    }

    internal fun deleteConfirmed(expense: Expense): JSONObject = result {
        database.write {
            val current = requireExpense(expense.id)
            if (current != expense) fail("EXPENSE_CHANGED", "The expense changed. Review it again before deleting.")
            softDelete(current)
        }
    }

    private fun SQLiteConnection.softDelete(expense: Expense): JSONObject {
        val deleted = expense.copy(deletedAt = timestamp(clock))
        execute("UPDATE expenses SET deleted_at = ? WHERE id = ?", listOf(deleted.deletedAt, deleted.id))
        return success("deleted_expense" to deleted.json())
    }

    fun queryExpenses(args: JSONObject = JSONObject()): JSONObject = queries.page(args)

    fun listExpenses(args: JSONObject = JSONObject()): JSONObject = queries.list(args)

    fun findExpenses(args: JSONObject = JSONObject()): JSONObject = queries.find(args)

    fun summarizeExpenses(args: JSONObject = JSONObject()): JSONObject = queries.summary(args)

    private fun SQLiteConnection.requireExpense(id: String): Expense =
        selectExpenses("WHERE e.id = ? AND e.deleted_at IS NULL", listOf(id)).firstOrNull()
            ?: fail("EXPENSE_NOT_FOUND", "No active expense has that ID.")

    private fun SQLiteConnection.categoryId(value: Any?): String? {
        if (value == null) return null
        if (value !is String || !hasCategory(value)) fail("INVALID_CATEGORY", "Use a current category ID or null for Uncategorized.")
        return value
    }
}

internal fun SQLiteConnection.categories(): List<Category> =
    rows("SELECT id, name, description FROM categories ORDER BY position, name") { Category(it.getText(0), it.getText(1), it.getText(2)) }

internal fun SQLiteConnection.hasCategory(id: String): Boolean =
    rows("SELECT 1 FROM categories WHERE id = ?", listOf(id)) { it.getLong(0) }.isNotEmpty()

internal fun SQLiteConnection.selectExpenses(suffix: String, values: List<Any?>): List<Expense> =
    rows("SELECT $expenseColumns $expenseJoin $suffix", values, ::readExpense)

private fun readExpense(row: SQLiteStatement) = Expense(
    row.getText(0), row.getLong(1), row.getText(2), row.textOrNull(3), row.textOrNull(4) ?: "Uncategorized",
    row.textOrNull(5), row.getText(6), row.textOrNull(7),
)

internal fun newId(): String = UUID.randomUUID().toString()

internal fun timestamp(clock: Clock): String = DateTimeMilliseconds.format(clock.instant().truncatedTo(ChronoUnit.MILLIS))

private val DateTimeMilliseconds = java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()

/** Amounts are integer hundredths of the chosen currency, which the code calls paise whatever the currency. */
internal fun decimal(paise: Long): String =
    "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

internal fun parseAmount(value: Any?, allowZero: Boolean): Long {
    if (value !is String || !amountPattern.matches(value)) {
        fail("INVALID_AMOUNT", "Use a ${currentCurrency.modelName} amount as a decimal string with up to two decimal places.")
    }
    val whole = value.substringBefore('.').toLong()
    val fraction = value.substringAfter('.', "").padEnd(2, '0').toLong()
    val paise = whole * 100 + fraction
    if (!allowZero && paise == 0L) fail("INVALID_AMOUNT", "Amount must be greater than zero.")
    return paise
}

/** A new or corrected expense amount; zero-decimal currencies take whole numbers only. */
private fun entryAmount(value: Any?): Long {
    val paise = parseAmount(value, allowZero = false)
    val currency = currentCurrency
    if (currency.decimals == 0 && paise % 100 != 0L) fail("INVALID_AMOUNT", "Use a whole number of ${currency.unit}, without decimals.")
    return paise
}

internal fun parseDate(value: Any?): String {
    if (value is String && datePattern.matches(value)) {
        try {
            return LocalDate.parse(value).toString()
        } catch (_: DateTimeParseException) {
            // Return the same error for syntax and calendar dates.
        }
    }
    fail("INVALID_DATE", "Use a real date in YYYY-MM-DD format.")
}

private fun parseDescription(value: Any?, required: Boolean): String? {
    if (value == null && !required) return null
    val description = (value as? String)?.trim()
    if (description == null || description.length > 500 || (required && description.isEmpty())) {
        fail("INVALID_DESCRIPTION", "Use a description of up to 500 characters${if (required) " and at least one nonblank character" else ""}.")
    }
    return description.ifEmpty { null }
}

private fun expenseId(args: JSONObject): String {
    val id = args.value("expense_id") as? String
    if (id.isNullOrBlank() || id.length > 200) fail("INVALID_ARGUMENTS", "Provide an exact expense_id.")
    return id
}
