package com.pulse.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTransactionFilterTest {
    @Test
    fun ignoresExplicitOwnAccountTransfersInSupportedLocales() {
        assertTrue(NotificationTransactionFilter.isSelfTransfer("Transfer between my own accounts: 150 MDL"))
        assertTrue(NotificationTransactionFilter.isSelfTransfer("Transfer to own card: 100 MDL"))
        assertTrue(NotificationTransactionFilter.isSelfTransfer("Money sent to my account: 100 MDL"))
        assertTrue(NotificationTransactionFilter.isSelfTransfer("Transfer \u00EEntre conturile proprii: 100 MDL"))
        assertTrue(NotificationTransactionFilter.isSelfTransfer("\u041F\u0435\u0440\u0435\u0432\u043E\u0434 \u043C\u0435\u0436\u0434\u0443 \u0441\u0432\u043E\u0438\u043C\u0438 \u0441\u0447\u0435\u0442\u0430\u043C\u0438: 50 MDL"))
    }

    @Test
    fun keepsTransfersToOtherPeople() {
        assertFalse(NotificationTransactionFilter.isSelfTransfer("Transfer to Ana: 150 MDL"))
    }

    @Test
    fun ignoresIncomingBankMoneyButKeepsRefunds() {
        assertTrue(NotificationTransactionFilter.shouldIgnoreBankNotification("Received salary: 10,000 MDL"))
        assertFalse(NotificationTransactionFilter.shouldIgnoreBankNotification("Refund received: EUR 12.00"))
    }

    @Test
    fun ignoresPendingAndDeclinedAlerts() {
        assertTrue(NotificationTransactionFilter.isNotFinal("Pending card payment EUR 20.00"))
        assertTrue(NotificationTransactionFilter.isNotFinal("Payment declined EUR 20.00"))
        assertFalse(NotificationTransactionFilter.isNotFinal("Payment authorized EUR 20.00"))
    }
}
