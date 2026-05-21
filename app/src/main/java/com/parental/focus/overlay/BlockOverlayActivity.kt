package com.parental.focus.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.parental.focus.R
import com.parental.focus.data.AppPreferences
import com.parental.focus.face.FaceUtils
import com.parental.focus.service.ScreenBrightnessManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * BlockOverlayActivity
 *
 * Full-screen overlay presented whenever the accessibility service detects a
 * blocked app in the foreground.
 *
 * ── Layout (built programmatically, no XML required) ──────────────────────
 *   • Dark gradient background
 *   • Shield icon + "Focus Time" title
 *   • App name that was blocked
 *   • Motivational quote
 *   • "Verify Parent Face" button — expands a front-camera preview and uses
 *     ML Kit to match the parent's enrolled face; a successful match grants a
 *     5-minute parent override and dismisses the overlay.
 *
 * ── Back button ────────────────────────────────────────────────────────────
 *   Fully swallowed. Only a parent face match can dismiss the overlay.
 *
 * ── Camera lifecycle ───────────────────────────────────────────────────────
 *   We implement LifecycleOwner directly so CameraX can bind without needing
 *   a Fragment or AppCompatActivity.
 */
class BlockOverlayActivity : Activity(), LifecycleOwner {

    companion object {
        const val EXTRA_BLOCKED_PKG  = "blocked_pkg"
        const val EXTRA_BLOCKED_NAME = "blocked_name"

        /** Parent override duration after a successful face match (5 minutes). */
        private const val PARENT_OVERRIDE_DURATION_MS = 5 * 60 * 1000L

        private val MOTIVATIONAL_QUOTES = listOf(
            "The present moment is the only time over which we have dominion.",
            "Focus is the art of knowing what to ignore.",
            "Deep work is the superpower of the 21st century.",
            "Your future self is watching. Don't let them down.",
            "Discipline is choosing between what you want now and what you want most.",
            "Where attention goes, energy flows.",
            "Every moment of resistance makes you stronger.",
            "Small steps forward every day. Stay the course.",
            "Protect your focus like you protect your time.",
            "The shortcut is always longer."
        )
    }

    private lateinit var prefs: AppPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraOpen = false
    private var verifyStatusText: TextView? = null

    // ── LifecycleOwner implementation for CameraX ─────────────────────────────
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(applicationContext)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)

        applyWindowFlags()
        setContentView(buildLayout())
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        shutdownCamera()
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        handler.removeCallbacksAndMessages(null)
        shutdownCamera()
        super.onDestroy()
    }

    // Back button is completely swallowed — only face verify can dismiss
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* intentionally empty */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window flags — render over everything including lock screen
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        // Full-screen immersive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI (programmatic layout)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun buildLayout(): View {
        val blockedName = intent?.getStringExtra(EXTRA_BLOCKED_NAME) ?: "This app"
        val quote = MOTIVATIONAL_QUOTES.random()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A2A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Central content card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(48), dp(32), dp(32))
        }

        // Shield emoji / icon
        card.addView(TextView(this).apply {
            text = "🛡️"
            textSize = 56f
            gravity = Gravity.CENTER
        })

        // Title
        card.addView(TextView(this).apply {
            text = getString(R.string.overlay_title)
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })

        // Blocked app name
        card.addView(TextView(this).apply {
            text = "$blockedName is restricted right now."
            textSize = 16f
            setTextColor(Color.parseColor("#BBDEFB"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })

        // Divider
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#334DB3E8"))
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(1))
        })

        // Quote
        card.addView(TextView(this).apply {
            text = "\"$quote\""
            textSize = 14f
            setTextColor(Color.parseColor("#AACCCCCC"))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(40))
        })

        // Camera preview (initially GONE)
        val cameraContainer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(300)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        }
        val previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        cameraContainer.addView(previewView)
        card.addView(cameraContainer)

        // Verify status text (shown after scan attempt)
        val statusText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        verifyStatusText = statusText
        card.addView(statusText)

        // Parent verify button (only shown if face is enrolled)
        if (prefs.isFaceEnrolled()) {
            card.addView(Button(this).apply {
                text = getString(R.string.overlay_verify_face)
                setBackgroundColor(Color.parseColor("#1565C0"))
                setTextColor(Color.WHITE)
                textSize = 15f
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                layoutParams = lp
                setPadding(dp(24), dp(12), dp(24), dp(12))
                setOnClickListener {
                    if (!isCameraOpen) {
                        cameraContainer.visibility = View.VISIBLE
                        openCameraForVerification(previewView)
                    }
                }
            })
        }

        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        return root
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera + face verification
    // ─────────────────────────────────────────────────────────────────────────

    private fun openCameraForVerification(previewView: PreviewView) {
        if (isCameraOpen) return
        isCameraOpen = true
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                showVerifyStatus(getString(R.string.overlay_not_verified), success = false)
                return@addListener
            }

            // Capture after a 1.5 s warm-up delay
            handler.postDelayed({
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bitmap = image.toBitmap()
                            image.close()
                            val reference = prefs.getFaceEmbedding()
                            FaceUtils.verifyFace(bitmap, reference, applicationContext) { match ->
                                handler.post { handleVerificationResult(match) }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            handler.post {
                                showVerifyStatus(getString(R.string.overlay_not_verified), success = false)
                            }
                        }
                    }
                )
            }, 1_500L)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleVerificationResult(match: Boolean?) {
        shutdownCamera()
        when (match) {
            true -> {
                showVerifyStatus(getString(R.string.overlay_verified), success = true)
                prefs.setParentVerifiedUntil(System.currentTimeMillis() + PARENT_OVERRIDE_DURATION_MS)
                // Restore screen brightness before dismissing the overlay
                ScreenBrightnessManager.restore(applicationContext)
                handler.postDelayed({ finish() }, 1_200L)
            }
            false -> {
                showVerifyStatus(getString(R.string.overlay_not_verified), success = false)
                isCameraOpen = false
            }
            null -> {
                showVerifyStatus("No face detected. Try again.", success = false)
                isCameraOpen = false
            }
        }
    }

    private fun showVerifyStatus(message: String, success: Boolean) {
        verifyStatusText?.apply {
            text = message
            setTextColor(if (success) Color.parseColor("#A5D6A7") else Color.parseColor("#EF9A9A"))
            visibility = View.VISIBLE
            startAnimation(AlphaAnimation(0f, 1f).apply { duration = 300 })
        }
    }

    private fun shutdownCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
