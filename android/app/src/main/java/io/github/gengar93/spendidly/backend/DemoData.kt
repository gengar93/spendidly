package io.github.gengar93.spendidly.backend

import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

private val demoCategories = listOf(
    "Accessories" to "Device add-ons and small carry items, like phone case, screen protector, USB-C cable.",
    "Bills" to "Recurring or periodic utility and telecom bills, like Jio recharge, Airtel postpaid, broadband.",
    "Clothes" to "Clothing and footwear, like T-shirts, socks, track pants.",
    "Digital" to "Apps, online subscriptions, and store credit, like ChatGPT Plus, Amazon Prime, Apple Gift Card.",
    "Electronics" to "Devices and computer hardware, like Pixel, MacBook, monitor.",
    "Family" to "Purchases specifically for relatives, like medicine for a grandparent, slippers for a parent.",
    "Food & Snacks" to "Prepared meals, snacks, and drinks, like Swiggy lunch, pizza, chai.",
    "Groceries" to "Ingredients and pantry staples, like curd, ghee, fruit.",
    "Home" to "Housing, furnishings, and household services, like rent, furniture rental, cook.",
    "Learning" to "Courses and study materials, like Udemy course.",
    "Other" to "Miscellaneous spending outside named categories, like tips, donations, gifts for friends.",
    "Personal" to "Grooming, fitness, and personal errands, like haircut, gym membership, face wash.",
    "Transport" to "Rides, public transit, and bus fares, like Uber, Ola, Rapido.",
)

/**
 * Two months of fictional spending ending today, for trying the app out. Built as a .spendidly
 * bundle and imported through the normal importer, so it gets the same validation and de-duplication.
 */
internal fun loadDemoData(database: LocalDatabase, clock: Clock = Clock.systemUTC()): ImportSummary {
    val today = LocalDate.now(clock.withZone(spendingZone))
    val start = today.minusDays(60)
    val rng = Random(today.toEpochDay())
    val rows = mutableListOf<Triple<LocalDate, Int, Pair<String, String>>>()
    fun add(d: LocalDate, amount: Int, category: String, note: String) { rows += Triple(d, amount, category to note) }

    val food = listOf("Swiggy", "Zomato", "Third Wave Coffee", "Meghana Foods", "Truffles", "Rameshwaram Cafe",
        "Domino's", "Subway", "Burma Burma", "Lunch at office canteen")
    val coffee = listOf("Filter coffee", "Chai Point", "Third Wave Coffee")
    val grocery = listOf("Zepto", "Swiggy Instamart", "BigBasket", "Blinkit", "Vegetables", "Milk and curd")
    val rides = listOf("Rapido", "Uber", "Namma Yatri", "Ola", "Auto", "Metro card top-up")

    var d = start
    while (!d.isAfter(today)) {
        val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        if (!weekend) repeat(listOf(1, 2, 2).random(rng)) { add(d, listOf(62, 78, 94, 110, 135, 168, 190, 214, 236).random(rng), "Transport", rides.random(rng)) }
        else if (rng.nextDouble() < 0.6) add(d, listOf(120, 180, 245, 310, 380).random(rng), "Transport", rides.take(3).random(rng))
        repeat(listOf(1, 1, 2, 2, 3).random(rng)) {
            if (rng.nextDouble() < 0.2) add(d, listOf(60, 90, 120, 140).random(rng), "Food & Snacks", coffee.random(rng))
            else add(d, listOf(180, 220, 249, 280, 320, 365, 410, 480).random(rng), "Food & Snacks", food.random(rng))
        }
        if (weekend && rng.nextDouble() < 0.4) add(d, listOf(1150, 1480, 1860, 2240).random(rng), "Food & Snacks", listOf("Dinner with friends", "Brunch at Toit", "Birthday dinner").random(rng))
        if (rng.nextDouble() < 0.38) add(d, listOf(140, 210, 265, 330, 415, 520, 640).random(rng), "Groceries", grocery.random(rng))
        when (d.dayOfMonth) {
            1 -> { add(d, 32000, "Home", "Rent"); add(d, 6000, "Home", "Cook") }
            3 -> add(d, 799, "Bills", "Broadband")
            5 -> add(d, 1150, "Bills", "Electricity bill")
            7 -> add(d, 2499, "Personal", "Gym membership")
            12 -> add(d, 499, "Bills", "Postpaid mobile")
            18 -> add(d, 1999, "Digital", "ChatGPT Plus")
            22 -> add(d, 149, "Digital", "Spotify")
        }
        d = d.plusDays(1)
    }
    // One-offs, placed by days before today so the recent weeks always look lived in.
    listOf(
        56 to Triple(2799, "Clothes", "Running shoes"), 49 to Triple(450, "Personal", "Haircut"),
        42 to Triple(18999, "Electronics", "Noise-cancelling headphones"), 36 to Triple(1299, "Accessories", "Phone case and screen protector"),
        31 to Triple(3200, "Family", "Sweets for the family"), 28 to Triple(6400, "Other", "Weekend trip to Coorg, homestay"),
        28 to Triple(1850, "Transport", "Bus to Coorg"), 26 to Triple(1850, "Transport", "Bus back from Coorg"),
        22 to Triple(899, "Learning", "Online course"), 16 to Triple(1499, "Clothes", "Kurta"),
        13 to Triple(600, "Home", "Laundry"), 9 to Triple(7499, "Home", "Air purifier"),
        6 to Triple(500, "Other", "Gift card for a colleague's farewell"), 3 to Triple(2299, "Accessories", "65W charger"),
        1 to Triple(950, "Personal", "Pharmacy"),
    ).forEach { (ago, e) -> add(today.minusDays(ago.toLong()), e.first, e.second, e.third) }

    val csv = buildString {
        append("Date,Amount,Category,Note\n")
        rows.sortedByDescending { it.first }.forEach { (day, amount, cn) ->
            append("$day,$amount.00,\"${cn.first}\",\"${cn.second.replace("\"", "\"\"")}\"\n")
        }
    }
    val categories = JSONArray(demoCategories.map { (n, desc) -> json("name" to n, "description" to desc) })
    val zip = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("expenses.csv")); z.write(csv.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("categories.json")); z.write(categories.toString().toByteArray()); z.closeEntry()
        }
    }.toByteArray()
    return importBundle(database, ByteArrayInputStream(zip), clock)
}
