package com.parental.focus.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.parental.focus.ParentalFocusApp
import com.parental.focus.R
import com.parental.focus.data.AppPreferences
import com.parental.focus.overlay.BlockOverlayActivity

/**
 * AppBlockerAccessibilityService
 *
 * Core enforcement engine. Listens for TYPE_WINDOW_STATE_CHANGED events,
 * checks whether the foreground package is in the blocked list during an
 * active schedule, and fires GLOBAL_ACTION_HOME to redirect the user.
 *
 * Features:
 *   • Never-block list — system UI, launcher, dialer, our own package
 *   • Cooldown — same package can't be re-blocked within BLOCK_COOLDOWN_MS
 *   • Retry — 4 re-checks at 400 ms intervals catch self-relaunching apps
 *   • Screen dim — optionally lowers brightness on block (WRITE_SETTINGS)
 *   • Parent override — suppressed while isParentVerified() returns true
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private lateinit var prefs: AppPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockedPkg: String = ""
    private var lastBlockedAt: Long = 0L

    companion object {
        private val NEVER_BLOCK = setOf(
            "com.parental.focus",
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.oneplus.launcher",
            "com.android.emergency",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "android",
            "com.android.server.telecom"
        )

        private const val BLOCK_COOLDOWN_MS = 1_500L
        private const val RETRY_COUNT = 4
        private const val RETRY_INTERVAL_MS = 400L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPreferences(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank()) return

        maybeBlock(pkg)
    }

    private fun maybeBlock(pkg: String) {
        if (pkg in NEVER_BLOCK) return
        if (!prefs.isBlockingEnabled()) return
        if (prefs.isParentVerified()) return

        val now = System.currentTimeMillis()
        val schedules = prefs.getSchedules()
        val globalBlocked = prefs.getBlockedPackages()

        val shouldBlock = schedules.any { schedule ->
            if (!schedule.isActiveAt(now)) return@any false
            val blockedPkgs = if (schedule.blockedPackages.isNotEmpty())
                schedule.blockedPackages.toSet()
            else
                globalBlocked
            pkg in blockedPkgs
        }

        if (!shouldBlock) return

        if (pkg == lastBlockedPkg && now - lastBlockedAt < BLOCK_COOLDOWN_MS) return
        lastBlockedPkg = pkg
        lastBlockedAt = now

        enforceBlock(pkg)
    }

    private fun enforceBlock(pkg: String) {
        // 1. Send user home immediately
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Dim screen if the setting is enabled
        if (prefs.isDimOnBlockEnabled()) {
            ScreenBrightnessManager.dim(applicationContext)
        }

        // 3. Show block overlay via full-screen notification
        postBlockNotification(pkg)

        // 4. Retry checks in case the app relaunches itself
        for (i in 1..RETRY_COUNT) {
            handler.postDelayed({ maybeBlock(pkg) }, RETRY_INTERVAL_MS * i)
        }
    }

    private fun postBlockNotification(blockedPkg: String) {
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(blockedPkg, 0)
            ).toString()
        } catch (e: Exception) { blockedPkg }

        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PKG, blockedPkg)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_NAME, appName)
        }

        val pi = PendingIntent.getActivity(
            this, 0, overlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ParentalFocusApp.CHANNEL_BLOCK_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_title_blocking))
            .setContentText("$appName is restricted right now.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(
            ParentalFocusApp.NOTIF_ID_BLOCK, notification
        )

        // Also start directly (works when screen is on and unlocked)
        startActivity(overlayIntent)
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
