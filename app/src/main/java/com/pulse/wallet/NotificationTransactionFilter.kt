package com.pulse.wallet

/** Conservative filters for bank alerts that should not reduce spending totals. */
object NotificationTransactionFilter {
    private val selfTransfer = Regex(
        "(?iu)(?:\\bbetween\\s+(?:(?:my|your|own)\\s+)?(?:own\\s+)?(?:bank\\s+)?(?:accounts?|cards?)\\b"
            + "|\\bto\\s+(?:(?:my|your)\\s+)?own\\s+(?:account|card)\\b"
            + "|\\bto\\s+(?:my|your)\\s+(?:account|card)\\b"
            + "|\\btransfer\\b.{0,30}\\bmyself\\b"
            + "|\\b(?:transfer|move)\\b.{0,60}\\bbetween\\s+(?:(?:my|your|own)\\s+)?(?:own\\s+)?(?:accounts?|cards?)\\b"
            + "|\\b(?:transfer(?:ul)?\\s+)?(?:\\u00EEntre|intre)\\s+(?:conturile|cardurile)\\s+(?:proprii|mele)\\b"
            + "|\\b(?:transferul\\s+)?conturilor\\s+proprii\\b"
            + "|\\b(?:\\u043F\\u0435\\u0440\\u0435\\u0432\\u043E\\u0434)\\b.{0,60}(?:\\u043C\\u0435\\u0436\\u0434\\u0443\\s+\\u0441\\u0432\\u043E\\u0438\\u043C\\u0438\\s+\\u0441\\u0447\\u0435\\u0442\\u0430\\u043C\\u0438"
            + "|\\u043C\\u0435\\u0436\\u0434\\u0443\\s+\\u0441\\u0432\\u043E\\u0438\\u043C\\u0438\\s+\\u043A\\u0430\\u0440\\u0442\\u0430\\u043C\\u0438"
            + "|\\u043D\\u0430\\s+\\u0441\\u0432\\u043E\\u0439\\s+\\u0441\\u0447\\u0435\\u0442\\u0430?))"
    )
    private val incomingMoney = Regex(
        "(?iu)\\b(?:received|incoming|deposit(?:ed)?|credited|top[ -]?up|alimentare|\\u00EEncasare|incasare|creditare|primit(?:\\u0103|a)?|"
            + "\\u0437\\u0430\\u0447\\u0438\\u0441\\u043B\\u0435\\u043D\\u0438\\u0435|\\u0437\\u0430\\u0447\\u0438\\u0441\\u043B\\u0435\\u043D|"
            + "\\u043F\\u043E\\u043F\\u043E\\u043B\\u043D\\u0435\\u043D\\u0438\\u0435|\\u043F\\u043E\\u043B\\u0443\\u0447\\u0435\\u043D)\\b"
    )
    private val refund = Regex("(?iu)\\b(?:refund(?:ed)?|reversal|cashback|rambursare|rambursat|restituire|returnare|\\u0432\\u043E\\u0437\\u0432\\u0440\\u0430\\u0442|\\u0432\\u043E\\u0437\\u0432\\u0440\\u0430\\u0449\\u0451\\u043D)\\b")
    private val notFinal = Regex(
        "(?iu)\\b(?:pending|pre[ -]?authori[sz]ation|declined|failed|rejected|cancelled|canceled|voided)\\b"
    )

    fun isSelfTransfer(text: String): Boolean = selfTransfer.containsMatchIn(text)
    fun isNotFinal(text: String): Boolean = notFinal.containsMatchIn(text)

    fun shouldIgnoreBankNotification(text: String): Boolean =
        isSelfTransfer(text) || notFinal.containsMatchIn(text) ||
            (incomingMoney.containsMatchIn(text) && !refund.containsMatchIn(text))
}
