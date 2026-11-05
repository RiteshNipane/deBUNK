package com.debunk.core.detect

/**
 * Privacy gate: decides which apps are never scanned.
 * Defaults per spec: skip password/banking/messaging apps. The user can extend the list ("Ignore this app")
 * and, from P4, override the defaults in Settings.
 */
object AppPolicy {

    private val blockedExact = setOf(
        "com.android.systemui", // notification shade shows private message previews
        "com.android.settings",
        // payments / banking (India-first list; fragments below catch the rest)
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.google.android.apps.walletnfcrel",
    )

    /** Chat apps. Blocked for background scanning, but allowed in point mode, where the user chooses the one message to check. */
    private val messaging = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.facebook.orca", "com.discord", "com.viber.voip",
    )

    private val blockedFragments = listOf(
        "bank", "banking", "paypal", "payment", "upi",
        "bitwarden", "keepass", "lastpass", "1password", "authenticator", "passwordmanager",
        "inputmethod", "keyboard",
    )

    fun isBlocked(
        packageName: String,
        userIgnored: Set<String> = emptySet(),
        ownPackage: String? = null,
        allowMessaging: Boolean = false,
    ): Boolean {
        if (packageName == ownPackage) return true
        if (packageName in userIgnored) return true
        if (packageName in blockedExact) return true
        if (!allowMessaging && packageName in messaging) return true
        val p = packageName.lowercase()
        return blockedFragments.any { p.contains(it) }
    }
}
