package io.github.gengar93.spendidly.backend

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock

private val filterKeys = setOf("start_date", "end_date", "category_ids", "min_amount", "max_amount", "description_search")
private val sortKeys = setOf("sort_by", "sort_order")
private val dimensions = listOf("month", "category")
private const val month = "substr(e.spent_on, 1, 7)"
private const val categoryName = "COALESCE(c.name, 'Uncategorized')"
private val metricExpressions = mapOf(
    "sum" to "COALESCE(SUM(e.amount_paise), 0)",
    "count" to "COUNT(*)",
    "avg" to "SUM(e.amount_paise) / COUNT(*) + (SUM(e.amount_paise) % COUNT(*) >= (COUNT(*) + 1) / 2)",
    "min" to "MIN(e.amount_paise)",
    "max" to "MAX(e.amount_paise)",
)

private data class Sort(val by: String, val order: String) {
    val direction: String get() = if (order == "asc") "ASC" else "DESC"
    fun json() = json("by" to by, "order" to order)
}

private data class Query(
    val where: String,
    val bindings: List<Any?>,
    val filters: JSONObject,
    val groups: List<String>,
    val sort: Sort,
    val page: Int,
    val pageSize: Int,
)

private enum class QueryMode { LIST, FIND, PAGE, SUMMARY }

internal class ExpenseQueries(private val database: LocalDatabase, private val clock: Clock) {
    fun list(args: JSONObject): JSONObject = result {
        database.read {
            val query = prepare(args, QueryMode.LIST)
            success(
                "table_id" to newId(), "initial_filters" to query.filters, "initial_sort" to query.sort.json(),
                "initial_group_by" to JSONArray(query.groups), "initial_matching_count" to count(query),
                "created_at" to timestamp(clock), "visibility" to "initial_metadata_only",
                "notice" to "The widget can change independently. You can see only its initial metadata. Ask for details for row-specific questions.",
            )
        }
    }

    fun find(args: JSONObject): JSONObject = result {
        database.read {
            val query = prepare(args, QueryMode.FIND)
            val count = count(query)
            val guidance = when (count) {
                0L -> "No records matched these exact filters. Try a distinctive keyword or broaden the filters using the user's clues."
                1L -> "Use the exact ID only if the result matches the user's intended expense."
                else -> "Refine using the user's clues or ask them to distinguish candidates. Never choose a row solely because it is first."
            }
            success(
                "matches" to JSONArray(expensePage(query.copy(pageSize = 5), 1)),
                "matching_count" to count, "truncated" to (count > 5), "filters" to query.filters,
                "sort" to query.sort.json(), "guidance" to guidance,
            )
        }
    }

    fun page(args: JSONObject): JSONObject = result {
        database.read {
            val query = prepare(args, QueryMode.PAGE)
            val count = count(query)
            val response = success(
                "matching_count" to count, "group_by" to JSONArray(query.groups), "page_size" to query.pageSize,
                "filters" to query.filters, "sort" to query.sort.json(), "fetched_at" to timestamp(clock),
                "categories" to JSONArray(categories().map { json("id" to it.id, "name" to it.name) }),
            )
            if (query.groups.isEmpty()) {
                val pages = pages(count, query.pageSize)
                val page = minOf(query.page.toLong(), pages)
                response.put("page", page).put("page_count", pages).put("expenses", JSONArray(expensePage(query, page)))
            } else {
                groupedPage(query, response)
            }
            response
        }
    }

