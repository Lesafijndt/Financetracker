package com.pulse.wallet

data class CaptureDecision(
    val money: ParsedMoney?,
    val merchant: String,
    val type: String,
    val reviewReason: String = ""
) {
    val needsReview: Boolean get() = reviewReason.isNotBlank()
}

/** A notification is evidence, not a bank ledger. Uncertain evidence never enters totals. */
object NotificationCaptureParser {
    fun parse(source: String, title: String, body: String): CaptureDecision? {
        val combined = "$title\n$body"
        val refund = NotificationTransactionFilter.isRefund(combined)
        val notFinal = NotificationTransactionFilter.isNotFinal(combined)
        if (NotificationTransactionFilter.isSelfTransfer(combined) ||
            (notFinal && !refund) || NotificationTransactionFilter.isNonTransactional(combined)) return null
        if (!refund && NotificationTransactionFilter.isIncoming(combined)) return null
        val transfer = NotificationTransactionFilter.isTransfer(combined)
        val payment = NotificationTransactionFilter.isPayment(combined)
        val transactionText = NotificationTextParser.removeBalanceAmounts(combined)
        val candidates = MoneyAmountParser.candidates(transactionText).map { it.money }.distinct()
        val money = candidates.singleOrNull()?.takeIf { it.amountMinorUnits != 0L }
        if (!refund && money != null && money.amountMinorUnits > 0) return null
        if (candidates.isEmpty() && !payment && !transfer && !refund) return null
        val merchant = NotificationTextParser.merchantOrNull(title, body)
        val type = if (refund) "INCOME" else if (transfer) "TRANSFER" else "EXPENSE"
        val multipleLines = body.lineSequence().count {
            MoneyAmountParser.candidates(NotificationTextParser.removeBalanceAmounts(it)).isNotEmpty() &&
                NotificationTransactionFilter.isPayment(it)
        } > 1
        val reason = when {
            notFinal && refund -> "This refund also has a pending, failed or cancelled status. Check the final bank entry before confirming."
            transfer -> "The destination is not identified as yours or someone else's. Ignore your own transfer, or confirm an external transfer."
            candidates.size > 1 || multipleLines -> "Several transaction amounts or currencies are present. Check the bank entry and enter the actual amount."
            money == null -> "No single valid transaction amount was found. Check the bank entry before confirming."
            merchant == null -> "The merchant could not be identified reliably. Check the notification before confirming."
            !payment && !refund && source != "WALLET" -> "This alert does not clearly identify a completed purchase. Check it before confirming."
            else -> ""
        }
        return CaptureDecision(if (multipleLines) null else money, merchant ?: "Unidentified transaction", type, reason)
    }
}

/** Cross-source matches are one-to-one Wallet/bank pairs, never two bank transfers. */
object CaptureDuplicateMatcher {
    fun complementarySources(source: String, existingSources: String): Boolean {
        val existing = existingSources.split('+').toSet()
        if (source in existing) return false
        return (source == "WALLET" && existing.size == 1 && existing.any { it in setOf("MICB", "OTP", "UP") }) ||
            (source in setOf("MICB", "OTP", "UP") && existing == setOf("WALLET"))
    }

    fun canMatch(source: String, existingSources: String, merchant: String, existingMerchant: String,
                 type: String, existingType: String): Boolean {
        val existing = existingSources.split('+').toSet()
        if (source in existing || type != existingType || type == "TRANSFER") return false
        if (!complementarySources(source, existingSources)) return false
        val identity = NotificationTextParser.merchantIdentity(merchant)
        return identity.isNotEmpty() && identity != "unidentifiedtransaction" &&
            identity == NotificationTextParser.merchantIdentity(existingMerchant)
    }
}
