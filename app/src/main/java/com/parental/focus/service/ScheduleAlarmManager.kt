package com.parental.focus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.parental.focus.data.BlockSchedule
import java.util.Calendar

/**
 * ScheduleAlarmManager
 *
 * Schedules exact AlarmManager alarms for each BlockSchedule's start and end time.
 * This ensures the blocking service is woken up precisely when a schedule window
 * opens or closes — even if the device is idle and no accessibility events fire.
 *
 * Call [rescheduleAll] whenever the schedule list changes (add / edit / delete / toggle).
 * It cancels all existing alarms and re-registers only the enabled schedules.
 *
 * Alarm IDs:
 *   Each schedule gets two alarms:
 *     • startAlarmId = hashCode of "start_${schedule.id}"
 *     • endAlarmId   = hashCode of "end_${schedule.id}"
 *
 * Repeating schedules:
 *   For day-of-week schedules we walk the next 7 days and schedule the closest
 *   upcoming start time. The alarm receiver re-schedules the next occurrence on
 *   each fire via [rescheduleAll].
 *
 * One-shot schedules:
 *   Scheduled directly at startEpochMs / endEpochMs. Not rescheduled after firing.
 */
object ScheduleAlarmManager {

    fun rescheduleAll(context: Context, schedules: List<BlockSchedule>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return

        schedules.forEach { schedule ->
            cancelAlarms(context, am, schedule)
            if (!schedule.enabled) return@forEach

            val now = System.currentTimeMillis()

            if (schedule.isOneShot) {
                // One-shot: schedule start alarm if it hasn't passed yet
                if (schedule.startEpochMs > now) {
                    setExactAlarm(context, am, schedule.startEpochMs,
                        pendingIntent(context, "start_${schedule.id}"))
                }
                if (schedule.endEpochMs > now) {
                    setExactAlarm(context, am, schedule.endEpochMs,
                        pendingIntent(context, "end_${schedule.id}"))
                }
            } else {
                // Repeating: find the next start time within the next 7 days
                val nextStart = nextOccurrence(schedule.startEpochMs, schedule.repeatDays, now)
                if (nextStart != null) {
                    setExactAlarm(context, am, nextStart,
                        pendingIntent(context, "start_${schedule.id}"))
                }
                val nextEnd = nextOccurrence(schedule.endEpochMs, schedule.repeatDays, now)
                if (nextEnd != null) {
                    setExactAlarm(context, am, nextEnd,
                        pendingIntent(context, "end_${schedule.id}"))
                }
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns the next epoch ms at which [timeMs] (time-of-day) occurs on any
     * day included in [repeatDaysMask], starting from [afterMs].
     * Returns null if no matching day is found within the next 7 days.
     */
    private fun nextOccurrence(timeMs: Long, repeatDaysMask: Int, afterMs: Long): Long? {
        val timeCal = Calendar.getInstance().apply { timeInMillis = timeMs }
        val hour   = timeCal.get(Calendar.HOUR_OF_DAY)
        val minute = timeCal.get(Calendar.MINUTE)
        val second = timeCal.get(Calendar.SECOND)

        val now = Calendar.getInstance().apply { timeInMillis = afterMs }

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

    private fun setExactAlarm(context: Context, am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
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
