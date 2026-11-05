package com.debunk.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Anonymous per-device id used only to count free daily checks on the server.
 * It is a salted SHA-256 of ANDROID_ID, so the raw id never leaves the phone and it cannot be tied back to a person.
 * On Android 8+ ANDROID_ID survives uninstall/reinstall for the same signing key and only changes on a factory reset,
 * so deleting and reinstalling the app does not reset the free allowance.
 */
object DeviceId {
    @Volatile private var cached: String? = null

    @SuppressLint("HardwareIds")
    fun get(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
                .ifEmpty { "unknown" }
            val digest = MessageDigest.getInstance("SHA-256").digest("debunk:${context.packageName}:$raw".toByteArray())
            digest.joinToString("") { "%02x".format(it) }.also { cached = it }
        }
    }
}

/** Free-plan usage as last reported by the server. [limit] is 0 when the server has no limit. */
data class QuotaInfo(val used: Int, val limit: Int, val premium: Boolean) {
    val left: Int get() = (limit - used).coerceAtLeast(0)
}
