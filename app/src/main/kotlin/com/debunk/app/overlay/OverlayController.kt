package com.debunk.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.debunk.app.data.Prefs
import com.debunk.app.ui.DetailsHolder
import com.debunk.app.ui.MainActivity
import com.debunk.core.domain.DotState
import com.debunk.core.domain.PanelMode
import com.debunk.core.overlay.OverlayGeometry
import com.debunk.core.session.ScanCoordinator
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the three overlay windows and keeps them in sync with [ScanCoordinator.ui].
 *
 *  1. DOT   - 48dp window, draggable, snaps to a screen edge. Present whenever the overlay is visible.
 *  2. PANEL - the message box (or the long-press menu). Sized to its content, so it never covers more
 *             than it draws. NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH: touches elsewhere still reach the app
 *             below, and we get told about them so we can collapse.
 *  3. LINE  - full-screen but FLAG_NOT_TOUCHABLE, only draws the connector line. alpha 0.8 so Android 12+'s
 *             "untrusted touch" rule (overlays above 0.8 opacity can block touches to apps) never applies.
 *
 * All windows are FLAG_NOT_FOCUSABLE: the overlay can never take focus or block the keyboard / app below.
 */
class OverlayController(
    private val context: Context,
    private val coordinator: ScanCoordinator,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val owner = OverlayLifecycleOwner()

    private var dotView: DotTouchLayout? = null
    private var dotParams: WindowManager.LayoutParams? = null
    private var panelView: PanelRootLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var lineView: ComposeView? = null

    // Compose-observable state shared with the panel/line windows.
    private var panelShown by mutableStateOf(false)
    private var contentMode by mutableStateOf(PanelMode.NONE)
    private var panelWidthDp by mutableStateOf(300f)
    private var panelMaxHeightDp by mutableStateOf(160f)
    private var lineSpec by mutableStateOf<LineSpec?>(null)

    private var panelGeneration = 0
    private var panelOpenOnDown = false
    private var lastOutsideAt = 0L
    private var snapAnimator: ValueAnimator? = null
    private var job: Job? = null

    private fun dp(v: Float) = (v * density).toInt()

    fun start() {
        owner.start()
        job = scope.launch { coordinator.ui.collect { render() } }
    }

    fun stop() {
        job?.cancel()
        snapAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        removePanelWindows()
        removeDot()
        owner.destroy()
    }

    /** Call on rotation / display change so the dot can't be stranded off-screen. */
    fun onConfigurationChanged() {
        val v = dotView ?: return
        val p = dotParams ?: return
        val (sw, sh) = screenSize()
        p.x = OverlayGeometry.snapLeft(p.x, p.width, sw)
        p.y = (prefs.dotYFraction * sh).toInt().coerceIn(topInset(), sh - p.height)
        wm.updateViewLayout(v, p)
        coordinator.setPanel(PanelMode.NONE)
    }

    // ------------------------------------------------------------------------------------ rendering

    private fun render() {
        val ui = coordinator.ui.value
        if (!ui.visible) {
            removePanelWindows()
            removeDot()
            return
        }
        ensureDot()
        if (ui.panel == PanelMode.NONE) hidePanelAnimated() else showPanel(ui.panel)
    }

    // ---------------------------------------------------------------------------------------- dot

    private fun ensureDot() {
        if (dotView != null) return
        val (sw, sh) = screenSize()
        val size = dp(48f)
        val params = baseParams(size, size, touchable = true).apply {
            x = if (prefs.dotOnLeft) 0 else sw - size
            y = (prefs.dotYFraction * sh).toInt().coerceIn(topInset(), sh - size)
        }

        val root = DotTouchLayout(context).apply {
            owner.attachTo(this)
            addView(
                ComposeView(context).apply {
                    setContent {
                        val ui by coordinator.ui.collectAsState()
                        StatusDot(ui.dot)
                    }
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "deBUNK fact-check indicator"

            onDown = {
                // A tap on the dot while the panel is open is also an "outside" touch for the panel window.
                // Whichever event is delivered first, treat that tap as "close", not "close then reopen".
                panelOpenOnDown = coordinator.ui.value.panel != PanelMode.NONE ||
                    SystemClock.uptimeMillis() - lastOutsideAt < 250
                snapAnimator?.cancel()
            }
            onTap = {
                val ui = coordinator.ui.value
                if (panelOpenOnDown) coordinator.setPanel(PanelMode.NONE)
                else if (prefs.pointMode && ui.result == null && ui.dot != DotState.VERIFYING) checkUnderDot() // tap = check what is under me
                else coordinator.setPanel(PanelMode.VERDICT) // ignored by the coordinator if there is no result yet
            }
            onLongPress = { coordinator.setPanel(PanelMode.MENU) }
            onDragStart = {
                coordinator.setPanel(PanelMode.NONE)
                if (prefs.pointMode) coordinator.reset(keepDot = true) // an old verdict colour must not follow the dot to a new line
            }
            onDrag = { dx, dy -> dragBy(dx, dy) }
            onDragEnd = { if (prefs.pointMode) checkUnderDot() else snapToEdge() }
        }
        wm.addView(root, params)
        dotView = root
        dotParams = params
    }

    private fun dragBy(dx: Float, dy: Float) {
        val v = dotView ?: return
        val p = dotParams ?: return
        val (sw, sh) = screenSize()
        p.x = (p.x + dx).toInt().coerceIn(0, sw - p.width)
        p.y = (p.y + dy).toInt().coerceIn(topInset(), sh - p.height)
        wm.updateViewLayout(v, p)
    }

    /** Point mode: read ONLY the text under the dot's centre and verify it. */
    private fun checkUnderDot() {
        val p = dotParams ?: return
        val (_, sh) = screenSize()
        prefs.dotYFraction = p.y.toFloat() / sh
        val cx = p.x + p.width / 2
        val cy = p.y + p.height / 2
        handler.postDelayed({
            val reader = com.debunk.app.data.DebunkRuntime.textReader
            val hit = reader?.invoke(cx, cy, dp(28f))
            when {
                reader == null -> Toast.makeText(context, "Turn on the deBUNK accessibility service first", Toast.LENGTH_LONG).show()
                hit == null -> Toast.makeText(context, "No readable text under the dot. Drop it on a headline or sentence.\n(" +
                    com.debunk.app.detect.ScreenTextExtractor.lastDiag + ")", Toast.LENGTH_LONG).show()
                else -> coordinator.verifyPointed(hit.first, hit.second)
            }
        }, 250)
    }

    private fun snapToEdge() {
        val v = dotView ?: return
        val p = dotParams ?: return
        val (sw, sh) = screenSize()
        val target = OverlayGeometry.snapLeft(p.x, p.width, sw)
        prefs.dotOnLeft = target == 0
        prefs.dotYFraction = p.y.toFloat() / sh
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(p.x, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                p.x = it.animatedValue as Int
                if (v.isAttachedToWindow) wm.updateViewLayout(v, p)
            }
            start()
        }
    }

    private fun removeDot() {
        snapAnimator?.cancel()
        dotView?.let { runCatching { wm.removeView(it) } }
        dotView = null
        dotParams = null
    }

    // -------------------------------------------------------------------------------------- panel

    private fun showPanel(mode: PanelMode) {
        val dot = dotParams ?: return
        panelGeneration++ // cancels any pending fade-out removal
        val (sw, sh) = screenSize()
        val isVerdict = mode == PanelMode.VERDICT || mode == PanelMode.PAYWALL

        // Spec: box max width ~75% of screen, max height ~25%.
        val widthPx = if (isVerdict) min((sw * 0.75f).toInt(), dp(360f)) else dp(MENU_WIDTH_DP)
        val maxHeightPx = if (isVerdict) (sh * 0.25f).toInt() else dp(MENU_HEIGHT_DP)

        val layout = OverlayGeometry.placePanel(
            dotCx = dot.x + dot.width / 2,
            dotCy = dot.y + dot.height / 2,
            dotRadius = dp(10f),
            screenW = sw, screenH = sh,
            panelWidth = widthPx, panelMaxHeight = maxHeightPx,
            gap = dp(28f), margin = dp(8f), topInset = topInset(),
        )

        contentMode = mode
        panelWidthDp = layout.width / density
        panelMaxHeightDp = maxHeightPx / density
        lineSpec = if (isVerdict) LineSpec(layout.lineStartX, layout.lineStartY, layout.lineEndX, layout.lineEndY) else null

        val isNew = panelView == null
        val params = panelParams ?: baseParams(layout.width, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).also {
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            panelParams = it
        }
        params.width = layout.width
        // Anchoring the box by its BOTTOM edge when above the dot means we never need to know its height.
        params.gravity = (if (layout.placeAbove) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
        params.x = layout.left
        params.y = if (layout.placeAbove) sh - layout.edgeY else layout.edgeY

        if (isNew) {
            val root = PanelRootLayout(context).apply {
                owner.attachTo(this)
                onOutsideTouch = {
                    lastOutsideAt = SystemClock.uptimeMillis()
                    coordinator.setPanel(PanelMode.NONE)
                }
                addView(
                    ComposeView(context).apply {
                        setContent {
                            val ui by coordinator.ui.collectAsState()
                            PanelContent(
                                shown = panelShown, mode = contentMode, result = ui.result,
                                widthDp = panelWidthDp.dp, maxHeightDp = panelMaxHeightDp.dp, actions = panelActions,
                            )
                        }
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            panelShown = false
            wm.addView(root, params)
            panelView = root
            handler.post { panelShown = true } // next frame: lets the fade-in actually animate
        } else {
            panelView?.let { wm.updateViewLayout(it, params) }
            panelShown = true
        }

        if (isVerdict) ensureLine() else removeLine()
    }

    private fun hidePanelAnimated() {
        if (panelView == null && lineView == null) return
        panelShown = false // composables fade out (box 180ms, line 200ms) ...
        val gen = ++panelGeneration
        handler.postDelayed({ if (gen == panelGeneration) removePanelWindows() }, 260) // ... then windows go away
    }

    private fun removePanelWindows() {
        panelGeneration++
        panelShown = false
        removeLine()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelParams = null
        lineSpec = null
        contentMode = PanelMode.NONE
    }

    // -------------------------------------------------------------------------------------- line

    private fun ensureLine() {
        if (lineView != null) return
        val params = baseParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = false,
        ).apply { alpha = 0.8f }
        val v = ComposeView(context).apply {
            owner.attachTo(this)
            setContent { ConnectorLine(lineSpec, panelShown) }
        }
        wm.addView(v, params)
        lineView = v
    }

    private fun removeLine() {
        lineView?.let { runCatching { wm.removeView(it) } }
        lineView = null
    }

    // ------------------------------------------------------------------------------------ actions

    private val panelActions = object : PanelActions {
        override fun onDetails() {
            val result = coordinator.ui.value.result ?: return
            DetailsHolder.show(context, result)
        }
        override fun onDismiss() = coordinator.setPanel(PanelMode.NONE)
        override fun onSourcesExpanded() = coordinator.holdPanel()
        override fun onPause() = com.debunk.app.data.DebunkRuntime.setPaused(true)
        override fun onIgnoreApp() {
            coordinator.ignoreCurrentApp()
            Toast.makeText(context, "deBUNK will ignore this app", Toast.LENGTH_SHORT).show()
        }
        override fun onSettings() {
            coordinator.setPanel(PanelMode.NONE)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun onUpgrade() {
            coordinator.setPanel(PanelMode.NONE)
            context.startActivity(Intent(context, com.debunk.app.ui.PremiumActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private fun baseParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = flags or if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "deBUNK overlay"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Full physical screen size in px (including system bars), matching FLAG_LAYOUT_NO_LIMITS windows. */
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun topInset(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24f)
    }

    private companion object {
        const val MENU_WIDTH_DP = 200f
        const val MENU_HEIGHT_DP = 170f
    }
}
