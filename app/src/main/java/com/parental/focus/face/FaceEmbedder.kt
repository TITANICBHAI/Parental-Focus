package com.parental.focus.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * FaceEmbedder
 *
 * Wraps a TFLite FaceNet model to produce a 128-dimensional embedding vector
 * from a face crop bitmap.
 *
 * Model: MediaPipe face embedder (face_embedder.tflite)
 *   • Input:  1 × 112 × 112 × 3  float32 normalised to [-1, 1]
 *   • Output: 1 × 128              float32 L2-normalised embedding
 *   • Size:   ~2 MB
 *   • Source: https://storage.googleapis.com/mediapipe-models/face_embedder/
 *             face_embedder/float16/latest/face_embedder.tflite
 *
 * The model is downloaded once to the app's private files directory and reused
 * on every subsequent launch. No network access is needed after the first run.
 *
 * Thread safety: [getEmbedding] is synchronous and not thread-safe. Call it on
 * a background thread.
 */
class FaceEmbedder(private val context: Context) {

    private var interpreter: Interpreter? = null

    companion object {
        private const val TAG = "FaceEmbedder"

        const val INPUT_SIZE  = 112
        const val OUTPUT_SIZE = 128

        private const val MODEL_FILENAME = "face_embedder.tflite"
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/face_embedder/face_embedder/float16/latest/face_embedder.tflite"
    }

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Load the TFLite model from the app's private files directory.
     * Downloads it first if not present.
     *
     * Call from a background thread. Returns `true` on success.
     */
    fun loadModel(): Boolean {
        if (interpreter != null) return true

        val modelFile = File(context.filesDir, MODEL_FILENAME)
        if (!modelFile.exists()) {
            Log.i(TAG, "Downloading face embedder model…")
            if (!downloadModel(modelFile)) {
                Log.e(TAG, "Model download failed.")
                return false
            }
            Log.i(TAG, "Model downloaded: ${modelFile.length()} bytes")
        }

        return try {
            val options = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(modelFile, options)
            Log.i(TAG, "TFLite interpreter ready.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create interpreter", e)
            false
        }
    }

    fun isReady(): Boolean = interpreter != null

    fun isModelDownloaded(): Boolean = File(context.filesDir, MODEL_FILENAME).exists()

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Run FaceNet inference on a [faceCrop] bitmap (any size — will be resized).
     *
     * @return L2-normalised 128-dim float array, or null if the interpreter is
     *         not loaded or inference fails.
     */
    fun getEmbedding(faceCrop: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        return try {
            val scaled = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)

            // Fill input ByteBuffer: NHWC float32 normalised to [-1, 1]
            val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .apply { order(ByteOrder.nativeOrder()) }

            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val pixel = scaled.getPixel(x, y)
                    inputBuffer.putFloat((Color.red(pixel)   - 127.5f) / 128f)
                    inputBuffer.putFloat((Color.green(pixel) - 127.5f) / 128f)
                    inputBuffer.putFloat((Color.blue(pixel)  - 127.5f) / 128f)
                }
            }
            inputBuffer.rewind()

            // Output tensor: 1 × 128 float32
            val outputBuffer = ByteBuffer.allocateDirect(1 * OUTPUT_SIZE * 4)
                .apply { order(ByteOrder.nativeOrder()) }

            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val embedding = FloatArray(OUTPUT_SIZE) { outputBuffer.getFloat() }
            if (scaled != faceCrop) scaled.recycle()
            l2Normalize(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            null
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun downloadModel(dest: File): Boolean {
        return try {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout    = 60_000
                requestMethod  = "GET"
            }
            conn.connect()
            if (conn.responseCode != 200) {
                Log.e(TAG, "HTTP ${conn.responseCode} downloading model.")
                return false
            }
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { it.copyTo(out) }
            }
            conn.disconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download exception", e)
            dest.delete()
            false
        }
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.map { it * it }.sum()).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
