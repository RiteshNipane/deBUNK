package com.debunk.app.data

import android.content.Context
import android.content.SharedPreferences
import com.debunk.core.detect.Sensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny settings store for Phase 1 (pause, ignored apps, dot position, consent, sensitivity).
 * Room-backed settings/history replace this in P4. Never stores screen content.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("debunk_prefs", Context.MODE_PRIVATE)

    private val _paused = MutableStateFlow(sp.getBoolean(K_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(value: Boolean) {
        sp.edit().putBoolean(K_PAUSED, value).apply()
        _paused.value = value
    }

    var consentGiven: Boolean
        get() = sp.getBoolean(K_CONSENT, false)
        set(v) = sp.edit().putBoolean(K_CONSENT, v).apply()

    val ignoredApps: Set<String>
        get() = sp.getStringSet(K_IGNORED, emptySet()) ?: emptySet()

    fun ignoreApp(pkg: String) {
        // getStringSet returns a live set that must not be mutated; copy first.
        sp.edit().putStringSet(K_IGNORED, ignoredApps + pkg).apply()
    }

    var sensitivity: Sensitivity
        get() = runCatching { Sensitivity.valueOf(sp.getString(K_SENS, null) ?: "") }.getOrDefault(Sensitivity.MEDIUM)
        set(v) = sp.edit().putString(K_SENS, v.name).apply()

    /** Dot resting side + vertical position (fraction of screen height), restored on next start. */
    var dotOnLeft: Boolean
        get() = sp.getBoolean(K_DOT_LEFT, false)
        set(v) = sp.edit().putBoolean(K_DOT_LEFT, v).apply()

    var dotYFraction: Float
        get() = sp.getFloat(K_DOT_Y, 0.35f)
        set(v) = sp.edit().putFloat(K_DOT_Y, v).apply()

    /** Point mode (default): the user drops the dot on a line and only that text is read and checked; no screen scanning. */
    var pointMode: Boolean
        get() = sp.getBoolean(K_POINT, true)
        set(v) = sp.edit().putBoolean(K_POINT, v).apply()

    private companion object {
        const val K_POINT = "point_mode"
        const val K_PAUSED = "paused"
        const val K_CONSENT = "consent"
        const val K_IGNORED = "ignored_apps"
        const val K_SENS = "sensitivity"
        const val K_DOT_LEFT = "dot_left"
        const val K_DOT_Y = "dot_y"
    }
}
