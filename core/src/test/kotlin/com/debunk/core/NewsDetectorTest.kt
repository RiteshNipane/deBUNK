package com.debunk.core

import com.debunk.core.detect.DetectionConfig
import com.debunk.core.detect.NewsDetector
import com.debunk.core.detect.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NewsDetectorTest {
    private val medium = NewsDetector()

    @Test fun triggerLabelPlusHeadlineFires() {
        val d = medium.detect(listOf("BREAKING NEWS", "Fire at Delhi market kills 12, police say", "Share"))
        assertNotNull(d)
        assertEquals("Fire at Delhi market kills 12, police say", d!!.candidate)
    }

    @Test fun settingsScreenDoesNotFire() {
        assertNull(medium.detect(listOf("Settings", "Wi-Fi", "Bluetooth", "Battery", "Display & brightness")))
    }

    @Test fun recipeTextDoesNotFire() {
        assertNull(medium.detect(listOf("Preheat the oven to 180 degrees and bake for twenty minutes")))
    }

    @Test fun lonelyHeadlineNeedsHighSensitivity() {
        val lines = listOf("Government announces 5 new schemes for farmers across the country")
        assertNull(NewsDetector(DetectionConfig(Sensitivity.MEDIUM)).detect(lines))
        assertNotNull(NewsDetector(DetectionConfig(Sensitivity.HIGH)).detect(lines))
    }

    @Test fun customKeywordCountsAsTrigger() {
        val lines = listOf("Leaked documents show minister met contractor before tender")
        assertNull(NewsDetector(DetectionConfig(Sensitivity.HIGH)).detect(lines))
        val d = NewsDetector(DetectionConfig(Sensitivity.HIGH, listOf("leaked"))).detect(lines)
        assertNotNull(d)
    }

    @Test fun triggersMatchWholeWordsOnly() {
        // "newsletter" must not count as "news"
        assertNull(medium.detect(listOf("Subscribe to our weekly newsletter for more great recipes today")))
    }

    @Test fun candidateIsTruncated() {
        val long = "BREAKING " + "Minister announces new policy ".repeat(20)
        val d = NewsDetector(DetectionConfig(Sensitivity.HIGH)).detect(listOf(long))
        // 250+ chars is not headline-shaped, but the trigger alone is enough at HIGH
        assertNotNull(d)
        assert(d!!.candidate.length <= NewsDetector.MAX_CANDIDATE_CHARS)
    }
}
