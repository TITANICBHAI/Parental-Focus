package com.parental.focus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.parental.focus.data.AppPreferences

/**
 * BootReceiver
 *
 * Restarts BlockerForegroundService when the device boots or the app is updated,
 * ensuring protection resumes automatically without the user re-launching the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) return

        val prefs = AppPreferences(context)
        if (!prefs.isBlockingEnabled()) return

        val serviceIntent = Intent(context, BlockerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
