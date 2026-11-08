package com.pulse.wallet

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["dedupHash"], unique = true)]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupHash: String,
    val timestampMs: Long,
    val rawMerchant: String,
    val normalizedMerchant: String,
    val amountMinorUnits: Long, // Negative = Expense/Transfer Out, Positive = Income/Top-up
    val originalAmountMinorUnits: Long = amountMinorUnits,
    val currency: String,
    val originalCurrency: String = currency,
    val cardLast4: String?,
    val category: String,
    val categorizationSource: String,
    val encryptedMetadata: String? = null,
    val sourceApp: String = "WALLET", // WALLET, MICB, OTP, UP, CASH
    val txType: String = "EXPENSE",   // EXPENSE, TRANSFER, INCOME
    val isSimulated: Boolean = false,
    @ColumnInfo(defaultValue = "''") val notificationKeyHash: String = "",
    @ColumnInfo(defaultValue = "'CONFIRMED'") val captureState: String = "CONFIRMED",
    @ColumnInfo(defaultValue = "''") val reviewReason: String = "",
    @ColumnInfo(defaultValue = "0") val userConfirmed: Boolean = false
)

// Receipts survive ordinary deletion/ignore so reconnecting cannot resurrect an alert.
@Entity(tableName = "capture_receipts")
data class CaptureReceiptEntity(@PrimaryKey val eventId: String, val transactionId: Long)

