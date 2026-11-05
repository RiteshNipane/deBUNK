package com.debunk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.debunk.app.R
import com.debunk.app.data.DebunkRuntime
import com.debunk.app.overlay.OverlayController
import com.debunk.app.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the overlay alive. Shows the persistent notification (with Pause/Stop)
 * and owns the [OverlayController]. Detection itself lives in the AccessibilityService.
 */
class OverlayService : Service() {

    private var controller: OverlayController? = null
    private var pausedWatcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(DebunkRuntime.isPaused()), type)

        controller = OverlayController(this, DebunkRuntime.coordinator, DebunkRuntime.prefs, DebunkRuntime.scope)
            .also { it.start() }
        DebunkRuntime.overlayRunning.value = true
        if (DebunkRuntime.prefs.pointMode) DebunkRuntime.coordinator.showIdleDot() // point mode: dot is always on screen while active

        // Keep the notification's Pause/Resume label in sync with the paused flag (menu, notification or app).
        pausedWatcher = DebunkRuntime.scope.launch {
            DebunkRuntime.prefs.paused.collect { paused ->
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(paused))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> DebunkRuntime.setPaused(!DebunkRuntime.isPaused())
            ACTION_STOP -> {
                DebunkRuntime.coordinator.reset()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onDestroy() {
        pausedWatcher?.cancel()
        controller?.stop()
        controller = null
        DebunkRuntime.overlayRunning.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "deBUNK status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while deBUNK is watching for news on screen"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(paused: Boolean): Notification {
        fun service(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, OverlayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_debunk)
            .setContentTitle(if (paused) "deBUNK is paused" else "deBUNK is watching for news")
            .setContentText("Only news-like text is checked. Nothing is stored.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", service(ACTION_TOGGLE_PAUSE, 1))
            .addAction(0, "Stop", service(ACTION_STOP, 2))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "debunk_status"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE_PAUSE = "com.debunk.app.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.debunk.app.STOP"
    }
}
