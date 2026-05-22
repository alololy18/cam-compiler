package com.camcompiler.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Wrapper around two TFLite interpreters: one for the image tower, one for the
 * text tower of MobileCLIP.
 *
 * Critical assumptions (verified at load time via diagnostic logging):
 *   - Image encoder input: float32 [1, 3, H, W] OR [1, H, W, 3], normalized with CLIP stats
 *   - Image encoder output: float32 [1, EMBED_DIM] embedding (not normalized — we normalize here)
 *   - Text encoder input: int32 [1, 77] token IDs
 *   - Text encoder output: float32 [1, EMBED_DIM] embedding (not normalized — we normalize here)
 *   - EMBED_DIM is usually 512 for MobileCLIP-S/B family
 *
 * IF THE ACTUAL MODEL DIFFERS:
 * The init() function logs full input/output details. Inspect logcat for the line
 *     "MobileClipInference: model details — ..."
 * and adjust constants below accordingly.
 *
 * Required assets (in app/src/main/assets/):
 *   - mobileclip_b_image.tflite
 *   - mobileclip_b_text.tflite
 *
 * If those filenames change, update MODEL_IMAGE_NAME / MODEL_TEXT_NAME.
 */
class MobileClipInference private constructor(
    private val imageInterpreter: Interpreter,
    private val textInterpreter: Interpreter,
    private val imageInputShape: IntArray,
    private val imageInputChannelsLast: Boolean,
    private val embeddingDim: Int,
) {
    private val tokenizer: MobileClipTokenizer

    init {
        // Tokenizer is shared with the text encoder; load it eagerly here too
        // so first-query latency is lower. (It's already a singleton inside.)
        tokenizer = MobileClipTokenizer.getInstance(appContext!!)
    }

    /**
     * Embed an image. Returns an L2-normalized float vector of length [embeddingDim].
     * Recycles the input bitmap if [recycleInput] is true (default).
     */
    fun embedImage(bitmap: Bitmap, recycleInput: Boolean = true): FloatArray {
        val targetH: Int
        val targetW: Int
        if (imageInputChannelsLast) {
            // [1, H, W, 3]
            targetH = imageInputShape[1]
            targetW = imageInputShape[2]
        } else {
            // [1, 3, H, W]
            targetH = imageInputShape[2]
            targetW = imageInputShape[3]
        }

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val input = preprocessImage(resized, targetH, targetW, imageInputChannelsLast)

        val output = Array(1) { FloatArray(embeddingDim) }
        imageInterpreter.run(input, output)

        if (resized !== bitmap) resized.recycle()
        if (recycleInput) bitmap.recycle()

        return l2Normalize(output[0])
    }

    /**
     * Embed a text query. Returns an L2-normalized float vector of length [embeddingDim].
     */
    fun embedText(text: String): FloatArray {
        val tokens = tokenizer.encode(text)
        // Build input tensor [1, 77] of int32
        val input = Array(1) { tokens }
        val output = Array(1) { FloatArray(embeddingDim) }
        textInterpreter.run(input, output)
        return l2Normalize(output[0])
    }

    /** Cosine similarity between two already-normalized vectors. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    fun close() {
        try { imageInterpreter.close() } catch (_: Exception) {}
        try { textInterpreter.close() } catch (_: Exception) {}
    }

    // ====================================================================
    // Image preprocessing
    // ====================================================================

    /**
     * Convert ARGB bitmap to a normalized float input tensor for CLIP.
     * Returns either NHWC or NCHW depending on the model's input layout.
     */
    private fun preprocessImage(
        bitmap: Bitmap,
        h: Int,
        w: Int,
        channelsLast: Boolean
    ): ByteBuffer {
        // 4 bytes per float, 3 channels
        val buf = ByteBuffer.allocateDirect(1 * 3 * h * w * 4).order(ByteOrder.nativeOrder())

        val pixels = IntArray(h * w)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        if (channelsLast) {
            // NHWC: [batch, h, w, c]
            var i = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val px = pixels[i++]
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    buf.putFloat((r - CLIP_MEAN[0]) / CLIP_STD[0])
                    buf.putFloat((g - CLIP_MEAN[1]) / CLIP_STD[1])
                    buf.putFloat((b - CLIP_MEAN[2]) / CLIP_STD[2])
                }
            }
        } else {
            // NCHW: [batch, c, h, w] — write channel R, then G, then B
            for (c in 0 until 3) {
                val mean = CLIP_MEAN[c]
                val std = CLIP_STD[c]
                var i = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val px = pixels[i++]
                        val v = when (c) {
                            0 -> ((px shr 16) and 0xFF) / 255f  // R
                            1 -> ((px shr 8) and 0xFF) / 255f   // G
                            else -> (px and 0xFF) / 255f         // B
                        }
                        buf.putFloat((v - mean) / std)
                    }
                }
            }
        }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq.toDouble()).toFloat()
        if (norm < 1e-8f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    companion object {
        private const val TAG = "MobileClipInference"

        // Update these if you bundle a different model variant
        private const val MODEL_IMAGE_NAME = "mobileclip_b_image.tflite"
        private const val MODEL_TEXT_NAME = "mobileclip_b_text.tflite"

        // Fallback names if the B model conversion isn't available — we try these
        // in order. First one that exists in assets is used.
        private val IMAGE_MODEL_FALLBACKS = listOf(
            "mobileclip_b_image.tflite",
            "mobileclip_b_datacompdr_last.tflite",
            "mobileclip_s2_image.tflite",
            "mobileclip_s2_datacompdr_last.tflite",
        )
        private val TEXT_MODEL_FALLBACKS = listOf(
            "mobileclip_b_text.tflite",
            "mobileclip_s2_text.tflite",
        )

        // Standard CLIP normalization stats (RGB)
        private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        @Volatile
        private var INSTANCE: MobileClipInference? = null

        // Keep a context reference to load the tokenizer lazily.
        // Set in getInstance().
        @Volatile
        private var appContext: Context? = null

        /**
         * Get the shared inference instance. Loads models on first call (~1-2 seconds).
         * Subsequent calls return the cached instance.
         *
         * Returns null if models could not be loaded — UI should show an error.
         */
        fun getInstance(ctx: Context): MobileClipInference? {
            appContext = ctx.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    load(ctx.applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load MobileCLIP: ${e.message}", e)
                    null
                }?.also { INSTANCE = it }
            }
        }

        private fun load(ctx: Context): MobileClipInference {
            // Find which image model file is available in assets
            val imageModelFile = findFirstAvailableAsset(ctx, IMAGE_MODEL_FALLBACKS)
                ?: throw IllegalStateException(
                    "No MobileCLIP image model found. Place one of these in app/src/main/assets/: ${IMAGE_MODEL_FALLBACKS.joinToString()}"
                )
            val textModelFile = findFirstAvailableAsset(ctx, TEXT_MODEL_FALLBACKS)
                ?: throw IllegalStateException(
                    "No MobileCLIP text model found. Place one of these in app/src/main/assets/: ${TEXT_MODEL_FALLBACKS.joinToString()}"
                )
            Log.d(TAG, "Loading image model: $imageModelFile")
            Log.d(TAG, "Loading text model: $textModelFile")

            val imageBuf = loadAssetAsBuffer(ctx, imageModelFile)
            val textBuf = loadAssetAsBuffer(ctx, textModelFile)

            val opts = Interpreter.Options().apply {
                numThreads = 4
                // GPU delegate could be added here later, but skip for now to keep
                // build simple and avoid surprises across devices.
            }
            val imageInterp = Interpreter(imageBuf, opts)
            val textInterp = Interpreter(textBuf, opts)

            // Inspect image input/output shape from the model itself
            val imageInputTensor = imageInterp.getInputTensor(0)
            val imageInputShape = imageInputTensor.shape()
            val imageOutputTensor = imageInterp.getOutputTensor(0)
            val imageOutputShape = imageOutputTensor.shape()

            // Detect NHWC vs NCHW from shape: NHWC has 3 in last position, NCHW has 3 in second
            val channelsLast = imageInputShape.size == 4 && imageInputShape[3] == 3
            val channelsFirst = imageInputShape.size == 4 && imageInputShape[1] == 3
            if (!channelsLast && !channelsFirst) {
                Log.w(TAG, "Unexpected image input shape: ${imageInputShape.contentToString()}; assuming NHWC")
            }

            val embeddingDim = imageOutputShape.last()

            // Inspect text input/output for sanity
            val textInputTensor = textInterp.getInputTensor(0)
            val textInputShape = textInputTensor.shape()
            val textOutputTensor = textInterp.getOutputTensor(0)
            val textOutputShape = textOutputTensor.shape()

            Log.d(TAG, "=== MobileCLIP model details ===")
            Log.d(TAG, "image input shape:  ${imageInputShape.contentToString()} (${if (channelsLast) "NHWC" else "NCHW"})")
            Log.d(TAG, "image input dtype:  ${imageInputTensor.dataType()}")
            Log.d(TAG, "image output shape: ${imageOutputShape.contentToString()}")
            Log.d(TAG, "text input shape:   ${textInputShape.contentToString()}")
            Log.d(TAG, "text input dtype:   ${textInputTensor.dataType()}")
            Log.d(TAG, "text output shape:  ${textOutputShape.contentToString()}")
            Log.d(TAG, "embedding dim:      $embeddingDim")
            Log.d(TAG, "================================")

            // Sanity check: text input length must match tokenizer
            if (textInputShape.last() != MobileClipTokenizer.MAX_TOKENS) {
                Log.w(TAG,
                    "Text input length ${textInputShape.last()} != tokenizer MAX_TOKENS ${MobileClipTokenizer.MAX_TOKENS}. " +
                            "Tokenizer may need adjustment for this model variant."
                )
            }

            return MobileClipInference(
                imageInterpreter = imageInterp,
                textInterpreter = textInterp,
                imageInputShape = imageInputShape,
                imageInputChannelsLast = channelsLast,
                embeddingDim = embeddingDim,
            )
        }

        private fun findFirstAvailableAsset(ctx: Context, names: List<String>): String? {
            val assets = try {
                ctx.assets.list("")?.toSet() ?: emptySet()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list assets: ${e.message}")
                return null
            }
            return names.firstOrNull { it in assets }
        }

        private fun loadAssetAsBuffer(ctx: Context, name: String): MappedByteBuffer {
            val afd = ctx.assets.openFd(name)
            return FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }
}
