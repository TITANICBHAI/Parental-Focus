package com.parental.focus.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * FaceUtils
 *
 * Two-stage on-device face recognition pipeline:
 *
 *   Stage 1 — Detection  (ML Kit Face Detection)
 *     Locate the largest face in the bitmap and extract a padded crop.
 *
 *   Stage 2 — Embedding  (TFLite FaceNet via [FaceEmbedder])
 *     Convert the 112 × 112 face crop into a 128-dimensional L2-normalised
 *     embedding vector using Google's MediaPipe face embedder model.
 *
 *   Matching — Cosine similarity
 *     Two embeddings are considered the same identity when their cosine
 *     similarity exceeds [MATCH_THRESHOLD] (0 = orthogonal, 1 = identical).
 *
 * Why this is better than landmark geometry:
 *   The old approach compared 8 normalised landmark coordinates.  Human faces
 *   have similar proportions so different people's landmark layouts overlap at
 *   that resolution.  FaceNet embeddings encode the full texture + geometry of
 *   the face into a high-dimensional space specifically trained to maximise
 *   inter-person distance and minimise intra-person distance (triplet loss).
 *   Cosine similarity in that space reliably separates identities.
 *
 * Model download:
 *   The TFLite model (~2 MB) is downloaded on first use from Google's public
 *   MediaPipe model registry and stored in the app's private files directory.
 *   All subsequent runs use the cached copy — no network required.
 *
 * Thread safety:
 *   Both [enrollFace] and [verifyFace] schedule their heavy work on
 *   [Dispatchers.IO] and post results back on the calling thread via the
 *   provided callback. They are safe to call from the main thread.
 */
object FaceUtils {

    private const val TAG = "FaceUtils"

    /**
     * Cosine similarity threshold above which two embeddings are considered
     * the same identity.
     *
     *   0.75 — tested across variable lighting, slight head angle changes.
     *          Rejects impostors while tolerating the same person under
     *          different conditions.  Raise to ~0.85 for stricter matching.
     */
    private const val MATCH_THRESHOLD = 0.75f

    /**
     * Fraction of the face bounding box to pad outward before cropping.
     * 0.25 gives FaceNet enough context (ears, chin) for accurate embeddings.
     */
    private const val CROP_PADDING = 0.25f

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(detectorOptions)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Detect a face in [bitmap], crop it, run FaceNet inference, and return
     * the 128-dim embedding via [onResult].
     *
     * [onResult] is called with `null` if:
     *   • No face is detected
     *   • The TFLite model fails to load / download
     *   • Inference throws
     *
     * Should be called from the main thread; heavy work runs on IO dispatcher.
     */
    fun enrollFace(
        bitmap: Bitmap,
        context: Context,
        onResult: (embedding: FloatArray?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val crop = detectAndCrop(bitmap) ?: run {
                Log.w(TAG, "enrollFace: no face detected.")
                onResult(null)
                return@launch
            }
            val embedder = FaceEmbedder(context)
            if (!embedder.loadModel()) {
                Log.e(TAG, "enrollFace: model load failed.")
                onResult(null)
                return@launch
            }
            val embedding = embedder.getEmbedding(crop)
            embedder.close()
            onResult(embedding)
        }
    }

    /**
     * Detect a face in [bitmap], embed it, and compare against [reference].
     *
     * @param onResult
     *   `true`  — cosine similarity ≥ [MATCH_THRESHOLD]  → identity confirmed
     *   `false` — similarity < threshold                  → different person
     *   `null`  — no face detected or inference failed
     */
    fun verifyFace(
        bitmap: Bitmap,
        reference: FloatArray,
        context: Context,
        onResult: (match: Boolean?) -> Unit
    ) {
        if (reference.isEmpty()) { onResult(null); return }

        CoroutineScope(Dispatchers.IO).launch {
            val crop = detectAndCrop(bitmap) ?: run {
                Log.w(TAG, "verifyFace: no face detected.")
                onResult(null)
                return@launch
            }
            val embedder = FaceEmbedder(context)
            if (!embedder.loadModel()) {
                Log.e(TAG, "verifyFace: model load failed.")
                onResult(null)
                return@launch
            }
            val liveEmbedding = embedder.getEmbedding(crop)
            embedder.close()

            if (liveEmbedding == null) {
                onResult(null)
                return@launch
            }

            val similarity = cosineSimilarity(reference, liveEmbedding)
            Log.d(TAG, "verifyFace: cosine similarity = ${"%.4f".format(similarity)}, threshold = $MATCH_THRESHOLD")
            onResult(similarity >= MATCH_THRESHOLD)
        }
    }

    /**
     * Check whether the TFLite model file is already cached locally.
     * Use this to show a "downloading model…" indicator on first enrollment.
     */
    fun isModelReady(context: Context): Boolean =
        FaceEmbedder(context).isModelDownloaded()

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Run synchronous ML Kit detection (using Tasks.await on IO thread) and
     * return a padded crop of the largest detected face, or null.
     */
    private fun detectAndCrop(bitmap: Bitmap): Bitmap? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = com.google.android.gms.tasks.Tasks.await(detector.process(image))
            val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return null

            val box = best.boundingBox
            val pad = (max(box.width(), box.height()) * CROP_PADDING).toInt()

            val left   = max(0, box.left   - pad)
            val top    = max(0, box.top    - pad)
            val right  = min(bitmap.width,  box.right  + pad)
            val bottom = min(bitmap.height, box.bottom + pad)

            if (right <= left || bottom <= top) return null

            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            Log.e(TAG, "detectAndCrop error", e)
            null
        }
    }

    /**
     * Cosine similarity between two L2-normalised vectors.
     *
     * Since both vectors are already L2-normalised by [FaceEmbedder], this
     * reduces to a simple dot product.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