    fun summary(args: JSONObject): JSONObject = result {
        database.read {
            val query = prepare(args, QueryMode.SUMMARY)
            val metrics = parseOptions(args, "metrics", metricExpressions.keys, listOf("sum", "count"), "INVALID_METRICS")
            if (metrics.isEmpty()) fail("INVALID_METRICS", "Choose one or more distinct spending metrics.")
            if (args.has("limit") && args.value("limit") == null) fail("INVALID_LIMIT", "Use a group limit from 1 to 100.")
            val limit = args.integer("limit", 24, 1..100, "INVALID_LIMIT")
            val select = metrics.joinToString(", ") { "${metricExpressions.getValue(it)} AS \"$it\"" }
            val overall = rows("SELECT $select FROM expenses e ${query.where}", query.bindings) { metricValues(it, 0, metrics) }.single()
            val response = success(
                "currency" to currentCurrency.modelName, "filters" to query.filters, "group_by" to JSONArray(query.groups),
                "overall" to overall, "groups" to JSONArray(), "group_count" to 0, "truncated" to false,
            )
            if (query.groups.isEmpty()) {
                if (args.has("sort_by") || args.has("sort_order")) fail("INVALID_SORT", "Sorting is available for grouped summaries.")
                return@read response
            }
            val defaultSort = if ("month" in query.groups) "month" else if ("sum" in metrics) "sum" else metrics.first()
            val by = summarySortValue(args, "sort_by", defaultSort)
            val order = summarySortValue(args, "sort_order", if (by in query.groups) "asc" else "desc")
            if (by !in query.groups && by !in metrics || order !in setOf("asc", "desc")) {
                fail("INVALID_SORT", "Sort by a selected grouping or requested metric, in asc or desc order.")
            }
            val sort = Sort(by, order)
            val selectDimensions = query.groups.joinToString(", ") {
                if (it == "month") "$month AS month" else "e.category_id, $categoryName AS category_name"
            }
            val groupKeys = query.groups.joinToString(", ") { if (it == "month") month else "e.category_id" }
            val ties = query.groups.joinToString(", ") { if (it == "month") "month ASC" else "category_name COLLATE NOCASE ASC, e.category_id ASC" }
            val column = if (by == "category") "category_name COLLATE NOCASE" else "\"$by\""
            val groups = rows(
                """SELECT $selectDimensions, $select, COUNT(*) OVER () AS group_count
                   $expenseJoin ${query.where} GROUP BY $groupKeys
                   ORDER BY $column ${sort.direction}, $ties LIMIT ?""",
                query.bindings + limit,
            ) { row ->
                val hasMonth = "month" in query.groups
                val offset = (if (hasMonth) 1 else 0) + (if ("category" in query.groups) 2 else 0)
                val group = metricValues(row, offset, metrics)
                if (hasMonth) group.put("month", row.getText(0))
                if ("category" in query.groups) {
                    val categoryIndex = if (hasMonth) 1 else 0
                    group.put("category_id", row.textOrNull(categoryIndex) ?: JSONObject.NULL)
                    group.put("category_name", row.getText(categoryIndex + 1))
                }
                group to row.getLong(offset + metrics.size)
            }
            val count = groups.firstOrNull()?.second ?: 0
            response.put("sort", sort.json()).put("groups", JSONArray(groups.map { it.first }))
                .put("group_count", count).put("truncated", groups.size.toLong() < count)
        }
    }

    private fun SQLiteConnection.groupedPage(query: Query, response: JSONObject) {
        val byMonth = query.groups.first() == "month"
        val key = if (byMonth) month else "e.category_id"
        val fields = if (byMonth) "$month AS month" else "e.category_id, $categoryName AS category_name"
        val totals = "SUM(e.amount_paise) AS total_paise, COUNT(*) AS expense_count"
        val count = rows(
            "SELECT COUNT(*) FROM (SELECT 1 FROM expenses e ${query.where} GROUP BY $key)",
            query.bindings,
        ) { it.getLong(0) }.single()
        val pages = pages(count, query.pageSize)
        val page = minOf(query.page.toLong(), pages)
        val label = if (byMonth) "month" else "category_name COLLATE NOCASE"
        val column = if (query.sort.by == "total") "total_paise" else label
        val tie = if (byMonth) "month DESC" else "category_name COLLATE NOCASE ASC, category_id ASC"
        val groups = rows(
            """SELECT $fields, $totals $expenseJoin ${query.where}
               GROUP BY $key ORDER BY $column ${query.sort.direction}, $tie LIMIT ? OFFSET ?""",
            query.bindings + listOf(query.pageSize, (page - 1) * query.pageSize),
        ) { row ->
            if (byMonth) totalGroup(row, 1).put("month", row.getText(0)) else categoryTotal(row, 0)
        }
        if (query.groups.size == 2 && groups.isNotEmpty()) {
            val children = groups.associate { group ->
                group.getString("month") to JSONArray().also { group.put("categories", it) }
            }
            rows(
                """SELECT $month AS month, e.category_id, $categoryName AS category_name, $totals
                   $expenseJoin ${query.where} AND $month IN (${placeholders(children.size)})
                   GROUP BY month, e.category_id
                   ORDER BY total_paise DESC, category_name COLLATE NOCASE, e.category_id""",
                query.bindings + children.keys,
            ) { row -> children.getValue(row.getText(0)).put(categoryTotal(row, 1)) }
        }
        response.put("groups", JSONArray(groups)).put("group_count", count).put("page", page).put("page_count", pages)
    }

    private fun SQLiteConnection.expensePage(query: Query, page: Long): List<JSONObject> {
        val sortColumn = if (query.sort.by == "amount") "e.amount_paise" else "e.spent_on"
        return selectExpenses(
            "${query.where} ORDER BY $sortColumn ${query.sort.direction}, e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
            query.bindings + listOf(query.pageSize, (page - 1) * query.pageSize),
        ).map(Expense::json)
    }

    private fun SQLiteConnection.count(query: Query): Long =
        rows("SELECT COUNT(*) FROM expenses e ${query.where}", query.bindings) { it.getLong(0) }.single()

