package com.pulse.wallet

/** Extracts a merchant-like label from the notification fields Android exposes. */
object NotificationTextParser {
    private val genericTitle = Regex(
        "(?i)^(?:notification|push notification|payment notification|payment alert|transaction notification|transaction alert|transfer notification|purchase notification|card purchase notification|card purchase alert|new transaction|new card transaction|transaction|card transaction|card payment|card debit|card operation|card purchase|pos|pos transaction|pos purchase|online payment|online purchase|p2p transfer|transfer|payment|purchase|operation|operation notice|micb(?: mobile banking)?|moldindconbank(?: mobile banking)?|mobiasbanca(?: mobile banking)?|otp(?: bank| mobile banking(?: moldova)?)?|google (?:wallet|pay))$"
    )
    private val currencyAmount = Regex(
        "(?i)(?:(?:\\b(?:MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b|[\\u20AC$])\\s*[+-]?\\d[\\d\\s.,']*|"
            + "[+-]?\\d[\\d\\s.,']*\\s*\\b(?:MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b)"
    )
    private val balanceAmount = Regex(
        "(?i)(?:\\b(?:available\\s+)?(?:balance|sold(?:ul)?|sold\\s+disponibil|balanta|balan\\u021Ba|"
            + "\\u0431\\u0430\\u043B\\u0430\\u043D\\u0441|\\u043E\\u0441\\u0442\\u0430\\u0442\\u043E\\u043A)\\b"
            + "\\s*[:=-]?\\s*(?:\\b(?:MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b\\s*)?\\d[\\d\\s.,']*\\s*(?:\\b(?:MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b)?|"
            + "\\b(?:MDL|EUR|USD|LEI|LEU|ЛЕЙ)\\b\\s+(?:balance|sold(?:ul)?|balanta)\\s*[:=-]?\\s*\\d[\\d\\s.,']*)"
    )
    private val cardDetails = Regex("(?i)(?:\\b(?:with|using|card)\\s+)?(?:visa|mastercard)\\s+(?:[\\u2022\\u25CF\\u00B7]{2,}|\\*{2,})?\\s*\\d{4}\\b.*$")
    private val leadingConnector = Regex("(?i)^(?:at|to|la|c\\u0103tre|catre|merchant)\\s*[:,-]?\\s*")
    private val trailingStatus = Regex("(?i)\\s+(?:successful|completed|approved|efectuat(?:\\u0103|a)?|reusit(?:\\u0103|a)?|success)$")
    private val whitespace = Regex("\\s+")

    fun removeBalanceAmounts(text: String): String = text.replace(balanceAmount, " ")

    fun merchant(title: String, body: String): String {
        val cleanTitle = title.trim()
        val source = if (cleanTitle.isNotEmpty() && !genericTitle.matches(cleanTitle)) cleanTitle else body
        val candidate = removeBalanceAmounts(source)
            .replace(currencyAmount, " ")
            .replace(cardDetails, " ")
            .replace(leadingConnector, "")
            .replace(trailingStatus, "")
            .replace(whitespace, " ")
            .trim(' ', '-', ':', '|', ',', '.')
        val normalizedCandidate = HybridCategorizer.normalize(candidate)
        return normalizedCandidate.takeIf { it.isNotBlank() && !genericTitle.matches(it) }
            ?: cleanTitle.takeIf { it.isNotBlank() }
            ?: body.trim().take(120)
    }
}
