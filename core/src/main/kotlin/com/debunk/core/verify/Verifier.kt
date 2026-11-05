package com.debunk.core.verify

import com.debunk.core.domain.SourceRef
import com.debunk.core.domain.Stance
import com.debunk.core.domain.Verdict
import com.debunk.core.domain.VerdictResult
import kotlinx.coroutines.delay

/** Verification backend seam. P1 = [MockVerifier]; P2/P3 swap in an HTTP client for the FastAPI service. */
interface Verifier {
    suspend fun verify(claim: String): VerdictResult
}

/** The free plan's daily allowance is used up. The UI shows an upgrade prompt instead of an error. */
class QuotaExceededException(val limit: Int, val used: Int) : Exception("daily limit reached ($used of $limit)")

/**
 * Deterministic fake verdicts (same claim => same verdict) with a simulated network delay,
 * so every UI state can be exercised without a backend.
 * Obeys the spec rules: <2 sources => UNVERIFIED; never a verdict without sources.
 */
class MockVerifier(private val minLatencyMs: Long = 1_500, private val jitterMs: Long = 1_300) : Verifier {

    override suspend fun verify(claim: String): VerdictResult {
        val h = claim.lowercase().hashCode() and Int.MAX_VALUE
        if (minLatencyMs > 0 || jitterMs > 0) delay(minLatencyMs + if (jitterMs > 0) h % jitterMs else 0)

        return when (h % 4) {
            0 -> VerdictResult(
                claim, Verdict.LIKELY_TRUE, 70 + h % 25,
                "Reported consistently by independent outlets. Details match official statements.",
                listOf(src("Reuters", Stance.SUPPORTS, 1), src("BBC", Stance.SUPPORTS, 1), src("PIB", Stance.SUPPORTS, 1)),
            )
            1 -> VerdictResult(
                claim, Verdict.LIKELY_FALSE, 70 + h % 28,
                "A fact-check contradicts this claim. No credible outlet reports it.",
                listOf(src("Alt News", Stance.CONTRADICTS, 2), src("Reuters", Stance.CONTRADICTS, 1), src("PTI", Stance.NEUTRAL, 1)),
            )
            2 -> VerdictResult(
                claim, Verdict.MISLEADING, 55 + h % 25,
                "Core event is real, but the figures or context here differ from original reports.",
                listOf(src("The Hindu", Stance.SUPPORTS, 2), src("AP", Stance.CONTRADICTS, 1)),
            )
            else -> VerdictResult(
                claim, Verdict.UNVERIFIED, 25 + h % 25,
                "Only one outlet is covering this so far. Developing story; check back soon.",
                listOf(src("PTI", Stance.NEUTRAL, 1)),
                developing = true,
            )
        }
    }

    private fun src(name: String, stance: Stance, tier: Int) = SourceRef(
        name = name,
        url = "https://example.org/mock/" + name.lowercase().replace(' ', '-'),
        stance = stance,
        credibilityTier = tier,
    )
}
