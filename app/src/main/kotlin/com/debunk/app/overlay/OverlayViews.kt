package com.debunk.app.overlay

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot

/**
 * Compose needs a Lifecycle/SavedState/ViewModelStore owner on the view tree. A Service has none,
 * so overlay windows get this stand-in. Drive it with [start] / [destroy] from the hosting service.
 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }
}

/**
 * Root of the dot window. Handles touch itself (rather than in Compose) because while the user drags,
 * the WINDOW moves under the finger; view-local coordinates would feed back and jitter. Raw screen
 * coordinates don't.
 *
 * Gestures: tap, long-press, drag. Children (the Compose dot) never see touches.
 */
class DotTouchLayout(context: Context) : FrameLayout(context) {
    var onDown: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressFired = false

    private val longPress = Runnable {
        if (!dragging) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                dragging = false; longPressFired = false
                onDown?.invoke()
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                    onDragStart?.invoke()
                }
                if (dragging) onDrag?.invoke(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX; lastY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) onDragEnd?.invoke() else if (!longPressFired) onTap?.invoke()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) onDragEnd?.invoke()
                dragging = false
            }
        }
        return true
    }
}

/**
 * Root of the message-box window. The window is NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH, so taps elsewhere
 * still reach the app underneath AND we get ACTION_OUTSIDE to collapse the box.
 */
class PanelRootLayout(context: Context) : FrameLayout(context) {
    var onOutsideTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
