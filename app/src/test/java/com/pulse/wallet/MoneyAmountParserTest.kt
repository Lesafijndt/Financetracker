package com.pulse.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyAmountParserTest {
    @Test
    fun parsesDotDecimalAndCurrencyCode() {
        assertEquals(ParsedMoney(-123456, "EUR"), MoneyAmountParser.parse("Paid EUR 1,234.56 at Market"))
    }

    @Test
    fun parsesCommaDecimalAndEuropeanGrouping() {
        assertEquals(ParsedMoney(-123456, "EUR"), MoneyAmountParser.parse("Payment \u20AC 1.234,56"))
    }

    @Test
    fun parsesSpaceGroupedMoldovanLeiAndCurrencyAfterAmount() {
        assertEquals(ParsedMoney(-450075, "MDL"), MoneyAmountParser.parse("4 500,75 MDL paid"))
    }

    @Test
    fun handlesRefundsAsCredits() {
        assertEquals(ParsedMoney(7525, "EUR"), MoneyAmountParser.parse("Refunded EUR 75.25"))
    }

    @Test
    fun parsesDollarSymbolAsUsd() {
        assertEquals(ParsedMoney(-1000, "USD"), MoneyAmountParser.parse("Paid $ 10.00"))
    }

    @Test
    fun respectsAnExplicitCreditSignBeforeTheCurrency() {
        assertEquals(ParsedMoney(1000, "EUR"), MoneyAmountParser.parse("+\u20AC10.00"))
    }

    @Test
    fun ignoresNumbersWithoutAnExplicitCurrency() {
        assertNull(MoneyAmountParser.parse("Paid 75.25 at Market"))
    }

    @Test
    fun rejectsCurrenciesOutsideTheSupportedSet() {
        assertNull(MoneyAmountParser.parse("RON 10.00"))
        assertNull(MoneyAmountParser.parse("GBP 10.00"))
    }
}
