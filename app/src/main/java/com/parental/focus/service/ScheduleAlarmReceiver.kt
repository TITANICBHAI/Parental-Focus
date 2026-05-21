package com.parental.focus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.parental.focus.data.AppPreferences

/**
 * ScheduleAlarmReceiver
 *
 * Fired by AlarmManager at each schedule's start or end time.
 *
 * On receipt:
 *   1. Wakes BlockerForegroundService so accessibility enforcement is running.
 *   2. Reschedules all alarms via [ScheduleAlarmManager] so the next occurrence
 *      is automatically set (critical for repeating schedules).
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Ensure the foreground blocking service is alive
        val serviceIntent = Intent(context, BlockerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Reschedule next occurrence for all enabled schedules
        val prefs = AppPreferences(context)
        ScheduleAlarmManager.rescheduleAll(context, prefs.getSchedules())
    }
}
