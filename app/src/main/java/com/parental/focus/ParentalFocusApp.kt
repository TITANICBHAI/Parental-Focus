package com.parental.focus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ParentalFocusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Persistent foreground service channel
        NotificationChannel(
            CHANNEL_FOREGROUND,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            manager.createNotificationChannel(this)
        }

        // Block-alert channel (high importance for full-screen intent)
        NotificationChannel(
            CHANNEL_BLOCK_ALERT,
            "Parental Focus – Block Alert",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fires when a restricted app is opened."
            manager.createNotificationChannel(this)
        }
    }

    companion object {
        const val CHANNEL_FOREGROUND  = "pf_foreground"
        const val CHANNEL_BLOCK_ALERT = "pf_block_alert"
        const val NOTIF_ID_FOREGROUND = 1001
        const val NOTIF_ID_BLOCK      = 9001
    }
}
