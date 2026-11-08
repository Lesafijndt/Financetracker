package com.pulse.wallet

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class ParsedMoney(val amountMinorUnits: Long, val currency: String)
data class MoneyCandidate(val money: ParsedMoney, val range: IntRange)

/** Exact minor units. Multiple different amounts/currencies are deliberately not guessed. */
object MoneyAmountParser {
    val supportedCurrencies = listOf("MDL", "EUR", "USD")
    private const val NUMBER = "(?:\\d{1,3}(?:[ \\u00A0\\u202F.,']\\d{3})+|\\d+)(?:[.,]\\d{1,2})?"
    private const val CURRENCY = "(?:\\b(?:MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b|[€$])"
    private val amountWithCurrency = Regex(
        "(?iu)(?<![\\p{L}\\d.,])(?:([+-]?)\\s*$CURRENCY\\s*([+-]?$NUMBER)(?!\\d|[.,]\\d)|([+-]?$NUMBER)\\s*$CURRENCY)"
    )
    private val currencyCode = Regex("(?iu)\\b(MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b")
    private val refundWords = Regex("(?iu)\\b(refund(?:ed)?|reversal|reversed|cashback|rambursare|rambursat|restituire|returnare|возврат|возвращ[её]н)\\b")

    fun candidates(text: String): List<MoneyCandidate> = amountWithCurrency.findAll(text).mapNotNull { match ->
        val prefixSign = match.groups[1]?.value.orEmpty()
        val raw = match.groups[2]?.value ?: match.groups[3]?.value ?: return@mapNotNull null
        if (prefixSign.isNotEmpty() && (raw.startsWith('+') || raw.startsWith('-'))) return@mapNotNull null
        val value = parseDecimal(prefixSign + raw) ?: return@mapNotNull null
        // Restrict to a positive Long magnitude so abs(Long.MIN_VALUE) can never overflow.
        val magnitude = try { value.abs().movePointRight(2).longValueExact() } catch (_: ArithmeticException) { return@mapNotNull null }
        val currency = currencyCode.find(match.value)?.value?.uppercase(Locale.ROOT)?.let {
            if (it in setOf("LEI", "LEU", "ЛЕЙ")) "MDL" else it
        } ?: if ('€' in match.value) "EUR" else "USD"
        val positive = prefixSign == "+" || raw.startsWith('+') || refundWords.containsMatchIn(text)
        MoneyCandidate(ParsedMoney(if (positive) magnitude else -magnitude, currency), match.range)
    }.toList()

    fun parse(text: String): ParsedMoney? = candidates(text).map { it.money }.distinct().singleOrNull()

    /** Input fields accept one exact decimal amount, never round an invalid value silently. */
    fun parseManualMinor(text: String, allowZero: Boolean = false): Long? {
        if (!Regex("\\d+(?:[.,]\\d{1,2})?").matches(text.trim())) return null
        return try {
            BigDecimal(text.trim().replace(',', '.')).movePointRight(2).longValueExact().takeIf { it > 0 || (allowZero && it == 0L) }
        } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
    }

    private fun parseDecimal(raw: String): BigDecimal? {
        val compact = raw.replace('\u00A0', ' ').replace('\u202F', ' ')
        val unsigned = compact.removePrefix("-").removePrefix("+")
        val decimalIndex = unsigned.indexOfLast { it == '.' || it == ',' }.takeIf {
            it >= 0 && unsigned.length - it - 1 in 1..2
        }
        val whole = if (decimalIndex == null) unsigned else unsigned.substring(0, decimalIndex)
        val fraction = if (decimalIndex == null) "" else unsigned.substring(decimalIndex + 1)
        val separators = whole.filterNot(Char::isDigit).toSet()
        if (separators.size > 1) return null
        if (separators.isNotEmpty()) {
            val separator = separators.single()
            if (decimalIndex != null && separator == unsigned[decimalIndex]) return null
            if (!Regex("\\d{1,3}(?:${Regex.escape(separator.toString())}\\d{3})+").matches(whole)) return null
        }
        val normalized = (if (compact.startsWith('-')) "-" else "") + whole.filter(Char::isDigit) +
            if (fraction.isEmpty()) "" else ".$fraction"
        return try { BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY) }
        catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }
    }
}
