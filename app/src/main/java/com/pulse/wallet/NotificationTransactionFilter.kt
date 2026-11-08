package com.pulse.wallet

import java.text.Normalizer
import java.util.Locale

/** Conservative multilingual signals. Absence of a self-transfer phrase is not proof of a purchase. */
object NotificationTransactionFilter {
    private fun folded(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    private val selfTransfer = Regex(
        "\\b(?:between\\s+(?:(?:my|your)\\s+)?own\\s+(?:bank\\s+)?(?:accounts?|cards?)|between\\s+(?:my|your)\\s+(?:accounts?|cards?)|to\\s+(?:(?:my|your)\\s+)?own\\s+(?:account|card)|to\\s+(?:my|your)\\s+(?:account|card)|transfer\\b.{0,30}\\bmyself)\\b" +
            "|\\b(?:intre\\s+(?:conturi(?:le)?|carduri(?:le)?)\\s+(?:proprii|mele)|conturilor\\s+proprii|(?:catre|in|pe)\\s+(?:contul|cardul)\\s+(?:propriu|meu))\\b" +
            "|перевод.{0,60}(?:между\\s+(?:своими|моими)\\s+(?:счетами|картами)|на\\s+(?:свои|свою|мои|мою|собственныи|собственную)\\s+(?:счет|карту))"
    )
    private val incoming = Regex("\\b(?:received|incoming|deposit(?:ed)?|credited|top[ -]?up|alimentare|suplinire|incasare|creditare|primit[аa]?|salariu|salary|зачислен(?:ие|о)?|пополнен(?:ие|о)|получен(?:о)?)\\b")
    private val refund = Regex("\\b(?:refund(?:ed)?|reversal|reversed|cashback|rambursare|rambursat|restituire|returnare|возврат|возвращен)\\b")
    private val notFinal = Regex("\\b(?:pending|pre[ -]?authori[sz]ation|declined|failed|rejected|cancelled|canceled|voided|in asteptare|refuzat[аa]?|respins[аa]?|esuat[аa]?|anulat[аa]?|неуспешн[ао]|отклонен[ао]?|отменен[ао]?|ожидает|ожидание|недостаточно средств)\\b")
    private val transfer = Regex("\\b(?:transfer(?:ul)?|p2p|mia|card[ -]to[ -]card|sent to|money sent|перевод|перевели)\\b")
    private val payment = Regex("\\b(?:paid|payment|purchase|spent|debited|debit|pos|plata|achitare|achitat|cumparatura|cumparaturi|debitare|оплата|оплачен[ао]?|покупка|списан[ао]?)\\b")
    private val nonTransaction = Regex("\\b(?:verification code|security code|one[ -]time|otp code|cod de confirmare|cod de verificare|код подтверждения|код проверки|offer|promotion|discount|oferta|reducere|предложение|скидка|daily summary|weekly summary|monthly summary|statement available|spending limit|лимит|лимита)\\b")

    fun isSelfTransfer(text: String) = selfTransfer.containsMatchIn(folded(text)) && !isRefund(text)
    fun isNotFinal(text: String) = notFinal.containsMatchIn(folded(text))
    fun isIncoming(text: String) = incoming.containsMatchIn(folded(text))
    fun isRefund(text: String) = refund.containsMatchIn(folded(text))
    fun isTransfer(text: String) = transfer.containsMatchIn(folded(text))
    fun isPayment(text: String) = payment.containsMatchIn(folded(text))
    fun isNonTransactional(text: String) = nonTransaction.containsMatchIn(folded(text))
    fun shouldIgnoreBankNotification(text: String) = isSelfTransfer(text) || isNotFinal(text) || (isIncoming(text) && !isRefund(text))
}
