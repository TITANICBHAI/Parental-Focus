package com.parental.focus.data

import java.util.UUID

/**
 * A scheduled time window during which the blocked apps list is enforced.
 *
 * @param id           Unique identifier (UUID)
 * @param label        User-facing name (e.g. "Homework time")
 * @param startEpochMs Absolute epoch milliseconds when blocking begins
 * @param endEpochMs   Absolute epoch milliseconds when blocking ends
 * @param repeatDays   Bitmask of days: 0=Sun,1=Mon,...,6=Sat (0 = one-shot)
 * @param enabled      Whether this schedule is currently active
 * @param blockedPackages Optional per-schedule package override; empty = use global list
 */
data class BlockSchedule(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val startEpochMs: Long = 0L,
    val endEpochMs: Long = 0L,
    val repeatDays: Int = 0,
    val enabled: Boolean = true,
    val blockedPackages: List<String> = emptyList()
) {
    /**
     * Returns true if the given timestamp falls within [startEpochMs, endEpochMs].
     * For repeating schedules, uses only the time-of-day component.
     */
    fun isActiveAt(nowMs: Long): Boolean {
        if (!enabled) return false
        if (repeatDays == 0) {
            // One-shot: compare absolute timestamps
            return nowMs in startEpochMs..endEpochMs
        }
        // Repeating: check day-of-week bit and time-of-day window
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayBit = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        if ((repeatDays and (1 shl dayBit)) == 0) return false

        // Extract time-of-day in ms since midnight
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startEpochMs }
        val endCal   = java.util.Calendar.getInstance().apply { timeInMillis = endEpochMs }
        val startTod = (startCal.get(java.util.Calendar.HOUR_OF_DAY) * 3600
                + startCal.get(java.util.Calendar.MINUTE) * 60
                + startCal.get(java.util.Calendar.SECOND)) * 1000L
        val endTod   = (endCal.get(java.util.Calendar.HOUR_OF_DAY) * 3600
                + endCal.get(java.util.Calendar.MINUTE) * 60
                + endCal.get(java.util.Calendar.SECOND)) * 1000L
        val nowTod   = (cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600
                + cal.get(java.util.Calendar.MINUTE) * 60
                + cal.get(java.util.Calendar.SECOND)) * 1000L

        return if (startTod <= endTod) nowTod in startTod..endTod
               else nowTod >= startTod || nowTod <= endTod // crosses midnight
    }

    val isOneShot: Boolean get() = repeatDays == 0

    companion object {
        // Day-of-week bitmask constants (matching Calendar.DAY_OF_WEEK - 1)
        const val SUN = 1 shl 0
        const val MON = 1 shl 1
        const val TUE = 1 shl 2
        const val WED = 1 shl 3
        const val THU = 1 shl 4
        const val FRI = 1 shl 5
        const val SAT = 1 shl 6
        const val WEEKDAYS = MON or TUE or WED or THU or FRI
        const val WEEKEND  = SAT or SUN
        const val EVERY_DAY = WEEKDAYS or WEEKEND
    }
}

/**
 * Stores a single face landmark position (normalised 0.0–1.0 coordinates
 * relative to the detected face bounding box).
 */
data class FaceLandmarkPoint(
    val type: Int,
    val x: Float,
    val y: Float
)

/**
 * Installed app info used in the blocked-app picker UI.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean = false,
    val isSystemApp: Boolean = false
)
