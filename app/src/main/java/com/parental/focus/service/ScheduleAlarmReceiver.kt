package com.parental.focus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * ScheduleAlarmReceiver
 *
 * Fired by AlarmManager when a schedule's start or end time arrives.
 * Ensures BlockerForegroundService (and thus the accessibility service) is
 * running at the precise moment a blocking window opens.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, BlockerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
