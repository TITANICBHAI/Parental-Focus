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
 * Guides the parent through enrolling the child's face as the reference identity.
 *
 * Flow:
 *  1. Request CAMERA permission if not granted.
 *  2. Show front-camera live preview — user frames the child's face.
 *  3. User taps "Capture" → CameraX takes a still photo.
 *  4. ML Kit detects the face bounding box, crops it with 25% padding.
 *  5. TFLite FaceNet model produces a 128-dimensional embedding vector.
 *  6. Embedding is persisted via [AppPreferences.saveFaceEmbedding].
 *
 * On first run the FaceNet model (~2 MB) is downloaded automatically in the
 * background.  A spinner is shown while the model is loading/downloading.
 */
class FaceEnrollmentActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button
    private lateinit var progressBar: ProgressBar

    private var cameraExecutor: ExecutorService? = null
    private var imageCapture: ImageCapture? = null
    private val handler = Handler(Looper.getMainLooper())

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                statusText.text = "Camera permission is required to enroll a face."
                statusText.setTextColor(Color.parseColor("#EF5350"))
            }
        }

    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(applicationContext)
        setContentView(buildLayout())
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Show download notice if model hasn't been cached yet
        if (!FaceUtils.isModelReady(this)) {
            statusText.text = "Downloading face model (~2 MB) on first use…"
            statusText.setTextColor(Color.parseColor("#FFA726"))
        }

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

    // ── Camera ────────────────────────────────────────────────────────────────

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
                if (statusText.text.toString().startsWith("Downloading")) {
                    statusText.text = ""
                }
            } catch (e: Exception) {
                statusText.text = "Failed to start camera: ${e.message}"
                statusText.setTextColor(Color.parseColor("#EF5350"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndEnroll() {
        val ic = imageCapture ?: return
        captureButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        if (!FaceUtils.isModelReady(this)) {
            statusText.text = "Downloading face model… please wait."
            statusText.setTextColor(Color.parseColor("#FFA726"))
        } else {
            statusText.text = "Analysing face…"
            statusText.setTextColor(Color.parseColor("#FFA726"))
        }

        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()

                    // FaceUtils.enrollFace handles:
                    //  - ML Kit face detection + crop
                    //  - TFLite FaceNet model load (+ download if needed)
                    //  - 128-dim embedding inference
                    FaceUtils.enrollFace(bitmap, applicationContext) { embedding ->
                        handler.post {
                            progressBar.visibility = View.GONE
                            if (embedding != null && embedding.isNotEmpty()) {
                                prefs.saveFaceEmbedding(embedding)
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
                        progressBar.visibility = View.GONE
                        statusText.text = "Capture failed. Please try again."
                        statusText.setTextColor(Color.parseColor("#EF5350"))
                        captureButton.isEnabled = true
                    }
                }
            }
        )
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun buildLayout(): android.view.View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.face_enroll_title)
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.face_enroll_subtitle)
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(24))
        })

        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(380)
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        container.addView(previewView)

        container.addView(TextView(this).apply {
            text = getString(R.string.face_enroll_tip)
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        })

        // Model note — explains TFLite download to the user
        container.addView(TextView(this).apply {
            text = "Face recognition model: TFLite FaceNet (128-dim embedding, ~2 MB, cached after first use)"
            textSize = 11f
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        statusText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        container.addView(statusText)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
        }
        container.addView(progressBar)

        captureButton = Button(this).apply {
            text = getString(R.string.face_enroll_btn)
            isEnabled = false
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { captureAndEnroll() }
        }
        container.addView(captureButton)

        if (prefs.isFaceEnrolled()) {
            container.addView(TextView(this).apply {
                text = "A face is already enrolled. Capturing a new face will replace it."
                textSize = 12f
                setTextColor(Color.parseColor("#FFA726"))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }

        root.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return root
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
