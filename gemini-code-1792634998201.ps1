$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# 1. Create folder structure in current working directory
New-Item -ItemType Directory -Force -Path "./.github/workflows", "./app/src/main/java/com/pulse/wallet" | Out-Null

# 2. Write .github/workflows/build-apk.yml
$workflow = @'
name: Build Android APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Build Debug APK
        run: gradle assembleDebug --no-daemon

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: pulse-wallet-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
'@
[System.IO.File]::WriteAllText("$PWD/.github/workflows/build-apk.yml", $workflow, $utf8NoBom)

# 3. Write gradle.properties
$gradleProps = @'
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
'@
[System.IO.File]::WriteAllText("$PWD/gradle.properties", $gradleProps, $utf8NoBom)

# 4. Write settings.gradle.kts
$settingsGradle = @'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PulseWallet"
include(":app")
'@
[System.IO.File]::WriteAllText("$PWD/settings.gradle.kts", $settingsGradle, $utf8NoBom)

# 5. Write root build.gradle.kts
$rootBuildGradle = @'
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
'@
[System.IO.File]::WriteAllText("$PWD/build.gradle.kts", $rootBuildGradle, $utf8NoBom)

# 6. Write app/build.gradle.kts
$appBuildGradle = @'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pulse.wallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulse.wallet"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
}
'@
[System.IO.File]::WriteAllText("$PWD/app/build.gradle.kts", $appBuildGradle, $utf8NoBom)

# 7. Write app/src/main/AndroidManifest.xml
$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="Pulse Wallet"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".WalletNotificationListener"
            android:exported="true"
            android:label="Pulse Wallet Live Capture"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

    </application>
</manifest>
'@
[System.IO.File]::WriteAllText("$PWD/app/src/main/AndroidManifest.xml", $manifest, $utf8NoBom)

# 8. Write app/src/main/java/com/pulse/wallet/MainActivity.kt
$mainActivity = @'
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
import androidx.lifecycle.lifecycleScope
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
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
    val categorizationSource: String
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

    @Query("UPDATE transactions SET category = :category, categorizationSource = 'USER_RULE' WHERE normalizedMerchant = :merchant")
    suspend fun updateCategoryForMerchant(merchant: String, category: String)

    @Query("SELECT * FROM merchant_rules WHERE normalizedMerchant = :merchant LIMIT 1")
    suspend fun findRule(merchant: String): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: MerchantRuleEntity)
}

@Database(entities = [TransactionEntity::class, MerchantRuleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): WalletDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                System.loadLibrary("sqlcipher")
                val passphrase = PassphraseManager.getOrCreateDatabasePassphrase(context)
                val factory = SupportOpenHelperFactory(passphrase)
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pulse_wallet_encrypted.db")
                    .openHelperFactory(factory)
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

    fun normalize(raw: String): String {
        return raw.trim()
            .replace(posPrefixRegex, "")
            .replace(trailingNoiseRegex, "")
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
        return Triple(normalized, predicted, "ON_DEVICE_ML")
    }
}

class WalletNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val WALLET_PACKAGES = setOf(
            "com.google.android.apps.walletnfcrel",
            "com.google.android.gms"
        )
        private val AMOUNT_REGEX = Regex("""([€$£]|USD|EUR|MDL|RON)\s?(\d+[.,]\d{2})""", RegexOption.IGNORE_CASE)
        private val LAST4_REGEX = Regex("""(?:••••|\*{4}|ending in)\s?(\d{4})""", RegexOption.IGNORE_CASE)

        suspend fun processAndStore(
            context: Context,
            rawTitle: String,
            rawText: String,
            timestampMs: Long
        ) {
            val combined = "$rawTitle $rawText"
            val match = AMOUNT_REGEX.find(combined) ?: return
            val currency = when (val sym = match.groupValues[1].uppercase()) {
                "€" -> "EUR"
                "$" -> "USD"
                "£" -> "GBP"
                else -> sym
            }
            val amountMajor = match.groupValues[2].replace(",", ".").toDoubleOrNull() ?: return
            val amountMinor = (amountMajor * 100).toLong() * -1L
            val last4 = LAST4_REGEX.find(combined)?.groupValues?.get(1)

            val db = AppDatabase.get(context)
            val dao = db.dao()
            val (normalized, category, source) = HybridCategorizer.categorize(dao, rawTitle)

            val bucket = timestampMs / 300_000L
            val dedupHash = MessageDigest.getInstance("SHA-256")
                .digest("$bucket|$normalized|$amountMinor".toByteArray())
                .joinToString("") { "%02x".format(it) }

            dao.insertTransaction(
                TransactionEntity(
                    dedupHash = dedupHash,
                    timestampMs = timestampMs,
                    rawMerchant = rawTitle,
                    normalizedMerchant = normalized,
                    amountMinorUnits = amountMinor,
                    currency = currency,
                    cardLast4 = last4,
                    category = category,
                    categorizationSource = source
                )
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName !in WALLET_PACKAGES) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return

        scope.launch {
            processAndStore(applicationContext, title, text, sbn.postTime)
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
                    PulseWalletDashboard(
                        dao = db.dao(),
                        onSimulateTap = { merchant, text ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                WalletNotificationListener.processAndStore(
                                    this@MainActivity,
                                    merchant,
                                    text,
                                    System.currentTimeMillis()
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PulseWalletDashboard(
    dao: WalletDao,
    onSimulateTap: (String, String) -> Unit
) {
    val context = LocalContext.current
    val transactions by dao.observeTransactions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var hasNotificationAccess by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var monthlyBudgetMinor by remember { mutableLongStateOf(1500_00L) }
    var selectedTxForRule by remember { mutableStateOf<TransactionEntity?>(null) }

    val totalSpentMinor = transactions.sumOf { kotlin.math.abs(it.amountMinorUnits) }
    val remainingMinor = (monthlyBudgetMinor - totalSpentMinor).coerceAtLeast(0L)
    val progress = (totalSpentMinor.toFloat() / monthlyBudgetMinor.toFloat()).coerceIn(0f, 1f)

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
                    Text("Google Wallet Auto-Capture Disabled", fontWeight = FontWeight.Bold)
                    Text(
                        "Grant Notification Listener access so Pulse can capture Google Wallet NFC taps in real time.",
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("REMAINING MONTHLY BUDGET", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    text = String.format(Locale.US, "EUR %.2f", remainingMinor / 100.0),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (progress >= 0.85f) Color(0xFFFF6B6B) else Color(0xFF4ECDC4)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "Spent: EUR %.2f / EUR %.2f (%.0f%%)",
                        totalSpentMinor / 100.0,
                        monthlyBudgetMinor / 100.0,
                        progress * 100
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val samples = listOf(
                        "SUMUP *COFFEE LAB #04" to "€4.50 with Mastercard •••• 8492",
                        "KAUFLAND CHISINAU" to "€38.20 with Visa •••• 4110",
                        "ROMPETROL #12" to "€52.00 with Mastercard •••• 8492",
                        "GOOGLE *YouTubePremium" to "€11.99 with Mastercard •••• 8492"
                    )
                    val pick = samples.random()
                    onSimulateTap(pick.first, pick.second)
                }
            ) {
                Text("Simulate Wallet Tap")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("LIVE TRANSACTIONS (Tap item to train rule)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transactions, key = { it.id }) { tx ->
                val dateStr = remember(tx.timestampMs) {
                    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(tx.timestampMs))
                }
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
                                "${tx.category} • ${tx.categorizationSource} • $dateStr",
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
'@
[System.IO.File]::WriteAllText("$PWD/app/src/main/java/com/pulse/wallet/MainActivity.kt", $mainActivity, $utf8NoBom)

# 9. Commit and force-push to GitHub
git init
git branch -M main
git remote remove origin 2>$null
git remote add origin https://github.com/Lesafijndt/Financetracker.git
git add .
git commit -m "Complete Android Gradle project for APK build"
git push -u origin main --force