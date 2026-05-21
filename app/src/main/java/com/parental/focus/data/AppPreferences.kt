package com.parental.focus.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Single source of truth for all persisted state.
 *
 * Face storage (v2):
 *   face_embedding_v2  – JSON array of 128 floats (FaceNet L2-normalised embedding)
 *   face_enrolled      – Boolean guard
 *
 *   The old key `face_landmark_data` stored 8 ML Kit landmark points.  It is no
 *   longer written; callers that read the new key will get an empty array if only
 *   the old key is present, triggering a fresh enrollment.
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
        setBlockedPackages(getBlockedPackages().toMutableSet().also { it.add(pkg) })
    }

    fun removeBlockedPackage(pkg: String) {
        setBlockedPackages(getBlockedPackages().toMutableSet().also { it.remove(pkg) })
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
        val idx = current.indexOfFirst { it.id == schedule.id }
        if (idx >= 0) current[idx] = schedule else current.add(schedule)
        saveSchedules(current)
    }

    fun removeSchedule(id: String) {
        saveSchedules(getSchedules().filter { it.id != id })
    }

    // ── Face enrollment (v2 — FaceNet embedding) ──────────────────────────────

    fun isFaceEnrolled(): Boolean = prefs.getBoolean(KEY_FACE_ENROLLED, false)

    /**
     * Persist the 128-dim FaceNet embedding as a JSON float array.
     * Also clears any legacy landmark data left from v1.
     */
    fun saveFaceEmbedding(embedding: FloatArray) {
        prefs.edit()
            .putString(KEY_FACE_EMBEDDING, gson.toJson(embedding.toList()))
            .putBoolean(KEY_FACE_ENROLLED, true)
            .remove(KEY_FACE_LANDMARKS_LEGACY)  // remove old format
            .apply()
    }

    /**
     * Retrieve the stored 128-dim embedding, or an empty array if none exists
     * (triggers re-enrollment in the UI).
     */
    fun getFaceEmbedding(): FloatArray {
        val json = prefs.getString(KEY_FACE_EMBEDDING, null) ?: return FloatArray(0)
        val type = object : TypeToken<List<Float>>() {}.type
        val list: List<Float> = gson.fromJson(json, type) ?: return FloatArray(0)
        return list.toFloatArray()
    }

    fun clearFaceEnrollment() {
        prefs.edit()
            .remove(KEY_FACE_EMBEDDING)
            .remove(KEY_FACE_LANDMARKS_LEGACY)
            .putBoolean(KEY_FACE_ENROLLED, false)
            .apply()
    }

    // ── Master switch ─────────────────────────────────────────────────────────

    fun isBlockingEnabled(): Boolean = prefs.getBoolean(KEY_BLOCKING_ENABLED, true)

    fun setBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, enabled).apply()
    }

    // ── Parent override ───────────────────────────────────────────────────────

    fun setParentVerifiedUntil(epochMs: Long) {
        prefs.edit().putLong(KEY_PARENT_VERIFIED_UNTIL, epochMs).apply()
    }

    fun isParentVerified(): Boolean =
        System.currentTimeMillis() < prefs.getLong(KEY_PARENT_VERIFIED_UNTIL, 0L)

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

        const val KEY_BLOCKED_PKGS          = "blocked_packages"
        const val KEY_SCHEDULES             = "schedules"
        const val KEY_FACE_ENROLLED         = "face_enrolled"
        const val KEY_FACE_EMBEDDING        = "face_embedding_v2"   // 128-dim FloatArray JSON
        const val KEY_FACE_LANDMARKS_LEGACY = "face_landmark_data"  // old v1 key — only removed
        const val KEY_BLOCKING_ENABLED      = "blocking_enabled"
        const val KEY_PARENT_VERIFIED_UNTIL = "parent_verified_until"
        const val KEY_DIM_ON_BLOCK          = "dim_on_block"
        const val KEY_SETUP_COMPLETE        = "setup_complete"
    }
}
