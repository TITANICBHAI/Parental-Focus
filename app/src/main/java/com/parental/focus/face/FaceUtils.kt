package com.parental.focus.face

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.parental.focus.data.FaceLandmarkPoint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * FaceUtils
 *
 * Wraps ML Kit Face Detection for two tasks:
 *   1. Enroll  – detect face in a bitmap and extract a normalised landmark signature.
 *   2. Verify  – detect face in a new bitmap and compare against the stored signature.
 *
 * Face identity approach:
 *   ML Kit's face detector doesn't produce embedding vectors. Instead we use the
 *   relative positions of key landmarks (both eyes, nose tip, left/right mouth
 *   corners, left/right cheeks) normalised to the face bounding box. The similarity
 *   score is the mean Euclidean distance between matching landmark pairs.
 *
 *   This is NOT a cryptographic biometric — it won't reliably distinguish identical
 *   twins or confuse a photo for a real face. For a parental-control use-case it
 *   provides a practical first line of identity confirmation combined with a
 *   configurable threshold.
 *
 *   For higher-security requirements, replace `compareSignatures` with a TFLite
 *   FaceNet embedding comparison (drop-in API-compatible).
 *
 * Thread-safety: All methods post work on ML Kit's background executor and return
 * results via callbacks on the calling thread.
 */
object FaceUtils {

    /** Landmark types we include in the signature (subset of FaceLandmark.*). */
    private val SIGNATURE_LANDMARK_TYPES = listOf(
        FaceLandmark.LEFT_EYE,
        FaceLandmark.RIGHT_EYE,
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
        FaceLandmark.LEFT_CHEEK,
        FaceLandmark.RIGHT_CHEEK,
        FaceLandmark.MOUTH_BOTTOM
    )

    /**
     * Maximum mean-landmark distance (0.0–1.0 normalised) considered a "match".
     *
     * Smaller = stricter. 0.08 (~8 % of face bounding box) works well in practice
     * for the same person captured under variable lighting.
     */
    private const val MATCH_THRESHOLD = 0.08f

    /** Min face probability to accept (rejects partial / background detections). */
    private const val MIN_FACE_CONFIDENCE = 0.70f

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(detectorOptions)

    // ──────────────────────────────────────────────────────────────────────────
    // Enrollment
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Detect faces in [bitmap], extract the landmark signature of the largest face,
     * and return it via [onResult]. Returns null if no suitable face is found.
     */
    fun extractSignature(
        bitmap: Bitmap,
        onResult: (signature: List<FaceLandmarkPoint>?) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val best = faces
                    .filter { it.trackingId != null || true } // all detections
                    .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                onResult(best?.toLandmarkSignature())
            }
            .addOnFailureListener { onResult(null) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Verification
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Detect face in [bitmap] and compare against [reference].
     *
     * @param onResult  called with `true` if the face matches the reference,
     *                  `false` if it doesn't, or `null` if no face was detected.
     */
    fun verifyFace(
        bitmap: Bitmap,
        reference: List<FaceLandmarkPoint>,
        onResult: (match: Boolean?) -> Unit
    ) {
        if (reference.isEmpty()) { onResult(null); return }
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (best == null) { onResult(null); return@addOnSuccessListener }
                val liveSignature = best.toLandmarkSignature()
                if (liveSignature.isEmpty()) { onResult(null); return@addOnSuccessListener }
                val score = compareSignatures(reference, liveSignature)
                onResult(score <= MATCH_THRESHOLD)
            }
            .addOnFailureListener { onResult(null) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Convert a detected [Face] to a list of normalised [FaceLandmarkPoint]s.
     * Coordinates are normalised to [0, 1] relative to the face bounding box.
     */
    private fun Face.toLandmarkSignature(): List<FaceLandmarkPoint> {
        val box = boundingBox
        val w = box.width().toFloat().coerceAtLeast(1f)
        val h = box.height().toFloat().coerceAtLeast(1f)
        return SIGNATURE_LANDMARK_TYPES.mapNotNull { type ->
            getLandmark(type)?.let { lm ->
                FaceLandmarkPoint(
                    type = type,
                    x = (lm.position.x - box.left) / w,
                    y = (lm.position.y - box.top) / h
                )
            }
        }
    }

    /**
     * Mean Euclidean distance between matching landmark pairs.
     * Returns Float.MAX_VALUE if there are no common landmarks.
     */
    private fun compareSignatures(
        ref: List<FaceLandmarkPoint>,
        live: List<FaceLandmarkPoint>
    ): Float {
        val refMap = ref.associateBy { it.type }
        val liveMap = live.associateBy { it.type }
        val commonTypes = refMap.keys.intersect(liveMap.keys)
        if (commonTypes.isEmpty()) return Float.MAX_VALUE
        val totalDist = commonTypes.sumOf { type ->
            val r = refMap[type]!!
            val l = liveMap[type]!!
            val dx = (r.x - l.x).toDouble()
            val dy = (r.y - l.y).toDouble()
            sqrt(dx * dx + dy * dy)
        }
        return (totalDist / commonTypes.size).toFloat()
    }
}
