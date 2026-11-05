package com.debunk.core.detect

import kotlin.math.min

/** How eagerly the detector fires. Higher number of required points = fewer false activations. */
enum class Sensitivity(val threshold: Int) { HIGH(3), MEDIUM(5), LOW(7) }

data class Trigger(val phrase: String, val weight: Int)

data class DetectionConfig(
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    /** User-defined trigger keywords (Settings, P4). Weighted 3. */
    val extraKeywords: List<String> = emptyList(),
)

/** `candidate` is the single line most likely to hold the claim; it is the ONLY text that leaves the detector. */
data class Detection(val candidate: String, val score: Int, val trigger: String?)

/**
 * Local, offline "does this screen contain news?" filter. Runs before any network call.
 *
 * Scoring (per screen):
 *   total = shapeScore(best line) + min(sum of trigger weights across the screen, 6)
 * where shapeScore rewards headline-looking lines (length, word count, title case or a news verb, digits).
 * Fires when total >= sensitivity.threshold.
 *
 * Phase 1 is English-only; Hindi/Marathi triggers and verbs arrive in P5.
 */
class NewsDetector(private val config: DetectionConfig = DetectionConfig()) {

    private class CompiledTrigger(val trigger: Trigger, val regex: Regex)

    private val triggers: List<CompiledTrigger> =
        (DEFAULT_TRIGGERS + config.extraKeywords.filter { it.isNotBlank() }.map { Trigger(it.trim(), 3) })
            .map { CompiledTrigger(it, boundaryRegex(it.phrase)) }

    private data class Scored(val line: String, val score: Int, val shape: Int, val trigger: String?)

    fun detect(lines: List<String>): Detection? {
        var screenTrigger = 0
        var best: Scored? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val (tw, tname) = triggerScore(line)
            screenTrigger += tw

            val words = wordCount(line)
            if (words < MIN_CLAIM_WORDS) continue // too short to be a claim (but it may still be a trigger label)

            val shape = shapeScore(line, words)
            val score = shape + tw
            if (score > 0 && (best == null || score > best.score)) best = Scored(line, score, shape, tname)
        }

        val b = best ?: return null
        val total = b.shape + min(screenTrigger, MAX_SCREEN_TRIGGER)
        if (total < config.sensitivity.threshold) return null
        return Detection(b.line.take(MAX_CANDIDATE_CHARS), total, b.trigger)
    }

    /** Sum of matched trigger weights in a line (capped at 4) plus the strongest matched phrase. */
    private fun triggerScore(line: String): Pair<Int, String?> {
        var sum = 0
        var strongest: Trigger? = null
        for (t in triggers) {
            if (t.regex.containsMatchIn(line)) {
                sum += t.trigger.weight
                if (strongest == null || t.trigger.weight > strongest.weight) strongest = t.trigger
            }
        }
        return min(sum, MAX_LINE_TRIGGER) to strongest?.phrase
    }

    private fun shapeScore(line: String, words: Int): Int {
        if (line.length !in 30..250 || words < 6) return 0
        val first = line.first()
        if (!(first.isLetterOrDigit() || first in OPENING_QUOTES)) return 0
        val letters = line.count { it.isLetter() }
        if (letters < line.length * 0.5) return 0 // mostly digits/symbols: prices, timestamps, UI chrome

        val hasVerb = NEWS_VERBS.containsMatchIn(line)
        if (!hasVerb && titleCaseRatio(line) < 0.5) return 0

        var score = 2
        if (hasVerb) score++
        if (line.any { it.isDigit() }) score++
        return score
    }

    private fun titleCaseRatio(line: String): Double {
        val ws = line.split(WHITESPACE).filter { w -> w.any { it.isLetter() } }
        if (ws.isEmpty()) return 0.0
        val caps = ws.count { w -> w.firstOrNull { it.isLetter() }?.isUpperCase() == true }
        return caps.toDouble() / ws.size
    }

    private fun wordCount(line: String) = line.split(WHITESPACE).count { it.isNotBlank() }

    companion object {
        const val MIN_CLAIM_WORDS = 5
        const val MAX_CANDIDATE_CHARS = 300
        private const val MAX_LINE_TRIGGER = 4
        private const val MAX_SCREEN_TRIGGER = 6
        private val WHITESPACE = Regex("\\s+")
        private const val OPENING_QUOTES = "\"'“‘"

        /** Unicode-aware boundaries (plain \b misbehaves with Devanagari combining marks). */
        private fun boundaryRegex(phrase: String) = Regex(
            "(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

        val DEFAULT_TRIGGERS: List<Trigger> = listOf(
            // strong: explicit news framing
            Trigger("breaking news", 4), Trigger("breaking", 4), Trigger("just in", 4),
            Trigger("news alert", 4), Trigger("developing story", 4), Trigger("live updates", 4),
            Trigger("exclusive", 4),
            // medium
            Trigger("alert", 2), Trigger("urgent", 2), Trigger("viral", 2),
            // weak: common in ordinary text, only meaningful in combination
            Trigger("news", 1), Trigger("reported", 1), Trigger("reports", 1), Trigger("according to", 1),
            Trigger("sources say", 1), Trigger("officials said", 1), Trigger("confirmed", 1),
            Trigger("announced", 1), Trigger("claims", 1),
        )

        private val NEWS_VERBS = Regex(
            "\\b(says?|said|dies?|died|kill(?:s|ed)?|arrest(?:s|ed)?|announce[sd]?|confirm(?:s|ed)?|" +
                "report(?:s|ed)?|bans?|banned|wins?|won|launch(?:es|ed)?|declares?|declared|resign(?:s|ed)?|" +
                "warns?|warned|claims?|claimed|orders?|ordered|hits?|rises?|rose|falls?|fell|passe[sd]|" +
                "approv(?:es|ed)|reject(?:s|ed)?|seiz(?:es|ed)|attack(?:s|ed)?|crash(?:es|ed)?|injur(?:es|ed)|" +
                "elected|sworn|quits?|hospitali[sz]ed|detained|charged|sentenced)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
