package com.parental.focus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.parental.focus.data.AppPreferences

/**
 * BootReceiver
 *
 * Fires on device boot, quick-boot, and app update.
 *
 * Responsibilities:
 *   1. Restart BlockerForegroundService so blocking resumes automatically.
 *   2. Reschedule all AlarmManager alarms — they do not survive reboots and
 *      must be re-registered every time the device starts.
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

        // 1. Re-start the foreground service
        val serviceIntent = Intent(context, BlockerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 2. Re-register all schedule alarms (cleared by the OS on reboot)
        ScheduleAlarmManager.rescheduleAll(context, prefs.getSchedules())
    }
}
