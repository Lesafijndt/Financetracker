package com.pulse.wallet

import java.text.Normalizer
import java.util.Locale

/** Pure helpers shared by live capture and rescans. */
object NotificationTextParser {
    private val genericTitle = Regex(
        "(?iu)^(?:notification|push notification|payment notification|payment alert|transaction notification|transaction alert|transfer notification|purchase notification|card purchase notification|card purchase alert|new transaction|new card transaction|transaction|card transaction|card payment|card debit|card operation|card purchase|pos|pos transaction|pos purchase|online payment|online purchase|p2p transfer|transfer|payment|purchase|operation|operation notice|micb(?: mobile banking)?|moldindconbank(?: mobile banking)?|mobiasbanca(?: mobile banking)?|otp(?: bank| mobile banking(?: moldova)?)?|google (?:wallet|pay)|up mobil(?: moldova)?|up moldova|up dejun|plata|tranzacție|tranzactie|notificare|операция|покупка|оплата|уведомление)$"
    )
    private val balanceLabel = "(?:available\\s+balance|balance|sold(?:ul)?(?:\\s+disponibil)?|disponibil|balan[țt]a|баланс|остаток|доступно)"
    private val balanceBefore = Regex("(?iu)\\b$balanceLabel\\s*[:=–-]?\\s*$")
    private val balanceAfter = Regex("(?iu)^\\s*[(]?$balanceLabel\\b")
    private val merchantMarker = Regex("(?iu)\\b(?:at|la|comerciant|merchant|торговая точка|магазин)\\s*[:,-]?\\s+(.+)")
    private val transactionPrefix = Regex("(?iu)^(?:(?:card|pos|online)\\s+)*(?:purchase|payment|paid|spent|plata|plată|achitare|cumparatura|cumpărătură|оплата|покупка)\\s*[:=-]?\\s*")
    private val metadata = Regex("(?iu)(?:[,;\\n]\\s*|\\s+)(?:using|with|cu cardul|card(?:ul)?|карта|карт[аеы]|visa|mastercard|sold|balance|disponibil|data|date|time|ora|rrn|reference|ref|id|успешно)\\b.*$")
    private val trailingStatus = Regex("(?iu)\\s+(?:successful|completed|approved|efectuat[ăa]?|reu[șs]it[ăa]?|success)$")
    private val invalidMerchant = Regex("(?iu)^(?:card|visa|mastercard|account|cont|карта|счет|счёт|sold|balance|available|disponibil|received|transfer|pending|refund|amount|suma)\\b|^[\\d\\W]+$")

    /** Remove only currency amounts directly labelled as balances, whichever comes first. */
    fun removeBalanceAmounts(text: String): String {
        val ranges = MoneyAmountParser.candidates(text).filter { candidate ->
            balanceBefore.containsMatchIn(text.substring(0, candidate.range.first).takeLast(80)) ||
                balanceAfter.containsMatchIn(text.substring(candidate.range.last + 1))
        }.map { it.range }
        return ranges.asReversed().fold(text) { current, range -> current.replaceRange(range, " ") }
    }

    fun merchantOrNull(title: String, body: String): String? {
        val cleanTitle = title.trim()
        val source = if (cleanTitle.isNotBlank() && !genericTitle.matches(cleanTitle)) cleanTitle else body
        val withoutBalance = removeBalanceAmounts(source)
        val marked = merchantMarker.find(withoutBalance)?.groupValues?.get(1)
        var candidate = marked ?: withoutBalance.replace(transactionPrefix, "")
        val amountStart = MoneyAmountParser.candidates(candidate).firstOrNull()?.range?.first
        if (amountStart != null) candidate = candidate.substring(0, amountStart)
        candidate = candidate.replace(metadata, "").replace(trailingStatus, "")
            .replace(Regex("\\s+"), " ").trim(' ', '-', ':', '|', ',', '.', ';')
        if (candidate.isBlank() || genericTitle.matches(candidate) || invalidMerchant.containsMatchIn(candidate)) return null
        // Body text without an explicit merchant marker or purchase prefix is not a merchant name.
        if (source == body && marked == null && !transactionPrefix.containsMatchIn(withoutBalance)) return null
        return candidate.lowercase(Locale.ROOT).split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    fun merchant(title: String, body: String): String = merchantOrNull(title, body) ?: "Unidentified transaction"

    fun merchantIdentity(merchant: String): String = Normalizer.normalize(merchant, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Expanded text supersedes collapsed text; subtext/account labels are not transaction bodies. */
    fun body(bigText: String?, text: String?, lines: List<String>): String =
        bigText?.trim()?.takeIf(String::isNotBlank)
            ?: text?.trim()?.takeIf(String::isNotBlank)
            ?: lines.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n")
}
