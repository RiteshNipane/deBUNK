package com.debunk.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.debunk.app.data.DebunkRuntime
import com.debunk.app.detect.ScreenTextExtractor
import com.debunk.core.detect.AppPolicy
import com.debunk.core.detect.ScanThrottle

/**
 * Watches the screen and feeds visible text lines to the [DebunkRuntime.coordinator].
 *
 * Event-driven, so it costs nothing while the screen is static:
 *   event -> cheap package filter -> throttle (max 1 scan / 2 s, trailing scan kept) ->
 *   extract text -> skip if unchanged -> coordinator (local keyword/headline filter, then verify)
 *
 * Nothing is persisted here. Raw text lives only in local variables for the duration of one scan,
 * except an int hash used to detect "content unchanged".
 */
class DebunkAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val throttle = ScanThrottle(minIntervalMs = 2_000)
    private var scanScheduled = false

    private var lastPackage: String? = null
    private var lastContentHash = 0

    private val scanRunnable = Runnable {
        scanScheduled = false
        throttle.markScanned(SystemClock.uptimeMillis())
        scanNow()
    }

    /** Point mode: text of the node under the dot. Runs only when the user drops the dot; nothing is scanned otherwise. */
    private fun readTextAt(x: Int, y: Int, tolerancePx: Int): Pair<String, String>? {
        val root = rootInActiveWindow ?: return null
        try {
            val pkg = root.packageName?.toString() ?: return null
            if (isBlocked(pkg)) return null
            val text = ScreenTextExtractor.textAt(root, x, y, tolerancePx) ?: return null
            return pkg to text
        } finally {
            ScreenTextExtractor.recycleCompat(root)
        }
    }

    override fun onServiceConnected() {
        DebunkRuntime.accessibilityConnected.value = true
        DebunkRuntime.textReader = ::readTextAt
        // Bring the overlay up automatically if the user already finished onboarding.
        val notifOk = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (DebunkRuntime.prefs.consentGiven && Settings.canDrawOverlays(this) && notifOk && !DebunkRuntime.overlayRunning.value) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!DebunkRuntime.overlayRunning.value || DebunkRuntime.isPaused()) return
        if (DebunkRuntime.prefs.pointMode) return // point mode: no background scanning at all
        if (isBlocked(pkg)) return
        scheduleScan()
    }

    private fun scheduleScan() {
        if (scanScheduled) return // a (possibly delayed) scan is already queued; it will see the latest screen
        scanScheduled = true
        handler.postDelayed(scanRunnable, throttle.delayUntilNextScan(SystemClock.uptimeMillis()))
    }

    private fun scanNow() {
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            if (isBlocked(pkg)) return

            val lines = ScreenTextExtractor.extract(root)
            if (lines.isEmpty()) return

            val hash = lines.hashCode()
            if (pkg == lastPackage && hash == lastContentHash && !DebunkRuntime.coordinator.retryDue()) return // nothing changed (unless a failed check should be retried)
            lastPackage = pkg
            lastContentHash = hash

            DebunkRuntime.coordinator.onScreenText(pkg, lines)
        } finally {
            ScreenTextExtractor.recycleCompat(root)
        }
    }

    private fun isBlocked(pkg: String) =
        AppPolicy.isBlocked(
            pkg, DebunkRuntime.prefs.ignoredApps, ownPackage = packageName,
            allowMessaging = DebunkRuntime.prefs.pointMode,   // chat apps only when the user points at one message
        )

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        DebunkRuntime.accessibilityConnected.value = false
        DebunkRuntime.textReader = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DebunkRuntime.accessibilityConnected.value = false
        DebunkRuntime.textReader = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
