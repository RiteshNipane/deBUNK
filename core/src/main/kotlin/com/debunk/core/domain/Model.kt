package com.debunk.core.domain

import java.security.MessageDigest

/** Verdict values. Mirrors the backend JSON contract ("LIKELY_TRUE | LIKELY_FALSE | MISLEADING | UNVERIFIED"). */
enum class Verdict(val label: String) {
    LIKELY_TRUE("LIKELY TRUE"),
    LIKELY_FALSE("LIKELY FALSE"),
    MISLEADING("MISLEADING"),
    UNVERIFIED("UNVERIFIED"),
}

enum class Stance { SUPPORTS, CONTRADICTS, NEUTRAL }

/** One evidence source. `credibilityTier`: 1 = wire/official, 2 = major outlet, 3 = other. */
data class SourceRef(
    val name: String,
    val url: String,
    val stance: Stance,
    val credibilityTier: Int,
    /** "fact_check" | "news" | "official". Shown in the details sheet. */
    val kind: String = "news",
    /** ISO-8601 date when the backend knows it. */
    val published: String? = null,
    /** Headline or review title of the evidence item. */
    val title: String? = null,
)

/**
 * Result of verifying one claim.
 *
 * Invariant (spec rule): fewer than 2 independent sources => verdict must be UNVERIFIED.
 * `developing` marks a "developing story" (valid state in the spec).
 */
data class VerdictResult(
    val claim: String,
    val verdict: Verdict,
    val confidence: Int,
    val summary: String,
    val sources: List<SourceRef>,
    val developing: Boolean = false,
    /** True when a backend stage fell back (no LLM, source timeout). Confidence is already capped. */
    val degraded: Boolean = false,
    /** Per-claim breakdown when the text held several claims (headline verdict above is the most alarming). */
    val claims: List<VerdictResult> = emptyList(),
)

/** Visual state of the anchor dot. */
enum class DotState { IDLE, VERIFYING, TRUE, FALSE, CAUTION }

fun Verdict.toDotState(): DotState = when (this) {
    Verdict.LIKELY_TRUE -> DotState.TRUE
    Verdict.LIKELY_FALSE -> DotState.FALSE
    Verdict.MISLEADING, Verdict.UNVERIFIED -> DotState.CAUTION
}

/** What the floating panel (box or quick menu) is currently showing. */
enum class PanelMode { NONE, VERDICT, MENU, PAYWALL }

/** Everything the overlay needs to render. Contains claim + verdict only, never raw screen text. */
data class OverlayUiState(
    val visible: Boolean = false,
    val dot: DotState = DotState.IDLE,
    val result: VerdictResult? = null,
    val panel: PanelMode = PanelMode.NONE,
)

/** Stable key for a claim (used for de-duplication now, and for the 6h cache in P4). */
object ClaimKey {
    fun of(text: String): String {
        val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
