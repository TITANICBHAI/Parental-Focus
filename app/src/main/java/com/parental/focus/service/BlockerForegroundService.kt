package com.parental.focus.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.parental.focus.ParentalFocusApp
import com.parental.focus.R
import com.parental.focus.ui.MainActivity

/**
 * BlockerForegroundService
 *
 * Lightweight foreground service that keeps the app's process alive so that
 * the AccessibilityService is never killed by Android's memory manager.
 *
 * Without this, aggressive OEM battery managers (Samsung One UI, MIUI,
 * ColorOS) terminate the accessibility service process after ~10 minutes
 * of inactivity, silently disabling all blocking.
 *
 * The notification is LOW importance (no sound, no heads-up) — it serves only
 * as the required foreground-service anchor.
 */
class BlockerForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ParentalFocusApp.NOTIF_ID_FOREGROUND, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() =
        NotificationCompat.Builder(this, ParentalFocusApp.CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
