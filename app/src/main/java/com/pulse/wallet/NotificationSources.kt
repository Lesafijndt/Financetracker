package com.pulse.wallet

import java.util.Locale

object NotificationSources {
    fun detect(packageName: String, text: String): String? = when (packageName.lowercase(Locale.ROOT)) {
        "com.solde.md", "com.solde.ro", "com.upromania.superup" -> "UP"
        "com.google.android.apps.walletnfcrel" -> "WALLET"
        "com.google.android.gms" -> if (Regex("(?i)\\b(?:google pay|google wallet)\\b").containsMatchIn(text)) "WALLET" else null
        "md.micb.mwb.prod" -> "MICB"
        "md.mobiasbanca.otpdirekt" -> "OTP"
        else -> null
    }

    // Android's event time survives reposts/rescans; postTime alone changes on notification updates.
    fun eventTime(postTime: Long, whenTime: Long): Long =
        if (whenTime > 0 && whenTime <= postTime + 60_000L) whenTime else postTime
}
