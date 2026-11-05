package com.debunk.core

import com.debunk.core.detect.AppPolicy
import com.debunk.core.detect.ScanThrottle
import com.debunk.core.overlay.OverlayGeometry
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryAndPolicyTest {
    private val sw = 1080
    private val sh = 2400

    private fun place(cx: Int, cy: Int) = OverlayGeometry.placePanel(
        dotCx = cx, dotCy = cy, dotRadius = 30, screenW = sw, screenH = sh,
        panelWidth = 800, panelMaxHeight = 600, gap = 84, margin = 24, topInset = 80,
    )

    @Test fun placedAboveRightWhenRoomAllows() {
        val p = place(100, 1000)
        assertTrue(p.placeAbove)
        assertEquals(214, p.left) // dotCx + r + gap
        assertEquals(886, p.edgeY)
        assertEquals(214f, p.lineEndX, 0.01f) // left corner is nearer
    }

    @Test fun flipsBelowNearTopOfScreen() {
        val p = place(100, 200)
        assertFalse(p.placeAbove)
        assertEquals(200 + 30 + 84, p.edgeY)
    }

    @Test fun clampedInsideScreenAndUsesNearerCornerOnRightEdge() {
        val p = place(1040, 1000)
        assertEquals(sw - 24 - 800, p.left)
        assertTrue(p.left + p.width <= sw - 24)
        assertEquals((p.left + p.width).toFloat(), p.lineEndX, 0.01f)
    }

    @Test fun lineStartsOnTheDotRim() {
        val p = place(100, 1000)
        assertEquals(30.0, hypot((p.lineStartX - 100).toDouble(), (p.lineStartY - 1000).toDouble()), 0.5)
    }

    @Test fun snapsToNearestEdge() {
        assertEquals(0, OverlayGeometry.snapLeft(200, 144, sw))
        assertEquals(sw - 144, OverlayGeometry.snapLeft(700, 144, sw))
    }

    @Test fun throttleAllowsOneScanPerInterval() {
        val t = ScanThrottle(2000)
        assertEquals(0, t.delayUntilNextScan(1000))
        t.markScanned(1000)
        assertEquals(1500, t.delayUntilNextScan(1500))
        assertEquals(0, t.delayUntilNextScan(3000))
    }

    @Test fun defaultBlockedApps() {
        assertTrue(AppPolicy.isBlocked("com.whatsapp"))
        assertFalse(AppPolicy.isBlocked("com.whatsapp", allowMessaging = true))
        assertTrue(AppPolicy.isBlocked("com.phonepe.app", allowMessaging = true))
        assertTrue(AppPolicy.isBlocked("com.hdfcbank.android"))
        assertTrue(AppPolicy.isBlocked("com.mine", ownPackage = "com.mine"))
        assertTrue(AppPolicy.isBlocked("com.example.news", userIgnored = setOf("com.example.news")))
        assertFalse(AppPolicy.isBlocked("com.android.chrome"))
        assertFalse(AppPolicy.isBlocked("com.twitter.android"))
    }
}
