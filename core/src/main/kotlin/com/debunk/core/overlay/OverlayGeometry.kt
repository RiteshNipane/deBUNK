package com.debunk.core.overlay

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where the message box goes and where the connector line runs.
 * All values are screen pixels. `edgeY` is the box edge nearest the dot:
 * the BOTTOM edge when [placeAbove], the TOP edge otherwise.
 */
data class PanelLayout(
    val placeAbove: Boolean,
    val left: Int,
    val width: Int,
    val edgeY: Int,
    val lineStartX: Float,
    val lineStartY: Float,
    val lineEndX: Float,
    val lineEndY: Float,
)

/** Pure placement math (no Android types) so it can be unit-tested on the JVM. */
object OverlayGeometry {

    /** Left x for the dot window so it rests against the nearest screen edge. */
    fun snapLeft(dotLeft: Int, dotSize: Int, screenW: Int, margin: Int = 0): Int =
        if (dotLeft + dotSize / 2 < screenW / 2) margin else screenW - dotSize - margin

    /**
     * Prefer above-right of the dot. Flip below if there isn't room above (and below has more).
     * Horizontal position is clamped so the box never leaves the screen.
     * The line ends on whichever box corner (on the dot-facing edge) is nearer to the dot.
     */
    fun placePanel(
        dotCx: Int,
        dotCy: Int,
        dotRadius: Int,
        screenW: Int,
        screenH: Int,
        panelWidth: Int,
        panelMaxHeight: Int,
        gap: Int,
        margin: Int,
        topInset: Int,
    ): PanelLayout {
        val w = minOf(panelWidth, screenW - 2 * margin)

        val aboveRoom = dotCy - dotRadius - gap - topInset - margin
        val belowRoom = screenH - margin - (dotCy + dotRadius + gap)
        val above = aboveRoom >= panelMaxHeight || aboveRoom >= belowRoom

        val left = (dotCx + dotRadius + gap).coerceIn(margin, screenW - margin - w)
        val right = left + w
        val edgeY = if (above) dotCy - dotRadius - gap else dotCy + dotRadius + gap

        val cornerX = if (abs(dotCx - left) <= abs(dotCx - right)) left else right
        val dx = (cornerX - dotCx).toFloat()
        val dy = (edgeY - dotCy).toFloat()
        val dist = sqrt(dx * dx + dy * dy)
        // Start the line on the dot's rim, not its centre, so it never draws over the dot.
        val sx = if (dist == 0f) dotCx.toFloat() else dotCx + dx / dist * dotRadius
        val sy = if (dist == 0f) dotCy.toFloat() else dotCy + dy / dist * dotRadius

        return PanelLayout(above, left, w, edgeY, sx, sy, cornerX.toFloat(), edgeY.toFloat())
    }
}