    private fun SQLiteConnection.prepare(args: JSONObject, mode: QueryMode): Query {
        val allowed = when (mode) {
            QueryMode.LIST -> sortKeys + "group_by"
            QueryMode.FIND -> sortKeys
            QueryMode.PAGE -> sortKeys + setOf("group_by", "page", "page_size")
            QueryMode.SUMMARY -> sortKeys + setOf("group_by", "metrics", "limit")
        }
        args.only(filterKeys + allowed)
        val start = args.value("start_date")?.let(::parseDate)
        val end = args.value("end_date")?.let(::parseDate)
        if (start != null && end != null && start > end) fail("INVALID_RANGE", "End date must be on or after start date.")
        val min = args.value("min_amount")?.let { parseAmount(it, allowZero = true) }
        val max = args.value("max_amount")?.let { parseAmount(it, allowZero = true) }
        if (min != null && max != null && min > max) fail("INVALID_RANGE", "Maximum amount must be at least the minimum amount.")
        val ids = args.value("category_ids")?.let { raw ->
            val items = (raw as? JSONArray)?.values()
            if (items.isNullOrEmpty() || items.size > 100 || items.any { it != null && (it !is String || !hasCategory(it)) }) {
                fail("INVALID_CATEGORY", "Use current category IDs or null for Uncategorized.")
            }
            items.distinct()
        }
        val search = args.value("description_search")?.let {
            if (it !is String || it.length > 200) fail("INVALID_SEARCH", "Use description search text up to 200 characters.")
            it.trim().takeIf(String::isNotEmpty)
        }
        val selectedGroups = parseOptions(args, "group_by", dimensions.toSet(), emptyList(), "INVALID_GROUP")
        val groups = dimensions.filter { it in selectedGroups }
        val primary = groups.firstOrNull()
        val groupSort = if (primary == "category") "total" else primary ?: "spent_on"
        val widget = mode == QueryMode.PAGE
        val sort = if (mode == QueryMode.SUMMARY) Sort("spent_on", "desc") else {
            val available = if (widget && primary != null) setOf(primary, "total") else setOf("spent_on", "amount")
            val by = args.value("sort_by") ?: if (widget) groupSort else "spent_on"
            val order = args.value("sort_order") ?: "desc"
            if (by !is String || by !in available || order !is String || order !in setOf("asc", "desc")) {
                fail("INVALID_SORT", "Sort by ${available.joinToString(" or ")}, in asc or desc order.")
            }
            if (!widget && primary != null) Sort(groupSort, "desc") else Sort(by, order)
        }
        val conditions = mutableListOf("e.deleted_at IS NULL")
        val bindings = mutableListOf<Any?>()
        for ((sql, value) in listOf("e.spent_on >= ?" to start, "e.spent_on <= ?" to end,
            "e.amount_paise >= ?" to min, "e.amount_paise <= ?" to max,
            "instr(lower(COALESCE(e.description, '')), lower(?)) > 0" to search)) {
            if (value != null) {
                conditions.add(sql)
                bindings.add(value)
            }
        }
        if (ids != null) {
            val named = ids.filterNotNull()
            val matches = mutableListOf<String>()
            if (named.isNotEmpty()) matches.add("e.category_id IN (${placeholders(named.size)})")
            if (null in ids) matches.add("e.category_id IS NULL")
            conditions.add("(${matches.joinToString(" OR ")})")
            bindings.addAll(named)
        }
        val filters = json(
            "start_date" to start, "end_date" to end, "category_ids" to ids?.let(::JSONArray),
            "min_amount" to args.value("min_amount"), "max_amount" to args.value("max_amount"), "description_search" to search,
        )
        return Query(
            "WHERE ${conditions.joinToString(" AND ")}", bindings, filters, groups, sort,
            args.integer("page", 1, 1..1_000_000, "INVALID_PAGE"), args.integer("page_size", 20, 1..50, "INVALID_PAGE"),
        )
    }
}

private fun pages(count: Long, size: Int) = maxOf(1, (count + size - 1) / size)

private fun placeholders(size: Int) = List(size) { "?" }.joinToString(", ")

private fun totalGroup(row: SQLiteStatement, offset: Int) =
    json("total" to decimal(row.getLong(offset)), "expense_count" to row.getLong(offset + 1))

private fun categoryTotal(row: SQLiteStatement, offset: Int) =
    totalGroup(row, offset + 2).put("category_id", row.textOrNull(offset) ?: JSONObject.NULL)
        .put("category_name", row.getText(offset + 1))

private fun metricValues(row: SQLiteStatement, offset: Int, metrics: List<String>): JSONObject = JSONObject().apply {
    for ((index, name) in metrics.withIndex()) {
        val column = offset + index
        val value = when {
            row.isNull(column) -> JSONObject.NULL
            name == "count" -> row.getLong(column)
            else -> decimal(row.getLong(column))
        }
        put(name, value)
    }
}

private fun parseOptions(args: JSONObject, key: String, choices: Set<String>, default: List<String>, code: String): List<String> {
    if (!args.has(key)) return default
    val items = args.optJSONArray(key)?.values()
    if (items == null || items.distinct().size != items.size || items.any { it !is String || it !in choices }) {
        fail(code, "Choose distinct values from ${choices.joinToString()}.")
    }
    return items.filterIsInstance<String>()
}

private fun summarySortValue(args: JSONObject, key: String, default: String): String {
    if (!args.has(key)) return default
    return (args.value(key) as? String)?.removeSurrounding("\"")
        ?: fail("INVALID_SORT", "Use a supported summary sort.")
}
