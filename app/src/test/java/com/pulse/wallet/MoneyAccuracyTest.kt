package com.pulse.wallet

import org.junit.Assert.*
import org.junit.Test

class MoneyAccuracyTest {
    @Test fun rejectsMalformedOrOverflowingNumbers() {
        for (text in listOf("12.3456 MDL", "MDL 12.3456", "MDL 1,234.567,89", "92233720368547758.08 MDL", "-92233720368547758.08 MDL"))
            assertNull(text, MoneyAmountParser.parse(text))
    }
    @Test fun supportsGroupingWhitespaceAndSentencePunctuation() {
        assertEquals(ParsedMoney(-123456, "MDL"), MoneyAmountParser.parse("Paid 1\u202F234,56 MDL."))
        assertEquals(ParsedMoney(-123456, "USD"), MoneyAmountParser.parse("Paid USD 1,234.56, at Shop"))
    }
    @Test fun conflictingAmountsAndCurrenciesAreNeverFirstMatchWins() {
        assertNull(MoneyAmountParser.parse("Paid 50 MDL and 100 MDL"))
        assertNull(MoneyAmountParser.parse("Paid 50 EUR or 50 USD"))
        assertEquals(ParsedMoney(-5000, "MDL"), MoneyAmountParser.parse("50 MDL; amount 50 MDL"))
    }
    @Test fun manualCorrectionUsesExactCentsAndRejectsSilentRounding() {
        assertEquals(29L, MoneyAmountParser.parseManualMinor("0.29"))
        assertEquals(123456L, MoneyAmountParser.parseManualMinor("1234,56"))
        for (input in listOf("1.234", "-10", "NaN", "Infinity", "0", "92233720368547758.08"))
            assertNull(input, MoneyAmountParser.parseManualMinor(input))
    }
}
