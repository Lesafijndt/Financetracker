package com.pulse.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextParserTest {
    @Test
    fun extractsMerchantFromAFormattedBankAlert() {
        assertEquals(
            "Kaufland Chisinau",
            NotificationTextParser.merchant(
                "Card transaction",
                "Card purchase at Kaufland Chisinau EUR 23.50 using Visa **** 4110"
            )
        )
    }

    @Test
    fun extractsMerchantFromACompactAlert() {
        assertEquals("Linella", NotificationTextParser.merchant("Transaction", "Paid at Linella MDL 45.00"))
    }

    @Test
    fun extractsMerchantWhenTheTitleIsAGenericPaymentAlert() {
        assertEquals(
            "Andy Pizza",
            NotificationTextParser.merchant("Payment notification", "Card purchase at Andy Pizza: MDL 180.00")
        )
    }

    @Test
    fun doesNotMistakeTheUpdatedBalanceForThePurchaseAmount() {
        val text = NotificationTextParser.removeBalanceAmounts("Balance: 1,200 MDL; purchase at Linella: 45 MDL")
        assertEquals(ParsedMoney(-4500, "MDL"), MoneyAmountParser.parse(text))
    }
}
