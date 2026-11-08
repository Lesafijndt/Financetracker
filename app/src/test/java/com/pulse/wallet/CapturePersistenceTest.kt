package com.pulse.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production DAO transaction algorithm with an in-memory persistence double. */
class CapturePersistenceTest {
    private fun tx(event: String, source: String = "WALLET", time: Long = 1000000L,
                   merchant: String = "Linella", amount: Long = -5000L, key: String = event,
                   currency: String = "MDL") = TransactionEntity(dedupHash = event, timestampMs = time,
        rawMerchant = merchant, normalizedMerchant = merchant, amountMinorUnits = amount, currency = currency,
        cardLast4 = null, category = "Groceries", categorizationSource = "KEYWORD_RULE", sourceApp = source,
        notificationKeyHash = key)
    private suspend fun MemoryDao.capture(tx: TransactionEntity) = insertDeduplicated(tx, 180000L, tx.dedupHash)

    @Test fun exactReplayAndBankReplayAfterWalletMergeStayOneTransaction() = runBlocking {
        val dao = MemoryDao()
        val wallet = tx("wallet").copy(cardLast4 = "1234")
        val bank = tx("bank", "MICB", 1001000L).copy(cardLast4 = "9876") // Wallet's virtual suffix may differ.
        assertTrue(dao.capture(wallet))
        assertFalse(dao.capture(wallet))
        assertFalse(dao.capture(bank))
        assertFalse(dao.capture(bank))
        assertEquals(1, dao.rows.size)
        assertEquals("WALLET+MICB", dao.rows.values.single().sourceApp)
        assertEquals(2, dao.receipts.size)
    }

    @Test fun repeatedRealPurchasesFromSameSourceRemainSeparate() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("first"))
        dao.capture(tx("second", time = 1001000L))
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.values.all { it.captureState == "CONFIRMED" })
    }

    @Test fun reusedNotificationKeyDoesNotSilentlyLoseOrCountAnotherIdenticalPurchase() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("first", key = "reused"))
        dao.capture(tx("second", time = 1001000L, key = "reused"))
        assertEquals(2, dao.rows.size)
        assertEquals("REVIEW", dao.rows.values.last().captureState)
    }

    @Test fun ambiguousCrossSourceMatchIsReviewedRatherThanChoosingOneOfTwoPurchases() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("first"))
        dao.capture(tx("second", time = 1001000L))
        dao.capture(tx("bank", "MICB", 1002000L))
        assertEquals(3, dao.rows.size)
        assertEquals("REVIEW", dao.rows.values.last().captureState)
    }

    @Test fun differentBankSourcesAndDifferentCurrenciesAreNotMerged() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("micb", "MICB"))
        dao.capture(tx("otp", "OTP"))
        dao.capture(tx("wallet", currency = "EUR"))
        assertEquals(3, dao.rows.size)
        assertTrue(dao.rows.values.all { it.captureState == "CONFIRMED" })
    }

    @Test fun inconsistentMerchantAcrossSourcesRequiresReview() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("wallet"))
        dao.capture(tx("bank", "MICB", merchant = "Different merchant"))
        assertEquals(2, dao.rows.size)
        assertEquals("REVIEW", dao.rows.values.last().captureState)
    }

    @Test fun deletedAndIgnoredEntriesDoNotReappearOnRescan() = runBlocking {
        val dao = MemoryDao()
        val capture = tx("first")
        dao.capture(capture)
        dao.updateTransaction(dao.rows.values.single().copy(captureState = "IGNORED"))
        assertFalse(dao.capture(capture))
        assertEquals("IGNORED", dao.rows.values.single().captureState)
        dao.deleteTransaction(dao.rows.values.single())
        assertFalse(dao.capture(capture))
        assertTrue(dao.rows.isEmpty())
        assertEquals(1, dao.receipts.size)
    }

    @Test fun anUpdatedNotificationCannotInsertASecondAmount() = runBlocking {
        val dao = MemoryDao()
        val capture = tx("first")
        dao.capture(capture)
        assertFalse(dao.capture(tx("first", amount = -7500L)))
        assertEquals(1, dao.rows.size)
        assertEquals("REVIEW", dao.rows.values.single().captureState)
        assertEquals(-5000L, dao.rows.values.single().originalAmountMinorUnits)
    }

    @Test fun cancellationOrOwnTransferUpdateRemovesTheEntryFromConfirmedSpending() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("first"))
        dao.reconcileExcludedEvent("first", false)
        assertEquals("REVIEW", dao.rows.values.single().captureState)
        dao.reconcileExcludedEvent("first", true)
        assertEquals("IGNORED", dao.rows.values.single().captureState)
    }

    @Test fun explicitUserCorrectionSurvivesReclassificationAndRescans() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("first"))
        dao.updateTransaction(dao.rows.values.single().copy(amountMinorUnits = -7000L, userConfirmed = true,
            categorizationSource = "USER_RULE"))
        dao.capture(tx("first", amount = -8000L))
        assertEquals(-7000L, dao.rows.values.single().amountMinorUnits)
        assertEquals("CONFIRMED", dao.rows.values.single().captureState)
        dao.reconcileExcludedEvent("first", true)
        assertEquals("CONFIRMED", dao.rows.values.single().captureState)
    }

    @Test fun legacyExactEventIsAdoptedWithoutDuplicatingExistingHistory() = runBlocking {
        val dao = MemoryDao()
        dao.insertTransaction(tx("old-hash", key = "same-key"))
        assertFalse(dao.capture(tx("new-event", key = "same-key")))
        assertEquals(1, dao.rows.size)
        assertEquals(1, dao.receipts.size)
    }

    @Test fun factoryResetClearsReceiptsAsWellAsVisibleData() = runBlocking {
        val dao = MemoryDao()
        dao.capture(tx("first"))
        dao.resetAllData()
        assertTrue(dao.rows.isEmpty())
        assertTrue(dao.receipts.isEmpty())
    }
}

