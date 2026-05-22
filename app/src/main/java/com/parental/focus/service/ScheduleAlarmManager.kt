package com.parental.focus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.parental.focus.data.BlockSchedule
import java.util.Calendar

object ScheduleAlarmManager {

    fun rescheduleAll(context: Context, schedules: List<BlockSchedule>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return

        schedules.forEach { schedule ->
            cancelAlarms(context, am, schedule)
            if (!schedule.enabled) return@forEach

            val now = System.currentTimeMillis()

            if (schedule.isOneShot) {
                if (schedule.startEpochMs > now) {
                    setExactAlarm(am, schedule.startEpochMs,
                        pendingIntent(context, "start_${schedule.id}"))
                }
                if (schedule.endEpochMs > now) {
                    setExactAlarm(am, schedule.endEpochMs,
                        pendingIntent(context, "end_${schedule.id}"))
                }
            } else {
                val nextStart = nextOccurrence(schedule.startEpochMs, schedule.repeatDays, now)
                if (nextStart != null) {
                    setExactAlarm(am, nextStart,
                        pendingIntent(context, "start_${schedule.id}"))
                }
                val nextEnd = nextOccurrence(schedule.endEpochMs, schedule.repeatDays, now)
                if (nextEnd != null) {
                    setExactAlarm(am, nextEnd,
                        pendingIntent(context, "end_${schedule.id}"))
                }
            }
        }
    }

    private fun nextOccurrence(timeMs: Long, repeatDaysMask: Int, afterMs: Long): Long? {
        val timeCal = Calendar.getInstance().apply { timeInMillis = timeMs }
        val hour   = timeCal.get(Calendar.HOUR_OF_DAY)
        val minute = timeCal.get(Calendar.MINUTE)
        val second = timeCal.get(Calendar.SECOND)

        for (daysAhead in 0..7) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = afterMs
                add(Calendar.DAY_OF_YEAR, daysAhead)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, second)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= afterMs) continue
            val dayBit = candidate.get(Calendar.DAY_OF_WEEK) - 1
            if ((repeatDaysMask and (1 shl dayBit)) != 0) {
                return candidate.timeInMillis
            }
        }
        return null
    }

    private fun setExactAlarm(am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
        // Android 12+ requires SCHEDULE_EXACT_ALARM permission; guard before calling setExact.
        // If exact alarms aren't permitted, fall back to a 15-minute inexact window so the
        // app never throws SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerMs, 15 * 60 * 1000L, pi)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun cancelAlarms(context: Context, am: AlarmManager, schedule: BlockSchedule) {
        am.cancel(pendingIntent(context, "start_${schedule.id}"))
        am.cancel(pendingIntent(context, "end_${schedule.id}"))
    }

    private fun pendingIntent(context: Context, tag: String): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = "com.parental.focus.SCHEDULE_ALARM"
            putExtra("alarm_tag", tag)
        }
        return PendingIntent.getBroadcast(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
