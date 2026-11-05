package com.debunk.core.session

import com.debunk.core.detect.NewsDetector
import com.debunk.core.domain.ClaimKey
import com.debunk.core.domain.DotState
import com.debunk.core.domain.OverlayUiState
import com.debunk.core.domain.PanelMode
import com.debunk.core.domain.Verdict
import com.debunk.core.domain.VerdictResult
import com.debunk.core.domain.toDotState
import com.debunk.core.verify.QuotaExceededException
import com.debunk.core.verify.Verifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The brain between "screen text arrived" and "what the overlay shows".
 *
 *  screen lines -> NewsDetector (local) -> de-dupe -> Verifier -> OverlayUiState
 *
 * Owns the timers from the spec: the box auto-collapses after [autoCollapseMs]; the dot settles
 * back to grey idle once news has been off-screen for [idleAfterMs].
 * Holds only the claim + verdict in memory, never the raw screen text.
 */
class ScanCoordinator(
    private val scope: CoroutineScope,
    private val verifier: Verifier,
    private val detector: () -> NewsDetector,
    private val isPaused: () -> Boolean,
    private val onIgnoreApp: (String) -> Unit,
    private val autoCollapseMs: Long = 8_000,
    private val idleAfterMs: Long = 15_000,
    /** A shown verdict stays at least this long before a different headline may replace it (stops flicker while scrolling). */
    private val minDwellMs: Long = 6_000,
    /** After a failed check, wait this long before asking the screen scanner to retry the same text. */
    private val retryCooldownMs: Long = 10_000,
    /** Point mode: the grey dot is on screen the whole time deBUNK is active, not only after a detection. */
    private val alwaysShowDot: Boolean = false,
    /** Called when a check fails (network down, HTTP 401/5xx, timeout). The app shows a short toast; the dot goes grey. */
    private val onVerifyError: (Throwable) -> Unit = {},
) {
    private val _ui = MutableStateFlow(
        if (alwaysShowDot) OverlayUiState(visible = true, dot = DotState.IDLE) else OverlayUiState(),
    )
    val ui: StateFlow<OverlayUiState> = _ui.asStateFlow()

    /** Package of the app currently being scanned (needed for "Ignore this app"). */
    var currentPackage: String? = null
        private set

    private var lastClaimKey: String? = null
    private var verifyJob: Job? = null
    private var collapseJob: Job? = null
    private var idleJob: Job? = null
    private var dwellJob: Job? = null
    private var pendingClaim: String? = null

    @Volatile private var failed = false
    @Volatile private var retryArmed = false

    /** True when the last check failed and the cooldown is over: the scanner should re-send the current screen even if unchanged. */
    fun retryDue(): Boolean = failed && retryArmed

    /** Entry point from the accessibility scan (or the debug "simulate" button). */
    fun onScreenText(pkg: String, lines: List<String>) {
        if (isPaused()) return
        currentPackage = pkg

        val detection = detector().detect(lines)
        if (detection == null) {
            scheduleIdleSettle()
            return
        }
        idleJob?.cancel()
        idleJob = null

        val key = ClaimKey.of(detection.candidate)
        if (key == lastClaimKey) { pendingClaim = null; return } // same story still on screen
        if (dwellJob?.isActive == true) {
            pendingClaim = detection.candidate // a verdict is on display: queue the newer headline until the dwell ends
            return
        }
        lastClaimKey = key
        startVerification(detection.candidate)
    }

    /** Point mode: the user dropped the dot on this text. Skips the detector and de-dupe; always checks it. */
    fun verifyPointed(pkg: String, text: String) {
        if (isPaused()) return
        currentPackage = pkg
        idleJob?.cancel(); idleJob = null
        dwellJob?.cancel(); dwellJob = null
        pendingClaim = null
        val claim = text.trim().take(300)
        lastClaimKey = ClaimKey.of(claim)
        startVerification(claim)
    }

    private fun startVerification(claim: String) {
        verifyJob?.cancel()
        collapseJob?.cancel()
        _ui.value = OverlayUiState(visible = true, dot = DotState.VERIFYING, result = null, panel = PanelMode.NONE)

        verifyJob = scope.launch {
            var error: Exception? = null
            val result = try {
                verifier.verify(claim)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                if (e !is QuotaExceededException) onVerifyError(e)
                null
            }
            if (error is QuotaExceededException) {
                // Free allowance used up: not an error. Show the upgrade prompt and let the same line be checked again later.
                lastClaimKey = null
                failed = false
                _ui.update { it.copy(dot = DotState.IDLE, result = null) }
                setPanel(PanelMode.PAYWALL)
            } else if (result == null && alwaysShowDot) {
                // Point mode: the user asked for this check, so a failure must be visible, never a silent grey dot.
                lastClaimKey = null
                val why = error?.let { "${it.javaClass.simpleName}: ${it.message?.take(100) ?: "no details"}" } ?: "unknown error"
                val failure = VerdictResult(
                    claim = claim, verdict = Verdict.UNVERIFIED, confidence = 0,
                    summary = "Could not run the check ($why). Tap the dot to try again.",
                    sources = emptyList(),
                )
                _ui.update { it.copy(dot = DotState.CAUTION, result = failure) }
                setPanel(PanelMode.VERDICT)
            } else if (result == null) {
                // Verification failed. Go quiet; the scanner may retry after a cooldown (see retryDue).
                lastClaimKey = null
                failed = true
                retryArmed = false
                scope.launch { delay(retryCooldownMs); retryArmed = true }
                _ui.update { it.copy(dot = DotState.IDLE) }
            } else {
                failed = false
                _ui.update { it.copy(dot = result.verdict.toDotState(), result = result) }
                setPanel(PanelMode.VERDICT) // line draws out, box fades in
                dwellJob?.cancel()
                dwellJob = scope.launch {
                    delay(minDwellMs)
                    val next = pendingClaim ?: return@launch
                    pendingClaim = null
                    val k = ClaimKey.of(next)
                    if (k != lastClaimKey) { lastClaimKey = k; startVerification(next) }
                }
            }
        }
    }

    /** Show/hide the box or quick menu. Showing the verdict box arms the 8 s auto-collapse. */
    fun setPanel(mode: PanelMode) {
        collapseJob?.cancel()
        collapseJob = null
        if (mode == PanelMode.VERDICT && _ui.value.result == null) return
        _ui.update { it.copy(panel = mode) }
        if (mode == PanelMode.VERDICT) {
            collapseJob = scope.launch {
                delay(autoCollapseMs)
                _ui.update { if (it.panel == PanelMode.VERDICT) it.copy(panel = PanelMode.NONE) else it }
            }
        }
    }

    /** User is reading (e.g. expanded the sources list): keep the box open. */
    fun holdPanel() {
        collapseJob?.cancel()
        collapseJob = null
    }

    fun ignoreCurrentApp() {
        currentPackage?.let(onIgnoreApp)
        reset(keepDot = true)
    }

    /** Un-pause in point mode: bring the idle dot back (a paused state hides everything). */
    fun showIdleDot() {
        if (isPaused() || _ui.value.visible) return
        _ui.value = OverlayUiState(visible = true, dot = DotState.IDLE)
    }

    /** Pause/stop: cancel everything and hide the overlay. */
    fun reset(keepDot: Boolean = false) {
        verifyJob?.cancel()
        collapseJob?.cancel()
        idleJob?.cancel()
        dwellJob?.cancel()
        dwellJob = null
        pendingClaim = null
        failed = false
        verifyJob = null
        collapseJob = null
        idleJob = null
        lastClaimKey = null
        _ui.value = if (keepDot) OverlayUiState(visible = true, dot = DotState.IDLE) else OverlayUiState()
    }

    private fun scheduleIdleSettle() {
        val s = _ui.value
        if (!s.visible || s.dot == DotState.VERIFYING || s.dot == DotState.IDLE || idleJob != null) return
        idleJob = scope.launch {
            delay(idleAfterMs)
            idleJob = null
            lastClaimKey = null // coming back to the same story later should re-verify (cheap once cached, P4)
            collapseJob?.cancel()
            _ui.value = OverlayUiState(visible = true, dot = DotState.IDLE)
        }
    }
}
