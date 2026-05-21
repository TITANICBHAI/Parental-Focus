package com.parental.focus.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * FaceEmbedder
 *
 * Loads the bundled FaceNet TFLite model from the APK assets and produces a
 * 128-dimensional L2-normalised embedding vector from a face-crop bitmap.
 *
 * Model specs (facenet.tflite — bundled in app/src/main/assets):
 *   • Architecture : FaceNet (Inception ResNet V1, trained on VGGFace2)
 *   • Input tensor : [1, 160, 160, 3]  float32  normalised to [-1, 1]
 *   • Output tensor: [1, 128]           float32  L2-normalised embedding
 *   • File size    : ~22 MB
 *   • Source       : github.com/shubham0204/FaceRecognition_With_FaceNet_Android
 *
 * The model is loaded once via [loadModel] and reused for every subsequent
 * call to [getEmbedding]. Always call [close] when done.
 *
 * Thread safety: [getEmbedding] is NOT thread-safe. Call on a single
 * background thread (IO dispatcher).
 */
class FaceEmbedder(private val context: Context) {

    private var interpreter: Interpreter? = null

    companion object {
        private const val TAG = "FaceEmbedder"

        const val INPUT_SIZE  = 160   // FaceNet expects 160 × 160 RGB
        const val OUTPUT_SIZE = 128   // 128-dim embedding
        private const val MODEL_ASSET = "facenet.tflite"
    }

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Load the TFLite model from APK assets into memory.
     * Must be called before [getEmbedding]. Returns `true` on success.
     */
    fun loadModel(): Boolean {
        if (interpreter != null) return true
        return try {
            val assetFd  = context.assets.openFd(MODEL_ASSET)
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val modelBuffer = inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFd.startOffset,
                assetFd.declaredLength
            )
            assetFd.close()
            val options = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "FaceNet TFLite model loaded from assets ($MODEL_ASSET).")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets", e)
            false
        }
    }

    /**
     * Returns true if the model asset file is present in the APK.
     * Since it is bundled at build time this should always be true.
     */
    fun isModelDownloaded(): Boolean = try {
        context.assets.open(MODEL_ASSET).close()
        true
    } catch (_: Exception) { false }

    fun isReady(): Boolean = interpreter != null

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Run FaceNet inference on [faceCrop] (any size — resized internally to
     * [INPUT_SIZE] × [INPUT_SIZE]).
     *
     * @return L2-normalised 128-dim float array, or null on failure.
     */
    fun getEmbedding(faceCrop: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        return try {
            val scaled = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)

            // Build input ByteBuffer — NHWC float32, pixels normalised to [-1, 1]
            val inputBuf = ByteBuffer
                .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .apply { order(ByteOrder.nativeOrder()) }

            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val px = scaled.getPixel(x, y)
                    inputBuf.putFloat((Color.red(px)   - 127.5f) / 128f)
                    inputBuf.putFloat((Color.green(px) - 127.5f) / 128f)
                    inputBuf.putFloat((Color.blue(px)  - 127.5f) / 128f)
                }
            }
            inputBuf.rewind()

            // Output ByteBuffer — 1 × 128 float32
            val outputBuf = ByteBuffer
                .allocateDirect(1 * OUTPUT_SIZE * 4)
                .apply { order(ByteOrder.nativeOrder()) }

            interp.run(inputBuf, outputBuf)
            outputBuf.rewind()

            val embedding = FloatArray(OUTPUT_SIZE) { outputBuf.float }
            if (scaled != faceCrop) scaled.recycle()
            l2Normalize(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
