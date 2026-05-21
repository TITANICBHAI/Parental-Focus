package com.parental.focus.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.parental.focus.ParentalFocusApp
import com.parental.focus.R
import com.parental.focus.data.AppPreferences
import com.parental.focus.overlay.BlockOverlayActivity
import com.parental.focus.ui.MainActivity

/**
 * AppBlockerAccessibilityService
 *
 * The core enforcement engine of Parental Focus.
 *
 * ── How blocking works ──────────────────────────────────────────────────────
 *
 *  1. Android fires TYPE_WINDOW_STATE_CHANGED every time a new activity/window
 *     appears in the foreground.
 *  2. We read the package name from the event.
 *  3. We check:
 *       a. Master blocking switch is ON.
 *       b. There is at least one active schedule whose time window includes now.
 *       c. The package is in the blocked list (global or schedule-specific).
 *       d. Parent override is NOT active (parent verified themselves recently).
 *  4. If all conditions are true:
 *       • We call performGlobalAction(GLOBAL_ACTION_HOME) — sends the user to
 *         the home screen instantly.
 *       • We launch BlockOverlayActivity over the home screen as a reminder,
 *         with the option to perform a parent face-unlock.
 *       • We schedule a retry check 400 ms later (some aggressive apps try to
 *         relaunch themselves).
 *
 * ── Never-block list ────────────────────────────────────────────────────────
 *  System apps that must always be accessible to prevent the user being locked
 *  out of their device:
 *    • Our own package
 *    • Android system UI
 *    • Device launcher (various)
 *    • Emergency dialer
 *    • Setup wizard
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private lateinit var prefs: AppPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockedPkg: String = ""
    private var lastBlockedAt: Long = 0L

    companion object {
        /** Packages that can NEVER be blocked regardless of settings. */
        private val NEVER_BLOCK = setOf(
            "com.parental.focus",
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",         // Samsung
            "com.miui.home",                         // Xiaomi
            "com.huawei.android.launcher",           // Huawei
            "com.oppo.launcher",                     // Oppo/OnePlus
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

        /** Cooldown in ms: don't re-block the same package twice within this window. */
        private const val BLOCK_COOLDOWN_MS = 1_500L

        /** How many retries to schedule after an initial block. */
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
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank()) return

        maybeBlock(pkg)
    }

    private fun maybeBlock(pkg: String) {
        if (pkg in NEVER_BLOCK) return
        if (!prefs.isBlockingEnabled()) return
        if (prefs.isParentVerified()) return

        // Check if any active schedule covers right now and includes this package
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

        // Cooldown — avoid hammering the same package
        if (pkg == lastBlockedPkg && now - lastBlockedAt < BLOCK_COOLDOWN_MS) return
        lastBlockedPkg = pkg
        lastBlockedAt = now

        enforceBlock(pkg)
    }

    private fun enforceBlock(pkg: String) {
        // 1. Fire global HOME action — returns user to launcher instantly
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Post block alert notification with full-screen intent to
        //    launch BlockOverlayActivity even on locked/sleeping devices
        postBlockNotification(pkg)

        // 3. Retry checks to catch apps that relaunch themselves
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

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(ParentalFocusApp.NOTIF_ID_BLOCK, notification)

        // Also start the activity directly (works when screen is on and unlocked)
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
