package com.debunk.app.detect

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks the accessibility tree and returns visible text lines in reading order.
 *
 * Privacy rules enforced here (before anything else sees the text):
 *  - password fields and ANY editable field are skipped (what the user is typing is never read)
 *  - invisible nodes (and their subtrees) are skipped
 * Work is bounded (node cap, line cap, per-line length cap) so a huge tree cannot stall the main thread.
 */
object ScreenTextExtractor {
    private const val MAX_NODES = 800
    private const val MAX_LINES = 150
    private const val MAX_LINE_CHARS = 400
    private const val MIN_DESCRIPTION_CHARS = 15

    fun extract(root: AccessibilityNodeInfo): List<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES && out.size < MAX_LINES) {
            val node = stack.removeLast()
            visited++
            try {
                if (!node.isVisibleToUser) continue
                if (node.isPassword || node.isEditable) continue

                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    out.add(text.take(MAX_LINE_CHARS))
                } else {
                    // Feed cards in many news apps expose the headline as contentDescription only.
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    if (desc.length >= MIN_DESCRIPTION_CHARS) out.add(desc.take(MAX_LINE_CHARS))
                }

                // Push in reverse so the first child is popped first (reading order).
                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            } finally {
                if (node !== root) recycleCompat(node)
            }
        }
        // Anything left unvisited (caps hit) must still be released on older Android versions.
        stack.forEach { if (it !== root) recycleCompat(it) }
        return out.toList()
    }

    /** Short human-readable summary of the last [textAt] call, shown in the "nothing found" toast while we tune this. */
    @Volatile var lastDiag: String = ""

    /**
     * Point mode: the text of the node under (x, y), preferring the smallest node that contains the point
     * and falling back to the nearest text node within [tolerancePx]. Needs >= 3 words to count as a statement.
     * Only subtrees whose bounds reach the target area are walked, so a long feed cannot exhaust the node budget
     * before the card under the dot is reached. Same privacy rules as [extract]: password / editable fields and
     * invisible nodes are never read.
     */
    fun textAt(root: AccessibilityNodeInfo, x: Int, y: Int, tolerancePx: Int): String? {
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var bestArea = Long.MAX_VALUE
        var nearestAny = Int.MAX_VALUE
        var withText = 0
        val r = Rect()
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_POINT_NODES) {
            val node = stack.removeLast()
            visited++
            try {
                if (node.isPassword || node.isEditable) continue

                node.getBoundsInScreen(r)
                val hasArea = r.width() > 0 && r.height() > 0
                val dx = maxOf(r.left - x, 0, x - r.right)
                val dy = maxOf(r.top - y, 0, y - r.bottom)
                val dist = maxOf(dx, dy)
                // Prune: a node that clearly does not reach the target area cannot contain a node that does.
                if (hasArea && dist > tolerancePx && node !== root) continue

                var text = node.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    if (desc.length >= MIN_DESCRIPTION_CHARS) text = desc
                }
                if (text.isNotEmpty()) {
                    withText++
                    if (hasArea && dist < nearestAny) nearestAny = dist
                    if (hasArea && dist <= tolerancePx && text.split(WHITESPACE).count { it.isNotBlank() } >= MIN_POINT_WORDS) {
                        val area = r.width().toLong() * r.height().toLong()
                        if (dist < bestDist || (dist == bestDist && area < bestArea)) {
                            best = text; bestDist = dist; bestArea = area
                        }
                    }
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            } finally {
                if (node !== root) recycleCompat(node)
            }
        }
        stack.forEach { if (it !== root) recycleCompat(it) }
        lastDiag = "at ${x},${y} in ${rootBounds.width()}x${rootBounds.height()}; nodes $visited, text $withText, nearest " +
            (if (nearestAny == Int.MAX_VALUE) "none" else "${nearestAny}px")
        return best?.take(MAX_POINT_CHARS)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_POINT_WORDS = 3
    private const val MAX_POINT_CHARS = 300
    private const val MAX_POINT_NODES = 4000

    @Suppress("DEPRECATION")
    fun recycleCompat(node: AccessibilityNodeInfo) {
        // recycle() is a no-op from API 33; required hygiene on API 29-32.
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }
}
