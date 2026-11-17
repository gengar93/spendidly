package io.github.gengar93.spendidly.backend

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.util.Locale
import java.util.UUID

internal class LocalDatabase(private val context: Context) {
    private val lock = Any()
    private val connection by lazy { open() }

    private fun open(): SQLiteConnection {
        val file = context.getDatabasePath("spendidly.sqlite")
        file.parentFile?.mkdirs()
        val db = BundledSQLiteDriver().open(file.path)
        try {
            db.execute("PRAGMA foreign_keys = ON")
            db.execute("PRAGMA busy_timeout = 5000")
            db.execute("PRAGMA secure_delete = ON")
            db.transaction(write = true) {
                val version = rows("PRAGMA user_version") { it.getLong(0) }.single()
                check(version in 0..3) { "This Spendidly database needs a newer app." }
                // The schema uses IF NOT EXISTS, so running it also upgrades version 1 by adding chats.
                if (version < 2L) {
                    val schema = context.assets.open("spendidly-schema.sql").bufferedReader().use { it.readText() }
                    schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach { execute(it) }
                }
                // Version 3 adds the user's category order, starting from the alphabetical order shown before.
                if (version in 1L..2L) {
                    execute("ALTER TABLE categories ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                    execute("UPDATE categories SET position = (SELECT COUNT(*) FROM categories c WHERE c.name < categories.name)")
                }
                if (version < 3L) {
                    if (version == 0L) seedCategories()
                    execute("PRAGMA user_version = 3")
                }
            }
            return db
        } catch (error: Throwable) {
            db.close()
            throw error
        }
    }

    fun <T> read(block: SQLiteConnection.() -> T): T =
        synchronized(lock) { connection.transaction(write = false, block) }

    fun <T> write(block: SQLiteConnection.() -> T): T =
        synchronized(lock) { connection.transaction(write = true, block) }

    fun reset() = write {
        execute("DELETE FROM expenses")
        execute("DELETE FROM categories")
        execute("DELETE FROM provider_settings")
        execute("DELETE FROM chats")
    }
}

private fun SQLiteConnection.seedCategories() {
    val categories = listOf(
        "Food & Snacks" to "Prepared meals, snacks, and drinks, like a Swiggy lunch, pizza or chai.",
        "Groceries" to "Ingredients and pantry staples, like curd, ghee or fruit.",
        "Transport" to "Rides, public transit, and bus fares, like Uber, Rapido or redBus.",
        "Bills" to "Recurring utility and telecom bills, like a Jio recharge or broadband.",
        "Home" to "Housing, furnishings, and household services, like rent, a cook or laundry.",
        "Personal" to "Grooming, fitness, and personal errands, like a haircut or gym.",
        "Family" to "Purchases specifically for relatives.",
        "Other" to "Anything outside the named categories, like tips, donations or gifts.",
    )
    // The example descriptions are Indian; elsewhere the user writes their own.
    val india = Locale.getDefault().country == "IN"
    for ((name, description) in categories) insertCategory(UUID.randomUUID().toString(), name, if (india) description else "")
}

/** Adds a category after the last one in the user's order. */
internal fun SQLiteConnection.insertCategory(id: String, name: String, description: String) {
    execute(
        "INSERT INTO categories (id, name, description, position) VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM categories))",
        listOf(id, name, description),
    )
}

private fun <T> SQLiteConnection.transaction(write: Boolean, block: SQLiteConnection.() -> T): T {
    execute(if (write) "BEGIN IMMEDIATE" else "BEGIN")
    try {
        val result = block()
        execute("COMMIT")
        return result
    } catch (error: Throwable) {
        try {
            execute("ROLLBACK")
        } catch (rollback: Exception) {
            error.addSuppressed(rollback)
        }
        throw error
    }
}

internal fun SQLiteConnection.execute(sql: String, values: List<Any?> = emptyList()) {
    prepare(sql).use { statement ->
        statement.bind(values)
        statement.step()
    }
}

internal fun <T> SQLiteConnection.rows(
    sql: String,
    values: List<Any?> = emptyList(),
    map: (SQLiteStatement) -> T,
): List<T> = prepare(sql).use { statement ->
    statement.bind(values)
    buildList { while (statement.step()) add(map(statement)) }
}

private fun SQLiteStatement.bind(values: List<Any?>) {
    for ((position, value) in values.withIndex()) {
        val index = position + 1
        when (value) {
            null -> bindNull(index)
            is String -> bindText(index, value)
            is Int -> bindLong(index, value.toLong())
            is Long -> bindLong(index, value)
            is ByteArray -> bindBlob(index, value)
            else -> error("Unsupported SQL parameter type.")
        }
    }
}

internal fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
