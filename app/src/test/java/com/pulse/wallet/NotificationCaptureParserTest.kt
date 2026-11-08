package com.pulse.wallet

import org.junit.Assert.*
import org.junit.Test

class NotificationCaptureParserTest {
    private fun parse(body: String, source: String = "MICB", title: String = "Card transaction") =
        NotificationCaptureParser.parse(source, title, body)

    @Test fun selectsPurchaseAmountRegardlessOfBalanceOrder() {
        for (body in listOf("Balance: 1,200 MDL; purchase at Linella: 45 MDL",
            "Purchase at Linella: 45 MDL; available balance: 1,200 MDL",
            "Sold disponibil: 1 200,00 MDL; Plata la Linella 45,00 MDL",
            "Остаток: 1 200,00 MDL; Покупка 45,00 MDL")) {
            assertEquals(body, ParsedMoney(-4500, "MDL"), parse(body)?.money)
        }
        assertFalse(parse("Balance: 1,200 MDL; purchase at Linella: 45 MDL")!!.needsReview)
    }

    @Test fun reviewsDifferentAmountsAndCurrenciesInsteadOfTakingTheFirst() {
        for (body in listOf("Purchase at Linella 10 EUR; charged 195 MDL",
            "Purchase at Linella 100 MDL; fee 2 MDL", "Paid at Linella 10 MDL and paid at Coffee 20 MDL")) {
            val result = parse(body)!!
            assertTrue(body, result.needsReview)
            assertNull(body, result.money)
        }
    }

    @Test fun doesNotConvertBalanceOnlyToAnExpense() {
        val result = parse("Available balance: 1,200 MDL")
        assertTrue(result == null || (result.needsReview && result.money == null))
    }

    @Test fun ignoresOwnTransfersInThreeLanguages() {
        for (body in listOf("Transfer between my own accounts: 150 MDL", "Money sent to my account: 150 MDL",
            "Transfer între cardurile mele: 150 MDL", "Transfer către contul propriu: 150 MDL",
            "Перевод между своими счетами: 150 MDL", "Перевод на свой счёт: 150 MDL",
            "Перевод на свою карту: 150 MDL")) assertNull(body, parse(body))
    }

    @Test fun unidentifiedTransferNeverCountsAsConfirmedSpending() {
        for (body in listOf("Transfer P2P MIA spre Alexandru 300 MDL", "Transfer to Ana: 150 MDL",
            "Перевод 300 MDL на карту **** 1234", "Transfer between accounts 500 MDL")) {
            val result = parse(body)!!
            assertTrue(body, result.needsReview)
            assertEquals("TRANSFER", result.type)
        }
    }

    @Test fun ordinaryPurchaseUsingOwnCardIsStillAPurchase() {
        val result = parse("Paid at Linella 45 MDL using my card **** 1234")!!
        assertFalse(result.needsReview)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun rejectsPendingDeclinedSecurityAndPromotionalAlerts() {
        for (body in listOf("Payment declined 50 MDL", "Plata respinsă 50 MDL", "Оплата отклонена 50 MDL",
            "Payment pending 50 MDL", "Payment verification code 1234 for 50 MDL",
            "Special offer cashback 50 MDL at Linella")) assertNull(body, parse(body))
    }

    @Test fun depositsAndExplicitCreditsAreNotMistakenForExpenses() {
        assertNull(parse("Received 200 MDL from Ana"))
        assertNull(parse("Card credited 200 MDL"))
        assertNull(parse("+200 MDL"))
        assertNull(parse("Alimentare 200 MDL"))
    }

    @Test fun refundWithDebitStyleSignIsPositiveAndDoesNotBecomeTransfer() {
        val result = parse("Refund received: -12.50 EUR", title = "Linella")!!
        assertEquals(ParsedMoney(1250, "EUR"), result.money)
        assertEquals("INCOME", result.type)
        assertFalse(result.needsReview)
    }

    @Test fun refundsToOwnAccountsAreNotSelfTransfersAndUnsettledRefundsNeedReview() {
        assertFalse(NotificationTransactionFilter.isSelfTransfer("Refund to my account 50 MDL"))
        val result = parse("Refund pending 50 MDL", title = "Linella")!!
        assertTrue(result.needsReview)
        assertEquals("INCOME", result.type)
    }

    @Test fun walletMerchantWithAmountCanBeCapturedWithoutBankVerbs() {
        val result = parse("45.00 MDL with Mastercard •••• 4821", "WALLET", "Linella")!!
        assertFalse(result.needsReview)
        assertEquals("Linella", result.merchant)
    }

    @Test fun incompleteOrUnknownBankTextRequiresReview() {
        assertTrue(parse("Card operation 50 MDL")!!.needsReview)
        assertTrue(parse("Paid 50 at Linella")!!.needsReview)
        assertTrue(parse("Paid 50 MDL")!!.needsReview)
    }

    @Test fun duplicateNotificationFieldsDoNotCreateMultipleTransactions() {
        assertEquals("Paid 50 MDL at Linella; balance 100 MDL", NotificationTextParser.body(
            "Paid 50 MDL at Linella; balance 100 MDL", "Paid 50 MDL at Linella", listOf("Old purchase 20 MDL")))
        assertEquals("Paid 50 MDL", NotificationTextParser.body(null, "Paid 50 MDL", listOf("Old purchase 20 MDL")))
        val result = parse("Paid at Linella 50 MDL\nPaid at Coffee 50 MDL")!!
        assertTrue(result.needsReview)
        assertNull(result.money)
    }

    @Test fun onlyKnownPackagesAreTrusted() {
        assertNull(NotificationSources.detect("com.example.sms", "Up mobil purchase 300 MDL"))
        assertNull(NotificationSources.detect("fake.up.mobil", "Purchase 300 MDL"))
        assertEquals("MICB", NotificationSources.detect("md.micb.mwb.prod", ""))
        assertEquals("OTP", NotificationSources.detect("md.mobiasbanca.otpdirekt", ""))
        assertEquals("WALLET", NotificationSources.detect("com.google.android.apps.walletnfcrel", ""))
    }

    @Test fun notificationTimeSurvivesRepostsAndRejectsInvalidDates() {
        assertEquals(1000L, NotificationSources.eventTime(2000L, 1000L))
        assertEquals(2000L, NotificationSources.eventTime(2000L, 0L))
        assertEquals(2000L, NotificationSources.eventTime(2000L, 99999999L))
    }
}
