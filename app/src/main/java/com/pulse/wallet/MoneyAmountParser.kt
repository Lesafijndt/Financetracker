package com.pulse.wallet

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class ParsedMoney(val amountMinorUnits: Long, val currency: String)

/** Parses amounts only when they are explicitly paired with a supported currency. */
object MoneyAmountParser {
    val supportedCurrencies = listOf("MDL", "EUR", "USD")
    private const val NUMBER = "(?:\\d{1,3}(?:[ \\u00A0\\u202F.,']\\d{3})+|\\d+)(?:[.,]\\d{1,2})?"
    private val amountWithCurrency = Regex(
        "(?i)(?:([+-]?)\\s*(?:\\b(?:MDL|EUR|USD)\\b|[\\u20AC$])\\s*([+-]?$NUMBER)(?![\\d.,])|([+-]?$NUMBER)\\s*(?:\\b(?:MDL|EUR|USD)\\b|[\\u20AC$]))"
    )
    private val currencyCode = Regex("(?i)\\b(MDL|EUR|USD)\\b")
    private val refundWords = Regex("(?i)\\b(refund(?:ed)?|reversal|reversed|cashback|rambursare|rambursat|restituire|returnare|\\u0432\\u043E\\u0437\\u0432\\u0440\\u0430\\u0442|\\u0432\\u043E\\u0437\\u0432\\u0440\\u0430\\u0449\\u0451\\u043D)\\b")

    fun parse(notificationText: String): ParsedMoney? {
        val match = amountWithCurrency.find(notificationText) ?: return null
        val prefixSign = match.groups[1]?.value.orEmpty()
        val rawNumber = (match.groups[2]?.value ?: match.groups[3]?.value) ?: return null
        val currency = currencyCode.find(match.value)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
            ?: when {
                '\u20AC' in match.value -> "EUR"
                '$' in match.value -> "USD"
                else -> return null
            }
        val value = parseDecimal(prefixSign + rawNumber) ?: return null
        val minorUnits = try {
            value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }

        val isRefund = refundWords.containsMatchIn(notificationText) || prefixSign == "+" || rawNumber.startsWith("+")
        val signedAmount = when {
            minorUnits == 0L -> 0L
            value.signum() < 0 -> -kotlin.math.abs(minorUnits)
            isRefund -> kotlin.math.abs(minorUnits)
            else -> -kotlin.math.abs(minorUnits)
        }
        return ParsedMoney(signedAmount, currency)
    }

    private fun parseDecimal(raw: String): BigDecimal? {
        var value = raw.replace(" ", "").replace("\u00A0", "").replace("\u202F", "")
            .replace("'", "")
        val sign = if (value.startsWith('-')) "-" else ""
        value = value.removePrefix("-").removePrefix("+")
        if (value.isEmpty()) return null

        val comma = value.lastIndexOf(',')
        val dot = value.lastIndexOf('.')
        val decimalSeparator = when {
            comma >= 0 && dot >= 0 -> if (comma > dot) ',' else '.'
            comma >= 0 && value.length - comma - 1 in 1..2 -> ','
            dot >= 0 && value.length - dot - 1 in 1..2 -> '.'
            else -> null
        }

        val normalized = if (decimalSeparator == null) {
            value.filter(Char::isDigit).let { sign + it }
        } else {
            val decimalIndex = value.lastIndexOf(decimalSeparator)
            val whole = value.substring(0, decimalIndex).filter(Char::isDigit).ifEmpty { "0" }
            val fraction = value.substring(decimalIndex + 1).filter(Char::isDigit)
            if (fraction.isEmpty()) return null
            sign + whole + "." + fraction
        }

        return try {
            BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY)
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}