private class MemoryDao : WalletDao {
    val rows = linkedMapOf<Long, TransactionEntity>()
    val receipts = linkedMapOf<String, CaptureReceiptEntity>()
    private var nextId = 1L
    override fun observeAllTransactions(): Flow<List<TransactionEntity>> = flowOf(rows.values.toList())
    override suspend fun getAllTransactionsOnce() = rows.values.filter { it.captureState != "IGNORED" }
    override fun observeAllRules(): Flow<List<MerchantRuleEntity>> = flowOf(emptyList())
    override suspend fun findReceipt(eventId: String) = receipts[eventId]
    override suspend fun insertReceipt(receipt: CaptureReceiptEntity) { receipts.putIfAbsent(receipt.eventId, receipt) }
    override suspend fun findTransaction(id: Long) = rows[id]
    override suspend fun findLegacyEvent(keyHash: String, timestampMs: Long) = rows.values.find {
        keyHash in it.notificationKeyHash.split('|') && it.timestampMs == timestampMs && !it.isSimulated
    }
    override suspend fun findCrossSourceCandidates(amountMinorUnits: Long, currency: String, fromTimestampMs: Long,
                                                   toTimestampMs: Long) = rows.values.filter {
        !it.isSimulated && it.captureState == "CONFIRMED" && it.sourceApp != "CASH" &&
            it.originalAmountMinorUnits == amountMinorUnits && it.originalCurrency == currency &&
            it.timestampMs in fromTimestampMs..toTimestampMs
    }
    override suspend fun recordAdditionalCapture(transactionId: Long, captureSource: String, notificationKeyHash: String) {
        val old = rows.getValue(transactionId)
        rows[transactionId] = old.copy(sourceApp = (old.sourceApp.split('+') + captureSource).distinct().joinToString("+"),
            notificationKeyHash = (old.notificationKeyHash.split('|') + notificationKeyHash).filter(String::isNotBlank).distinct().joinToString("|"))
    }
    override suspend fun insertTransaction(tx: TransactionEntity): Long {
        if (rows.values.any { it.dedupHash == tx.dedupHash }) return -1L
        val id = nextId++
        rows[id] = tx.copy(id = id)
        return id
    }
    override suspend fun updateTransaction(tx: TransactionEntity) { rows[tx.id] = tx }
    override suspend fun deleteTransaction(tx: TransactionEntity) { rows.remove(tx.id) }
    override suspend fun deleteAllTransactions() { rows.clear() }
    override suspend fun deleteSimulatedTransactions() { rows.values.removeAll { it.isSimulated } }
    override suspend fun deleteBySourceApp(source: String) { rows.values.removeAll { source in it.sourceApp } }
    override suspend fun deleteByTransactionType(txType: String) { rows.values.removeAll { it.txType == txType } }
    override suspend fun deleteTransactionsInRange(startMs: Long, endMs: Long) { rows.values.removeAll { it.timestampMs in startMs..endMs } }
    override suspend fun updateCategoryForMerchant(merchant: String, category: String) = Unit
    override suspend fun findRule(merchant: String): MerchantRuleEntity? = null
    override suspend fun upsertRule(rule: MerchantRuleEntity) = Unit
    override suspend fun deleteAllRules() = Unit
    override suspend fun deleteAllReceipts() { receipts.clear() }
}
