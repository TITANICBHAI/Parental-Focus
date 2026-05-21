package com.parental.focus.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Single source of truth for all persisted state.
 *
 * Keys:
 *   blocked_packages      – JSON array of package names the child cannot open
 *   schedules             – JSON array of BlockSchedule objects
 *   face_enrolled         – Boolean: face reference has been saved
 *   face_landmark_data    – JSON array of FaceLandmarkPoint (child's face reference)
 *   blocking_enabled      – Boolean: master on/off switch
 *   parent_verified_until – Long: epoch ms until parent override is valid
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Blocked packages ──────────────────────────────────────────────────────

    fun getBlockedPackages(): Set<String> {
        val json = prefs.getString(KEY_BLOCKED_PKGS, "[]") ?: "[]"
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson<List<String>>(json, type).toSet()
    }

    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit().putString(KEY_BLOCKED_PKGS, gson.toJson(packages.toList())).apply()
    }

    fun addBlockedPackage(pkg: String) {
        val current = getBlockedPackages().toMutableSet()
        current.add(pkg)
        setBlockedPackages(current)
    }

    fun removeBlockedPackage(pkg: String) {
        val current = getBlockedPackages().toMutableSet()
        current.remove(pkg)
        setBlockedPackages(current)
    }

    // ── Schedules ─────────────────────────────────────────────────────────────

    fun getSchedules(): List<BlockSchedule> {
        val json = prefs.getString(KEY_SCHEDULES, "[]") ?: "[]"
        val type = object : TypeToken<List<BlockSchedule>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun saveSchedules(schedules: List<BlockSchedule>) {
        prefs.edit().putString(KEY_SCHEDULES, gson.toJson(schedules)).apply()
    }

    fun addSchedule(schedule: BlockSchedule) {
        val current = getSchedules().toMutableList()
        val existing = current.indexOfFirst { it.id == schedule.id }
        if (existing >= 0) current[existing] = schedule else current.add(schedule)
        saveSchedules(current)
    }

    fun removeSchedule(id: String) {
        saveSchedules(getSchedules().filter { it.id != id })
    }

    // ── Face enrollment ───────────────────────────────────────────────────────

    fun isFaceEnrolled(): Boolean = prefs.getBoolean(KEY_FACE_ENROLLED, false)

    fun saveFaceLandmarks(landmarks: List<FaceLandmarkPoint>) {
        prefs.edit()
            .putString(KEY_FACE_LANDMARKS, gson.toJson(landmarks))
            .putBoolean(KEY_FACE_ENROLLED, true)
            .apply()
    }

    fun getFaceLandmarks(): List<FaceLandmarkPoint> {
        val json = prefs.getString(KEY_FACE_LANDMARKS, "[]") ?: "[]"
        val type = object : TypeToken<List<FaceLandmarkPoint>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun clearFaceEnrollment() {
        prefs.edit()
            .remove(KEY_FACE_LANDMARKS)
            .putBoolean(KEY_FACE_ENROLLED, false)
            .apply()
    }

    // ── Master switch ─────────────────────────────────────────────────────────

    fun isBlockingEnabled(): Boolean = prefs.getBoolean(KEY_BLOCKING_ENABLED, true)

    fun setBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, enabled).apply()
    }

    // ── Parent override (temporary bypass after face verify) ─────────────────

    fun setParentVerifiedUntil(epochMs: Long) {
        prefs.edit().putLong(KEY_PARENT_VERIFIED_UNTIL, epochMs).apply()
    }

    fun isParentVerified(): Boolean {
        return System.currentTimeMillis() < prefs.getLong(KEY_PARENT_VERIFIED_UNTIL, 0L)
    }

    fun clearParentOverride() {
        prefs.edit().putLong(KEY_PARENT_VERIFIED_UNTIL, 0L).apply()
    }

    // ── Dim on block ─────────────────────────────────────────────────────────

    fun isDimOnBlockEnabled(): Boolean = prefs.getBoolean(KEY_DIM_ON_BLOCK, false)
    fun setDimOnBlock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIM_ON_BLOCK, enabled).apply()
    }

    // ── Setup complete ────────────────────────────────────────────────────────

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    fun markSetupComplete() { prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply() }

    companion object {
        private const val PREFS_FILE = "parental_focus_prefs"

        const val KEY_BLOCKED_PKGS         = "blocked_packages"
        const val KEY_SCHEDULES            = "schedules"
        const val KEY_FACE_ENROLLED        = "face_enrolled"
        const val KEY_FACE_LANDMARKS       = "face_landmark_data"
        const val KEY_BLOCKING_ENABLED     = "blocking_enabled"
        const val KEY_PARENT_VERIFIED_UNTIL = "parent_verified_until"
        const val KEY_DIM_ON_BLOCK         = "dim_on_block"
        const val KEY_SETUP_COMPLETE       = "setup_complete"
    }
}
