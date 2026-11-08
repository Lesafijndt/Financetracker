package com.pulse.wallet

/** One accounting boundary for dashboard, income and category summaries. */
object TransactionAccounting {
    fun outflows(transactions: List<TransactionEntity>, currency: String, includeTransfers: Boolean): List<TransactionEntity> =
        transactions.filter {
            it.captureState == "CONFIRMED" && !it.isSimulated && it.currency == currency && it.amountMinorUnits < 0 &&
                (it.txType == "EXPENSE" || (includeTransfers && it.txType == "TRANSFER"))
        }

    fun income(transactions: List<TransactionEntity>, currency: String): Long = transactions.filter {
        it.captureState == "CONFIRMED" && !it.isSimulated && it.currency == currency && it.amountMinorUnits > 0
    }.sumOf { it.amountMinorUnits }
}
