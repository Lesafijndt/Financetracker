package com.pulse.wallet

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    val amountMinorUnits: Long,
    val currency: String,
    val cardLast4: String?,
    val category: String,
    val categorizationSource: String,
    @ColumnInfo(defaultValue = "''") val captureSource: String,
    @ColumnInfo(defaultValue = "''") val notificationKeyHash: String,
    @ColumnInfo(defaultValue = "''") val captureSources: String
)

@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val normalizedMerchant: String,
    val targetCategory: String
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM transactions ORDER BY timestampMs DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(tx: TransactionEntity)

    @Query("""
        SELECT * FROM transactions
        WHERE instr('|' || CASE WHEN captureSources = '' THEN captureSource ELSE captureSources END || '|', '|' || :captureSource || '|') = 0
          AND normalizedMerchant = :normalizedMerchant
          AND amountMinorUnits = :amountMinorUnits
          AND currency = :currency
          AND timestampMs BETWEEN :fromTimestampMs AND :toTimestampMs
          AND (cardLast4 IS NULL OR :cardLast4 IS NULL OR cardLast4 = :cardLast4)
        LIMIT 1
    """)
    suspend fun findCrossSourceDuplicate(
        captureSource: String,
        normalizedMerchant: String,
        amountMinorUnits: Long,
        currency: String,
        fromTimestampMs: Long,
        toTimestampMs: Long,
        cardLast4: String?
    ): TransactionEntity?

    @Query("""
        SELECT * FROM transactions
        WHERE (notificationKeyHash = :notificationKeyHash
            OR instr('|' || notificationKeyHash || '|', '|' || :notificationKeyHash || '|') > 0)
          AND normalizedMerchant = :normalizedMerchant
          AND amountMinorUnits = :amountMinorUnits
          AND currency = :currency
          AND timestampMs BETWEEN :fromTimestampMs AND :toTimestampMs
          AND (cardLast4 IS NULL OR :cardLast4 IS NULL OR cardLast4 = :cardLast4)
        LIMIT 1
    """)
    suspend fun findSameNotificationUpdate(
        notificationKeyHash: String,
        normalizedMerchant: String,
        amountMinorUnits: Long,
        currency: String,
        fromTimestampMs: Long,
        toTimestampMs: Long,
        cardLast4: String?
    ): TransactionEntity?

    @Query("""
        UPDATE transactions
        SET captureSources = CASE
            WHEN captureSources = '' THEN captureSource || '|' || :captureSource
            ELSE captureSources || '|' || :captureSource
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

    @Transaction
    suspend fun insertWithCrossSourceDedup(tx: TransactionEntity, dedupeWindowMs: Long) {
        val duplicateFromSameNotification = tx.notificationKeyHash.takeIf { it.isNotBlank() }?.let {
            findSameNotificationUpdate(
                it,
                tx.normalizedMerchant,
                tx.amountMinorUnits,
                tx.currency,
                tx.timestampMs - dedupeWindowMs,
                tx.timestampMs + dedupeWindowMs,
                tx.cardLast4
            )
        }
        val duplicateFromOtherSource = if (duplicateFromSameNotification == null) findCrossSourceDuplicate(
            tx.captureSource,
            tx.normalizedMerchant,
            tx.amountMinorUnits,
            tx.currency,
            tx.timestampMs - dedupeWindowMs,
            tx.timestampMs + dedupeWindowMs,
            tx.cardLast4
        ) else null
        when {
            duplicateFromSameNotification != null -> Unit
            duplicateFromOtherSource != null -> recordAdditionalCapture(
                duplicateFromOtherSource.id,
                tx.captureSource,
                tx.notificationKeyHash
            )
            else -> insertTransaction(tx)
        }
    }

    @Query("UPDATE transactions SET category = :category, categorizationSource = 'USER_RULE' WHERE normalizedMerchant = :merchant")
    suspend fun updateCategoryForMerchant(merchant: String, category: String)

    @Query("SELECT * FROM merchant_rules WHERE normalizedMerchant = :merchant LIMIT 1")
    suspend fun findRule(merchant: String): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: MerchantRuleEntity)
}

@Database(entities = [TransactionEntity::class, MerchantRuleEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): WalletDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN captureSource TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transactions ADD COLUMN notificationKeyHash TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN captureSources TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                System.loadLibrary("sqlcipher")
                val passphrase = PassphraseManager.getOrCreateDatabasePassphrase(context)
                val factory = SupportOpenHelperFactory(passphrase)
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pulse_wallet_encrypted.db")
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

object PassphraseManager {
    private const val KEY_ALIAS = "pulse_sqlcipher_master_key"
    private const val PREFS_NAME = "pulse_secure_prefs"

    fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val existingCiphertext = prefs.getString("enc_pass", null)
        val existingIv = prefs.getString("enc_iv", null)

        if (existingCiphertext != null && existingIv != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.getDecoder().decode(existingIv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            return cipher.doFinal(Base64.getDecoder().decode(existingCiphertext))
        } else {
            val rawPass = UUID.randomUUID().toString().toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encrypted = cipher.doFinal(rawPass)
            prefs.edit()
                .putString("enc_pass", Base64.getEncoder().encodeToString(encrypted))
                .putString("enc_iv", Base64.getEncoder().encodeToString(cipher.iv))
                .apply()
            return rawPass
        }
    }
}

object HybridCategorizer {
    val CATEGORIES = listOf(
        "Groceries", "Dining & Coffee", "Transport & Fuel",
        "Subscriptions", "Shopping", "Utilities", "Health", "Other"
    )

    private val posPrefixRegex = Regex("""^(SQ\s?\*|SUMUP\s?\*|PAYPAL\s?\*|GOOGLE\s?\*|TST\*\s?)""", RegexOption.IGNORE_CASE)
    private val trailingNoiseRegex = Regex("""(\s+#\d+|\s+\d{4,})$""")
    private val transactionPrefixRegex = Regex(
        """^(?:(?:POS|CARD|ONLINE)\s+)*(?:(?:CARD\s+)?(?:PURCHASE|PAYMENT|TRANSACTION)|P2P\s+TRANSFER|TRANSFER|PAID|SPENT|TRX|PLATA|TRANZACTIE|TRANZAC\u021BIE|OPERATION|OPERATIUNE)(?:\s+(?:AT|TO|BETWEEN|FROM|LA|CATRE|C\u0102TRE))?\s*[:#-]?\s*""",
        RegexOption.IGNORE_CASE
    )
    private val cardSuffixRegex = Regex("""\s+(?:(?:VISA|MASTERCARD)\s+)?(?:\*{2,}\s*)?\d{4}$""", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        val cleaned = raw.trim()
            .replace(posPrefixRegex, "")
            .replace(transactionPrefixRegex, "")
            .replace(cardSuffixRegex, "")
            .replace(trailingNoiseRegex, "")
        return cleaned
            .lowercase()
            .split(Regex("\\s+"))
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .ifBlank { raw.trim() }
    }

    suspend fun categorize(dao: WalletDao, rawMerchant: String): Triple<String, String, String> {
        val normalized = normalize(rawMerchant)
        val rule = dao.findRule(normalized)
        if (rule != null) {
            return Triple(normalized, rule.targetCategory, "USER_RULE")
        }

        val lower = normalized.lowercase()
        val predicted = when {
            listOf("kaufland", "linella", "nr1", "metro", "market", "grocery", "lidl", "carrefour").any { it in lower } -> "Groceries"
            listOf("coffee", "cafe", "roasters", "starbucks", "mcdonald", "kfc", "pizza", "rest", "bistro", "andy").any { it in lower } -> "Dining & Coffee"
            listOf("rompetrol", "lukoil", "petrom", "bemol", "uber", "bolt", "yandex", "fuel", "gas", "parking").any { it in lower } -> "Transport & Fuel"
            listOf("netflix", "spotify", "youtube", "google one", "steam", "openai", "icloud").any { it in lower } -> "Subscriptions"
            listOf("orange", "moldcell", "moldtelecom", "premier energy", "water", "electric").any { it in lower } -> "Utilities"
            listOf("farmacia", "hippocrates", "felicia", "clinic", "dent", "gym", "fitness").any { it in lower } -> "Health"
            listOf("zara", "amazon", "aliexpress", "darwin", "ultra", "enter", "shop").any { it in lower } -> "Shopping"
            else -> "Other"
        }
        return Triple(normalized, predicted, "KEYWORD_RULE")
    }
}

class WalletNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val TRANSACTION_SOURCES = mapOf(
            "com.google.android.apps.walletnfcrel" to "google_wallet",
            "md.micb.mwb.prod" to "micb",
            "md.mobiasbanca.otpdirekt" to "otp"
        )
        private const val CROSS_SOURCE_DEDUPE_WINDOW_MS = 180_000L
        private val LAST4_REGEX = Regex("""(?:[\u2022\u25CF\u00B7]{4}|\*{4}|ending in)\s?(\d{4})""", RegexOption.IGNORE_CASE)

        suspend fun processAndStore(
            context: Context,
            rawTitle: String,
            rawText: String,
            timestampMs: Long,
            captureSource: String,
            notificationKey: String? = null
        ) {
            val combined = "$rawTitle $rawText"
            if (NotificationTransactionFilter.isSelfTransfer(combined)) return
            val parsedMoney = MoneyAmountParser.parse(NotificationTextParser.removeBalanceAmounts(combined)) ?: return
            val amountMinor = parsedMoney.amountMinorUnits
            if (amountMinor == 0L) return
            val last4 = LAST4_REGEX.find(combined)?.groupValues?.get(1)

            val db = AppDatabase.get(context)
            val dao = db.dao()
            val (normalized, category, source) = HybridCategorizer.categorize(dao, rawTitle)

            val dedupHash = MessageDigest.getInstance("SHA-256")
                .digest("$captureSource|${notificationKey.orEmpty()}|$timestampMs|$normalized|$amountMinor|${parsedMoney.currency}|${last4.orEmpty()}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val notificationKeyHash = notificationKey?.let {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.orEmpty()

            dao.insertWithCrossSourceDedup(
                TransactionEntity(
                    dedupHash = dedupHash,
                    timestampMs = timestampMs,
                    rawMerchant = rawTitle,
                    normalizedMerchant = normalized,
                    amountMinorUnits = amountMinor,
                    currency = parsedMoney.currency,
                    cardLast4 = last4,
                    category = category,
                    categorizationSource = source,
                    captureSource = captureSource,
                    notificationKeyHash = notificationKeyHash,
                    captureSources = captureSource
                ),
                CROSS_SOURCE_DEDUPE_WINDOW_MS
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val captureSource = TRANSACTION_SOURCES[sbn.packageName] ?: return

        val extras = notification.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim().orEmpty()
        val textParts = buildList {
            listOf(
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT
            ).forEach { key ->
                extras.getCharSequence(key)?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
                line?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct()
        val text = textParts.joinToString(" ")
        val merchant = NotificationTextParser.merchant(title, text)
        if (merchant.isBlank() || (title.isBlank() && text.isBlank())) return
        val combined = "$merchant $text"
        if (NotificationTransactionFilter.isNotFinal(combined)) return
        if (captureSource != "google_wallet" && NotificationTransactionFilter.shouldIgnoreBankNotification(combined)) return

        scope.launch {
            processAndStore(applicationContext, merchant, text, sbn.postTime, captureSource, sbn.key)
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PulseWalletDashboard(dao = db.dao())
                }
            }
        }
    }
}

@Composable
fun PulseWalletDashboard(dao: WalletDao) {
    val context = LocalContext.current
    val transactions by dao.observeTransactions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var hasNotificationAccess by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var selectedTxForRule by remember { mutableStateOf<TransactionEntity?>(null) }

    val currentMonth = remember(transactions) { YearMonth.now() }
    val spentByCurrency = remember(transactions, currentMonth) {
        val monthTransactions = transactions.filter { tx ->
            YearMonth.from(Instant.ofEpochMilli(tx.timestampMs).atZone(ZoneId.systemDefault())) == currentMonth
        }
        MoneyAmountParser.supportedCurrencies.associateWith { currency ->
            monthTransactions.asSequence()
                .filter { it.currency == currency }
                .sumOf { -it.amountMinorUnits }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pulse Wallet", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("On-Device Encrypted Spending Tracker", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(modifier = Modifier.height(12.dp))

        if (!hasNotificationAccess) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2525)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Transaction Notification Capture Disabled", fontWeight = FontWeight.Bold)
                    Text(
                        "Allow notification access and turn on transaction push notifications in Wallet, MICB, and OTP. Captured alerts can be missed or misread; check totals against bank statements.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text("Enable in Android Settings")
                    }
                    TextButton(onClick = { hasNotificationAccess = isNotificationListenerEnabled(context) }) {
                        Text("Refresh Status")
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            "Automatic capture reads visible Wallet, MICB, and OTP transaction alerts. It can miss alerts when notifications are off or do not show a supported amount.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("SPENT THIS MONTH", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                MoneyAmountParser.supportedCurrencies.forEach { currency ->
                    Text(
                        text = String.format(Locale.US, "%s %.2f", currency, spentByCurrency.getValue(currency) / 100.0),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "MDL, EUR, and USD are summed separately. No currency conversion is applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Spacer(modifier = Modifier.height(16.dp))
        Text("TRANSACTIONS (Tap item to train a category rule)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transactions, key = { it.id }) { tx ->
                val dateStr = remember(tx.timestampMs) {
                    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(tx.timestampMs))
                }
                val sourceIds = tx.captureSources.split('|').filter(String::isNotBlank)
                    .ifEmpty { listOf(tx.captureSource).filter(String::isNotBlank) }
                val sourceLabel = sourceIds.mapNotNull { source ->
                    when (source) {
                        "google_wallet" -> "Wallet"
                        "micb" -> "MICB"
                        "otp" -> "OTP"
                        else -> null
                    }
                }.distinct().joinToString(" + ").ifBlank { "Older entry" }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTxForRule = tx }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.normalizedMerchant, fontWeight = FontWeight.Bold)
                            Text(
                                "${tx.category} - $sourceLabel - $dateStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%s %.2f", tx.currency, tx.amountMinorUnits / 100.0),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF8A80)
                        )
                    }
                }
            }
        }
    }

    selectedTxForRule?.let { tx ->
        AlertDialog(
            onDismissRequest = { selectedTxForRule = null },
            title = { Text("Categorize ${tx.normalizedMerchant}") },
            text = {
                Column {
                    Text("Select a category to create an automatic rule for future transactions:")
                    Spacer(modifier = Modifier.height(8.dp))
                    HybridCategorizer.CATEGORIES.forEach { category ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    dao.upsertRule(MerchantRuleEntity(tx.normalizedMerchant, category))
                                    dao.updateCategoryForMerchant(tx.normalizedMerchant, category)
                                }
                                selectedTxForRule = null
                            }
                        ) {
                            Text(category)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTxForRule = null }) { Text("Cancel") }
            }
        )
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, WalletNotificationListener::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}
