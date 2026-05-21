package com.parental.focus.service

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings

/**
 * ScreenBrightnessManager
 *
 * Reads and writes the screen brightness via [Settings.System.SCREEN_BRIGHTNESS].
 *
 * Requires the WRITE_SETTINGS permission (granted via
 * Settings.ACTION_MANAGE_WRITE_SETTINGS on API 23+).
 *
 * Usage in AppBlockerAccessibilityService:
 *   When [AppPreferences.isDimOnBlockEnabled] is true, call [dim] when a block
 *   fires. Call [restore] when the block overlay is dismissed or the parent
 *   override is granted.
 *
 * Brightness scale: 0–255 (system default is typically 102–153).
 */
object ScreenBrightnessManager {

    private const val DIM_VALUE = 20
    private const val PREFS_KEY_SAVED = "saved_brightness"

    /**
     * Switch to manual brightness mode and set brightness to [DIM_VALUE].
     * Saves the current brightness value so [restore] can undo it.
     */
    fun dim(context: Context) {
        if (!canWrite(context)) return
        val resolver = context.contentResolver

        // Save current value before dimming
        val current = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) { 128 }

        context.getSharedPreferences("pf_brightness", Context.MODE_PRIVATE)
            .edit().putInt(PREFS_KEY_SAVED, current).apply()

        // Switch to manual mode so our write takes effect
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, DIM_VALUE)
    }

    /**
     * Restore brightness to the value saved before [dim] was called.
     * Also restores automatic brightness if it was active before.
     */
    fun restore(context: Context) {
        if (!canWrite(context)) return
        val resolver = context.contentResolver

        val saved = context.getSharedPreferences("pf_brightness", Context.MODE_PRIVATE)
            .getInt(PREFS_KEY_SAVED, -1)

        if (saved >= 0) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, saved)
        }
        // Re-enable auto-brightness
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        )
    }

    private fun canWrite(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }
}
