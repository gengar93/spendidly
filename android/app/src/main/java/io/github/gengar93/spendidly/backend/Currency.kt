package io.github.gengar93.spendidly.backend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * The currencies an install can use. Amounts are stored as integer hundredths ("paise") of the chosen
 * currency whatever it is; zero-decimal currencies simply never show or accept the hundredths.
 * Toman isn't an ISO currency (1 toman = 10 rials), so it has the internal code IRT.
 */
enum class AppCurrency(val code: String, val displayName: String, internal val unit: String, val decimals: Int) {
    USD("USD", "US dollar", "dollar", 2),
    EUR("EUR", "Euro", "euro", 2),
    JPY("JPY", "Japanese yen", "yen", 0),
    GBP("GBP", "British pound", "pound", 2),
    CNY("CNY", "Chinese yuan", "yuan", 2),
    AUD("AUD", "Australian dollar", "dollar", 2),
    CAD("CAD", "Canadian dollar", "dollar", 2),
    CHF("CHF", "Swiss franc", "franc", 2),
    HKD("HKD", "Hong Kong dollar", "dollar", 2),
    SGD("SGD", "Singapore dollar", "dollar", 2),
    INR("INR", "Indian rupee", "rupee", 2),
    IRT("IRT", "Iranian toman", "toman", 0);

    /** How the AI prompt, tools and results name the currency; toman has no ISO code to use. */
    internal val modelName: String get() = if (this == IRT) "toman" else code

    /** The symbol on its own, for labels like "Min €". */
    fun symbol(locale: Locale = Locale.getDefault()): String = parts(100, locale).let { (it.prefix + it.suffix).trim() }

    /**
     * An amount split around its number, e.g. "$" "1,234.50" "" or "" "1.234,50" " €", in the phone's
     * grouping and symbol placement. INR keeps Indian lakh grouping; hundredths show only when present.
     */
    fun parts(paise: Long, locale: Locale = Locale.getDefault()): MoneyParts {
        val sign = if (paise < 0) "−" else ""
        val abs = kotlin.math.abs(paise)
        val fraction = decimals > 0 && abs % 100 != 0L
        return when (this) {
            INR -> MoneyParts(sign + "₹", indianGrouping(abs, fraction), "")
            IRT -> MoneyParts(sign, formats(locale).number(fraction).format(BigDecimal.valueOf(abs, 2)), " toman")
            else -> {
                val f = formats(locale)
                val value = BigDecimal.valueOf(abs, 2)
                val number = f.number(fraction).format(value)
                val full = f.currency(fraction).format(value)
                val at = full.indexOf(number)
                if (at < 0) return MoneyParts(sign, full, "")
                // Keep letter symbols off the digits: "CHF 12", not "CHF12".
                val prefix = full.substring(0, at).let { if (it.lastOrNull()?.isLetter() == true) "$it\u00A0" else it }
                val suffix = full.substring(at + number.length).let { if (it.firstOrNull()?.isLetter() == true) "\u00A0$it" else it }
                MoneyParts(sign + prefix, number, suffix)
            }
        }
    }

    fun format(paise: Long, symbol: Boolean = true, locale: Locale = Locale.getDefault()): String =
        parts(paise, locale).let { if (symbol) it.toString() else it.number }

    /**
     * Parses a typed amount like "277", "1,250.5" or "1.250,5" into hundredths; null if invalid or zero.
     * The phone's decimal separator always marks decimals; the other one does too unless exactly three
     * digits follow it, as in "1,250". Zero-decimal currencies reject decimals.
     */
    fun parse(text: String, locale: Locale = Locale.getDefault()): Long? {
        val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val clean = text.filter { !it.isWhitespace() && it != '\'' && it != '’' }
        val last = clean.indexOfLast { it == '.' || it == ',' }
        val after = if (last < 0) "" else clean.substring(last + 1)
        val isDecimal = last >= 0 && (after.length != 3 || (clean[last] == decimal && decimals > 0))
        val whole = (if (isDecimal) clean.substring(0, last) else clean).filter { it != '.' && it != ',' }
        val fraction = if (isDecimal) after else ""
        if (!Regex("[0-9]{1,12}").matches(whole) || !Regex("[0-9]{0,$decimals}").matches(fraction)) return null
        return (whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()).takeIf { it > 0 }
    }

    /** An amount for an input field: the phone's decimal separator, no grouping. */
    fun input(paise: Long, locale: Locale = Locale.getDefault()): String {
        if (paise % 100 == 0L || decimals == 0) return (Math.round(paise / 100.0)).toString()
        val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return "${paise / 100}$decimal${(paise % 100).toString().padStart(2, '0')}"
    }

    companion object {
        fun fromCode(code: String?): AppCurrency? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }

        /** The region's currency when supported (IRR means toman), else USD. */
        fun regionDefault(locale: Locale = Locale.getDefault()): AppCurrency {
            val iso = runCatching { java.util.Currency.getInstance(locale)?.currencyCode }.getOrNull()
            return if (iso == "IRR") IRT else entries.firstOrNull { it.code == iso && it != IRT } ?: USD
        }
    }
}

data class MoneyParts(val prefix: String, val number: String, val suffix: String) {
    override fun toString() = prefix + number + suffix
}

/**
 * The install's currency. [SpendidlyBackend] sets it from preferences; it's snapshot state so screens
 * showing amounts recompose when it changes.
 */
internal var currentCurrency by mutableStateOf(AppCurrency.INR)

/** 132680 → 1,32,680, with ".50" only when [fraction]. */
private fun indianGrouping(paise: Long, fraction: Boolean): String {
    val whole = (paise / 100).toString()
    val grouped = if (whole.length <= 3) whole else {
        val head = whole.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    }
    return if (fraction) "$grouped.${(paise % 100).toString().padStart(2, '0')}" else grouped
}

/** Number formats are slow to build and not thread-safe, so each thread keeps the last pair it used. */
private class Formats(val currency: AppCurrency, val locale: Locale) {
    private val plain = NumberFormat.getNumberInstance(locale)
    private val money = NumberFormat.getCurrencyInstance(locale).also {
        if (currency != AppCurrency.IRT) it.currency = java.util.Currency.getInstance(currency.code)
    }

    fun number(fraction: Boolean) = plain.digits(fraction)
    fun currency(fraction: Boolean) = money.digits(fraction)

    private fun NumberFormat.digits(fraction: Boolean) = apply {
        val digits = if (fraction) 2 else 0
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
}

private val cachedFormats = ThreadLocal<Formats>()

private fun AppCurrency.formats(locale: Locale): Formats =
    cachedFormats.get()?.takeIf { it.currency == this && it.locale == locale }
        ?: Formats(this, locale).also { cachedFormats.set(it) }
