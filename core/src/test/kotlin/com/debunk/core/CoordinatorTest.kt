package com.debunk.core

import com.debunk.core.detect.NewsDetector
import com.debunk.core.domain.DotState
import com.debunk.core.domain.PanelMode
import com.debunk.core.domain.Verdict
import com.debunk.core.domain.VerdictResult
import com.debunk.core.session.ScanCoordinator
import com.debunk.core.verify.MockVerifier
import com.debunk.core.verify.Verifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTest {
    private val news = listOf("BREAKING NEWS", "Fire at Delhi market kills 12, police say")

    private class CountingVerifier : Verifier {
        var calls = 0
        override suspend fun verify(claim: String): VerdictResult {
            calls++
            delay(1_000)
            return MockVerifier(0, 0).verify(claim)
        }
    }

    @Test fun detectsVerifiesShowsThenAutoCollapses() = runTest {
        val v = CountingVerifier()
        var paused = false
        val c = ScanCoordinator(backgroundScope, v, { NewsDetector() }, { paused }, {})

        c.onScreenText("com.chrome", news)
        assertTrue(c.ui.value.visible)
        assertEquals(DotState.VERIFYING, c.ui.value.dot)
        assertEquals(PanelMode.NONE, c.ui.value.panel)

        advanceTimeBy(1_001); runCurrent()
        assertTrue(c.ui.value.dot != DotState.VERIFYING)
        assertEquals(PanelMode.VERDICT, c.ui.value.panel)

        advanceTimeBy(8_001); runCurrent()
        assertEquals(PanelMode.NONE, c.ui.value.panel)
        assertTrue(c.ui.value.visible) // dot stays
    }

    @Test fun sameClaimIsNotVerifiedTwice() = runTest {
        val v = CountingVerifier()
        val c = ScanCoordinator(backgroundScope, v, { NewsDetector() }, { false }, {})
        c.onScreenText("a", news); c.onScreenText("a", news)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, v.calls)
    }

    @Test fun pausedDoesNothing() = runTest {
        val c = ScanCoordinator(backgroundScope, CountingVerifier(), { NewsDetector() }, { true }, {})
        c.onScreenText("a", news)
        assertFalse(c.ui.value.visible)
    }

    @Test fun nonNewsScreenDoesNothing() = runTest {
        val c = ScanCoordinator(backgroundScope, CountingVerifier(), { NewsDetector() }, { false }, {})
        c.onScreenText("a", listOf("Settings", "Wi-Fi"))
        assertFalse(c.ui.value.visible)
    }

    @Test fun ignoreAppReportsPackageAndResetsToIdleDot() = runTest {
        var ignored: String? = null
        val c = ScanCoordinator(backgroundScope, CountingVerifier(), { NewsDetector() }, { false }, { ignored = it })
        c.onScreenText("com.news.app", news)
        advanceTimeBy(1_001); runCurrent()
        c.ignoreCurrentApp()
        assertEquals("com.news.app", ignored)
        assertEquals(DotState.IDLE, c.ui.value.dot)
        assertEquals(PanelMode.NONE, c.ui.value.panel)
    }

    @Test fun dotSettlesToIdleAfterNewsLeavesScreen() = runTest {
        val c = ScanCoordinator(backgroundScope, CountingVerifier(), { NewsDetector() }, { false }, {})
        c.onScreenText("a", news)
        advanceTimeBy(1_001); runCurrent()
        c.onScreenText("launcher", listOf("Home"))
        advanceTimeBy(15_001); runCurrent()
        assertEquals(DotState.IDLE, c.ui.value.dot)
    }

    @Test fun mockVerifierObeysSpecRules() = runTest {
        val mv = MockVerifier(0, 0)
        val seen = mutableSetOf<Verdict>()
        for (i in 0 until 200) {
            val r = mv.verify("claim number $i about something")
            seen += r.verdict
            assertTrue(r.confidence in 0..100)
            assertTrue(r.sources.isNotEmpty())
            if (r.verdict == Verdict.UNVERIFIED) assertTrue(r.sources.size < 2) else assertTrue(r.sources.size >= 2)
        }
        assertEquals(Verdict.values().toSet(), seen)
    }

    @Test fun newHeadlineWaitsForDwellThenReplacesVerdict() = runTest {
        val v = CountingVerifier()
        val c = ScanCoordinator(backgroundScope, v, { NewsDetector() }, { false }, {})
        c.onScreenText("a", news)
        advanceTimeBy(1_001); runCurrent() // first verdict is on display
        c.onScreenText("a", listOf("BREAKING NEWS", "Bridge collapses in Mumbai, ten injured, officials said"))
        runCurrent()
        assertEquals(1, v.calls) // queued, not started: the shown verdict is not wiped mid-read
        advanceTimeBy(6_001); runCurrent()
        assertEquals(2, v.calls)
    }

    @Test fun failedCheckIsRetriedAfterCooldown() = runTest {
        val failing = object : Verifier {
            override suspend fun verify(claim: String): VerdictResult = throw java.io.IOException("down")
        }
        val c = ScanCoordinator(backgroundScope, failing, { NewsDetector() }, { false }, {})
        c.onScreenText("a", news); runCurrent()
        assertFalse(c.retryDue())
        advanceTimeBy(10_001); runCurrent()
        assertTrue(c.retryDue())
    }

    @Test fun pointModeShowsIdleDotFromTheStart() = runTest {
        val c = ScanCoordinator(backgroundScope, CountingVerifier(), { NewsDetector() }, { false }, {}, alwaysShowDot = true)
        assertTrue(c.ui.value.visible)
        assertEquals(DotState.IDLE, c.ui.value.dot)
    }

    @Test fun quotaExceededShowsPaywallNotAnError() = runTest {
        var errors = 0
        val v = object : Verifier {
            override suspend fun verify(claim: String): VerdictResult = throw com.debunk.core.verify.QuotaExceededException(10, 10)
        }
        val c = ScanCoordinator(backgroundScope, v, { NewsDetector() }, { false }, {}, onVerifyError = { errors++ })
        c.verifyPointed("com.chrome", "Fire at Delhi market kills 12, police say")
        runCurrent()
        assertEquals(PanelMode.PAYWALL, c.ui.value.panel)
        assertEquals(DotState.IDLE, c.ui.value.dot)
        assertEquals(0, errors)               // not reported as a failure
        advanceTimeBy(60_000); runCurrent()
        assertEquals(PanelMode.PAYWALL, c.ui.value.panel) // stays until the user dismisses it
    }
}
