package com.pulse.wallet

import org.junit.Assert.*
import org.junit.Test

class TransactionAccountingTest {
    private fun row(amount: Long, currency: String = "MDL", state: String = "CONFIRMED", type: String = "EXPENSE", simulated: Boolean = false) =
        TransactionEntity(dedupHash = "test", timestampMs = 1L, rawMerchant = "Shop", normalizedMerchant = "Shop",
            amountMinorUnits = amount, currency = currency, cardLast4 = null, category = "Other",
            categorizationSource = "TEST", txType = type, captureState = state, isSimulated = simulated)

    @Test fun uncertainOrOwnTransfersCannotEnterSpendingEvenWhenTransfersEnabled() {
        val rows = listOf(row(-1000), row(-2000, state = "REVIEW", type = "TRANSFER"),
            row(-3000, state = "IGNORED", type = "TRANSFER"), row(-4000, type = "TRANSFER"), row(-5000, simulated = true))
        assertEquals(5000L, TransactionAccounting.outflows(rows, "MDL", true).sumOf { -it.amountMinorUnits })
        assertEquals(1000L, TransactionAccounting.outflows(rows, "MDL", false).sumOf { -it.amountMinorUnits })
    }

    @Test fun supportedCurrenciesAndRefundReviewAreKeptSeparate() {
        val rows = listOf(row(-1000), row(-2000, "EUR"), row(-3000, "USD"), row(500, type = "INCOME"),
            row(900, state = "REVIEW", type = "INCOME"), row(700, "EUR", type = "INCOME"))
        assertEquals(1000L, TransactionAccounting.outflows(rows, "MDL", true).sumOf { -it.amountMinorUnits })
        assertEquals(2000L, TransactionAccounting.outflows(rows, "EUR", true).sumOf { -it.amountMinorUnits })
        assertEquals(3000L, TransactionAccounting.outflows(rows, "USD", true).sumOf { -it.amountMinorUnits })
        assertEquals(500L, TransactionAccounting.income(rows, "MDL"))
        assertEquals(700L, TransactionAccounting.income(rows, "EUR"))
    }
}
