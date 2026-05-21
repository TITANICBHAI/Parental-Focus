package com.parental.focus.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.parental.focus.R
import com.parental.focus.data.AppPreferences
import com.parental.focus.face.FaceUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FaceEnrollmentActivity
 *
 * Guides the user through enrolling a child's face as the blocking-target identity.
 *
 * Flow:
 *  1. Request CAMERA permission if not granted.
 *  2. Show front-camera preview so the user can frame the child's face.
 *  3. User taps "Capture Face" → take a photo → run ML Kit face detection.
 *  4. On success: save normalised landmark signature to AppPreferences and finish.
 *  5. On failure: prompt to retry.
 */
class FaceEnrollmentActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button

    private var cameraExecutor: ExecutorService? = null
    private var imageCapture: ImageCapture? = null
    private val handler = Handler(Looper.getMainLooper())

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                statusText.text = "Camera permission is required to enroll a face."
                statusText.setTextColor(Color.parseColor("#EF5350"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(applicationContext)
        setContentView(buildLayout())
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
        handler.removeCallbacksAndMessages(null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                captureButton.isEnabled = true
            } catch (e: Exception) {
                statusText.text = "Failed to start camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndEnroll() {
        val ic = imageCapture ?: return
        captureButton.isEnabled = false
        statusText.text = "Analysing face…"
        statusText.setTextColor(Color.parseColor("#FFA726"))

        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()

                    FaceUtils.extractSignature(bitmap) { signature ->
                        handler.post {
                            if (signature != null && signature.isNotEmpty()) {
                                prefs.saveFaceLandmarks(signature)
                                statusText.text = getString(R.string.face_enroll_success)
                                statusText.setTextColor(Color.parseColor("#66BB6A"))
                                handler.postDelayed({
                                    setResult(RESULT_OK)
                                    finish()
                                }, 1_500L)
                            } else {
                                statusText.text = getString(R.string.face_enroll_fail)
                                statusText.setTextColor(Color.parseColor("#EF5350"))
                                captureButton.isEnabled = true
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    handler.post {
                        statusText.text = "Capture failed. Please try again."
                        statusText.setTextColor(Color.parseColor("#EF5350"))
                        captureButton.isEnabled = true
                    }
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI (programmatic layout)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLayout(): android.view.View {
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        // Title
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.face_enroll_title)
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        // Subtitle
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.face_enroll_subtitle)
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(24))
        })

        // Camera preview
        previewView = PreviewView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(380)
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        container.addView(previewView)

        // Tip text
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.face_enroll_tip)
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        })

        // Status text
        statusText = android.widget.TextView(this).apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        container.addView(statusText)

        // Capture button
        captureButton = android.widget.Button(this).apply {
            text = getString(R.string.face_enroll_btn)
            isEnabled = false
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            textSize = 16f
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { captureAndEnroll() }
        }
        container.addView(captureButton)

        // Re-enroll note (if already enrolled)
        if (prefs.isFaceEnrolled()) {
            container.addView(android.widget.TextView(this).apply {
                text = "A face is already enrolled. Capturing a new face will replace it."
                textSize = 12f
                setTextColor(Color.parseColor("#FFA726"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }

        root.addView(container, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return root
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
