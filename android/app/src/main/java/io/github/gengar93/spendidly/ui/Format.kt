package io.github.gengar93.spendidly.ui

import io.github.gengar93.spendidly.backend.AppCurrency
import io.github.gengar93.spendidly.backend.MoneyParts
import io.github.gengar93.spendidly.backend.currentCurrency
import io.github.gengar93.spendidly.backend.parseAmount
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Amounts are integer hundredths ("paise") of the install's currency, whatever that is.

/** The install's currency; reading it in a composable recomposes it when the currency changes. */
val currency: AppCurrency get() = currentCurrency

/**
 * An amount in the install's currency with the phone's grouping and symbol placement, e.g. $1,234.50,
 * 1.234,50 €, ¥1,200 or 120,000 toman. INR keeps Indian grouping (₹1,32,680). Hundredths show only when present.
 */
fun rupees(paise: Long, symbol: Boolean = true): String = currency.format(paise, symbol)

/** The symbol before and after the number, for showing them in a smaller size. */
fun moneyParts(paise: Long): MoneyParts = currency.parts(paise)

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)
private val full = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> if (date.year == today.year) date.format(weekdayDayMonth) else date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))
}

fun shortDate(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayMonth)
}

fun fullDate(date: LocalDate): String = date.format(full)

/** Parses a typed amount like "277", "1,250.5" or, under a European locale, "1.250,5" into paise; null if invalid or zero. */
fun parsePaise(text: String): Long? = currency.parse(text)

/** An amount for an input field, with the phone's decimal separator. */
fun paiseToInput(paise: Long): String = currency.input(paise)

/** Parses a plain decimal from the backend or the AI ("450.00"), whatever the locale; null if invalid. */
fun decimalPaise(text: String?): Long? = runCatching { parseAmount(text?.trim(), allowZero = true) }.getOrNull()

/** Characters an amount field accepts: digits, plus separators unless the currency has no decimals. */
fun amountChar(ch: Char): Boolean = ch.isDigit() || (currency.decimals > 0 && (ch == '.' || ch == ','))

/** Whether a search like "450", "₹4" or "1.250" starts this amount, ignoring the symbol and grouping. */
fun amountMatches(paise: Long, query: String): Boolean {
    val grouping = DecimalFormatSymbols.getInstance().groupingSeparator
    fun digits(text: String) = text.filter { !it.isWhitespace() && it != ',' && it != grouping && it != '\'' && it != '’' }
    val q = digits(query.trim().lowercase().removePrefix(currency.symbol().lowercase()).removeSuffix(currency.symbol().lowercase()))
    return q.isNotEmpty() && q.first().isDigit() && digits(rupees(paise, symbol = false)).startsWith(q)
}

/** Whole units, for summaries where hundredths are noise. */
fun wholeRupees(paise: Long): String = rupees(Math.round(paise / 100.0) * 100)