@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val normalizedMerchant: String,
    val targetCategory: String
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM transactions WHERE captureState != 'IGNORED' ORDER BY timestampMs DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE captureState != 'IGNORED' ORDER BY timestampMs DESC")
    suspend fun getAllTransactionsOnce(): List<TransactionEntity>

    @Query("SELECT * FROM merchant_rules ORDER BY normalizedMerchant ASC")
    fun observeAllRules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM capture_receipts WHERE eventId = :eventId LIMIT 1")
    suspend fun findReceipt(eventId: String): CaptureReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: CaptureReceiptEntity)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findTransaction(id: Long): TransactionEntity?

    @Query("""
        SELECT * FROM transactions
        WHERE (notificationKeyHash = :keyHash OR instr('|' || notificationKeyHash || '|', '|' || :keyHash || '|') > 0)
          AND timestampMs = :timestampMs AND isSimulated = 0
        LIMIT 1
    """)
    suspend fun findLegacyEvent(keyHash: String, timestampMs: Long): TransactionEntity?

    @Query("""
        SELECT * FROM transactions
        WHERE isSimulated = 0 AND captureState = 'CONFIRMED' AND sourceApp != 'CASH'
          AND originalAmountMinorUnits = :amountMinorUnits AND originalCurrency = :currency
          AND timestampMs BETWEEN :fromTimestampMs AND :toTimestampMs
        ORDER BY timestampMs DESC
    """)
    suspend fun findCrossSourceCandidates(amountMinorUnits: Long, currency: String,
        fromTimestampMs: Long, toTimestampMs: Long): List<TransactionEntity>

    @Query("""
        UPDATE transactions
        SET sourceApp = CASE
                WHEN instr('+' || sourceApp || '+', '+' || :captureSource || '+') > 0 THEN sourceApp
                ELSE sourceApp || '+' || :captureSource
            END,
            notificationKeyHash = CASE
                WHEN :notificationKeyHash = '' OR notificationKeyHash = :notificationKeyHash
                    OR instr('|' || notificationKeyHash || '|', '|' || :notificationKeyHash || '|') > 0
                    THEN notificationKeyHash
                WHEN notificationKeyHash = '' THEN :notificationKeyHash
                ELSE notificationKeyHash || '|' || :notificationKeyHash
            END
        WHERE id = :transactionId
    """)
    suspend fun recordAdditionalCapture(transactionId: Long, captureSource: String, notificationKeyHash: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(tx: TransactionEntity): Long

    @Transaction
    suspend fun insertDeduplicated(tx: TransactionEntity, dedupeWindowMs: Long, eventId: String): Boolean {
        if (tx.isSimulated) return insertTransaction(tx) != -1L
        val receipt = findReceipt(eventId)
        val existing = if (receipt != null) findTransaction(receipt.transactionId)
            else tx.notificationKeyHash.takeIf(String::isNotBlank)?.let { findLegacyEvent(it, tx.timestampMs) }
        if (receipt != null || existing != null) {
            if (existing != null) {
                // An updated amount/direction is a correction to one event, never another purchase.
                if (existing.captureState != "IGNORED" && !existing.userConfirmed && existing.categorizationSource != "USER_EDIT" &&
                    (existing.originalAmountMinorUnits != tx.originalAmountMinorUnits || existing.originalCurrency != tx.originalCurrency || existing.txType != tx.txType ||
                        NotificationTextParser.merchantIdentity(existing.rawMerchant) != NotificationTextParser.merchantIdentity(tx.rawMerchant))) {
                    updateTransaction(existing.copy(captureState = "REVIEW",
                        reviewReason = "This notification changed its amount, currency, type or merchant. Check the final bank entry before confirming.",
                        encryptedMetadata = tx.encryptedMetadata))
                }
                insertReceipt(CaptureReceiptEntity(eventId, existing.id))
            }
            return false
        }
        val candidates = if (tx.captureState == "CONFIRMED") findCrossSourceCandidates(
            tx.originalAmountMinorUnits, tx.originalCurrency,
            tx.timestampMs - dedupeWindowMs, tx.timestampMs + dedupeWindowMs) else emptyList()
        val matches = candidates.filter {
            CaptureDuplicateMatcher.canMatch(tx.sourceApp, it.sourceApp, tx.rawMerchant, it.rawMerchant, tx.txType, it.txType)
        }
        if (matches.size == 1) {
            val duplicate = matches.single()
            recordAdditionalCapture(duplicate.id, tx.sourceApp, tx.notificationKeyHash)
            insertReceipt(CaptureReceiptEntity(eventId, duplicate.id))
            return false
        }
        val possibleDuplicate = candidates.any {
            CaptureDuplicateMatcher.complementarySources(tx.sourceApp, it.sourceApp) ||
                (tx.notificationKeyHash.isNotBlank() && tx.notificationKeyHash in it.notificationKeyHash.split('|') &&
                    NotificationTextParser.merchantIdentity(tx.rawMerchant) == NotificationTextParser.merchantIdentity(it.rawMerchant))
        }
        val row = if (matches.size > 1 || possibleDuplicate) tx.copy(captureState = "REVIEW",
            reviewReason = "A similar alert already exists. Check whether this is another purchase or a duplicate; ignore it if already recorded.") else tx
        val id = insertTransaction(row)
        if (id != -1L) insertReceipt(CaptureReceiptEntity(eventId, id))
        return id != -1L
    }

    @Transaction
    suspend fun reconcileExcludedEvent(eventId: String, ownTransfer: Boolean) {
        val receipt = findReceipt(eventId) ?: return
        val existing = findTransaction(receipt.transactionId) ?: return
        if (existing.userConfirmed || existing.categorizationSource == "USER_EDIT" || existing.captureState == "IGNORED") return
        updateTransaction(existing.copy(captureState = if (ownTransfer) "IGNORED" else "REVIEW",
            reviewReason = if (ownTransfer) "Own transfer" else "The notification now reports a pending, failed or cancelled payment. Verify the final bank entry."))
    }

    @Update
    suspend fun updateTransaction(tx: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(tx: TransactionEntity)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM transactions WHERE isSimulated = 1")
    suspend fun deleteSimulatedTransactions()

    @Query("DELETE FROM transactions WHERE sourceApp LIKE '%' || :source || '%'")
    suspend fun deleteBySourceApp(source: String)

    @Query("DELETE FROM transactions WHERE txType = :txType")
    suspend fun deleteByTransactionType(txType: String)

    @Query("DELETE FROM transactions WHERE timestampMs >= :startMs AND timestampMs <= :endMs")
    suspend fun deleteTransactionsInRange(startMs: Long, endMs: Long)

    @Query("UPDATE transactions SET category = :category, categorizationSource = 'USER_RULE' WHERE normalizedMerchant = :merchant")
    suspend fun updateCategoryForMerchant(merchant: String, category: String)

    @Query("SELECT * FROM merchant_rules WHERE normalizedMerchant = :merchant LIMIT 1")
    suspend fun findRule(merchant: String): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: MerchantRuleEntity)

    @Query("DELETE FROM merchant_rules")
    suspend fun deleteAllRules()

    @Query("DELETE FROM capture_receipts")
    suspend fun deleteAllReceipts()

    @Transaction
    suspend fun resetAllData() {
        deleteAllTransactions()
        deleteAllRules()
        deleteAllReceipts()
    }
}

