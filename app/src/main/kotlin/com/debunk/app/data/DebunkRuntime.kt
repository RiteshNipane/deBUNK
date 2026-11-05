package com.debunk.app.data

import android.content.Context
import com.debunk.app.BuildConfig
import com.debunk.core.detect.DetectionConfig
import com.debunk.core.detect.NewsDetector
import com.debunk.core.session.ScanCoordinator
import com.debunk.core.verify.MockVerifier
import com.debunk.core.verify.Verifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide wiring. The AccessibilityService (producer of screen text) and the OverlayService
 * (renderer of verdicts) are separate Android components in the same process; they meet here.
 */
object DebunkRuntime {
    lateinit var prefs: Prefs
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** True when a backend URL is configured (local.properties). Otherwise the app stays fully offline. */
    val usesBackend: Boolean = BuildConfig.BACKEND_URL.isNotBlank()

    /** Backend when configured, offline mock verdicts otherwise. */
    private lateinit var appContext: Context

    /** Free-plan usage as last reported by the server; null until known or when there is no limit. */
    val quota = MutableStateFlow<QuotaInfo?>(null)

    private val httpVerifier: HttpVerifier? = if (usesBackend) HttpVerifier(
        BuildConfig.BACKEND_URL, BuildConfig.BACKEND_KEY,
        deviceId = { DeviceId.get(appContext) },
        onQuota = { q -> quota.value = if (q.limit > 0) q else null },
    ) else null

    private val verifier: Verifier = httpVerifier ?: MockVerifier()

    /** Device id sent to the server; shown on the premium screen so a purchase can be matched to this phone. */
    fun deviceId(): String = DeviceId.get(appContext)

    suspend fun refreshQuota() {
        httpVerifier?.fetchQuota()?.let { quota.value = it }
    }

    /** True while OverlayService is alive. The accessibility service skips all work when false. */
    val overlayRunning = MutableStateFlow(false)

    /** True while the system has our AccessibilityService bound. */
    val accessibilityConnected = MutableStateFlow(false)

    /** Set by the accessibility service while it is bound: (x, y, tolerancePx) -> (package, text under that point) or null. */
    @Volatile var textReader: ((Int, Int, Int) -> Pair<String, String>?)? = null

    lateinit var coordinator: ScanCoordinator
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(context.applicationContext)
        coordinator = ScanCoordinator(
            scope = scope,
            verifier = verifier,
            // Re-read settings on every scan so Settings changes (P4) apply immediately.
            detector = { NewsDetector(DetectionConfig(sensitivity = prefs.sensitivity)) },
            isPaused = { prefs.paused.value },
            onIgnoreApp = { prefs.ignoreApp(it) },
            alwaysShowDot = prefs.pointMode, // point mode: the dot is always there; the user places it on a line
            onVerifyError = { e ->
                android.widget.Toast.makeText(
                    context.applicationContext,
                    "Check failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    fun isPaused() = prefs.paused.value

    fun setPaused(paused: Boolean) {
        prefs.setPaused(paused)
        if (paused) coordinator.reset() // hides the overlay, cancels in-flight verification
        else if (prefs.pointMode) coordinator.showIdleDot()
    }
}
