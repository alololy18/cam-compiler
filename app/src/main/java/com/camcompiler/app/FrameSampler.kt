package com.camcompiler.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Utility for extracting frames from a video clip at specified timestamps.
 *
 * For Phase 1 of highlight detection, we use MediaMetadataRetriever which is:
 *  - Simple (single API call per frame)
 *  - Acceptable speed when extracting from sync samples (~30-100ms per frame on midrange phones)
 *  - Returns scaled bitmaps via getScaledFrameAtTime() on Android 9+
 *
 * If MMR proves too slow in real-world testing, we can fall back to MediaCodec+Surface
 * (5-10× faster but ~300 more lines of code).
 *
 * Memory model: each sample call returns a freshly-allocated bitmap. The caller is
 * responsible for processing it (extracting histogram, etc.) and discarding promptly.
 * DO NOT accumulate bitmaps — a 30-min clip with 1000 I-frames × 57600 bytes = 58MB OOM risk.
 */
object FrameSampler {
    private const val TAG = "FrameSampler"

    // Downscale frames to this resolution for analysis — small enough to compute fast,
    // big enough that scene-change correlation is reliable.
    const val ANALYSIS_WIDTH = 160
    const val ANALYSIS_HEIGHT = 90

    /**
     * Walks the video track via MediaExtractor and returns the timestamps of all
     * sync (I-frame) samples, in milliseconds, sorted ascending.
     *
     * This is FAST — no decoding involved. Just metadata walks.
     * Cancellable: throws CancellationException if the coroutine is cancelled.
     */
    suspend fun extractKeyframeTimestamps(ctx: Context, uri: Uri): List<Long> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            val timestamps = mutableListOf<Long>()
            try {
                extractor.setDataSource(ctx, uri, null)
                // Find the video track
                var videoTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        videoTrack = i
                        break
                    }
                }
                if (videoTrack < 0) return@withContext emptyList<Long>()

                extractor.selectTrack(videoTrack)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                while (true) {
                    coroutineContext.ensureActive()
                    val flags = extractor.sampleFlags
                    if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        val timeUs = extractor.sampleTime
                        if (timeUs >= 0) timestamps.add(timeUs / 1000L)
                    }
                    if (!extractor.advance()) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractKeyframeTimestamps failed for $uri: ${e.message}")
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
            timestamps.sorted()
        }

    /**
     * Sampler interface: holds an MMR open for the lifetime of an analysis pass.
     * Caller must call `close()` when done (preferably via `use { }`).
     */
    interface Sampler : AutoCloseable {
        /**
         * Extract a single frame at the given time (ms), downsampled to ANALYSIS_WIDTH×ANALYSIS_HEIGHT.
         * Returns null if extraction fails.
         *
         * @param syncOnly if true, returns the closest preceding I-frame (faster, less precise);
         *                 if false, returns the closest frame (slower, more precise).
         */
        fun frameAt(timeMs: Long, syncOnly: Boolean = true): Bitmap?
    }

    /**
     * Open a Sampler for a clip. Always wrap in `use { }` to ensure cleanup.
     */
    fun open(ctx: Context, uri: Uri): Sampler {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(ctx, uri)
        return MmrSampler(mmr)
    }

    private class MmrSampler(private val mmr: MediaMetadataRetriever) : Sampler {
        override fun frameAt(timeMs: Long, syncOnly: Boolean): Bitmap? {
            val timeUs = timeMs * 1000L
            val option = if (syncOnly)
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            else
                MediaMetadataRetriever.OPTION_CLOSEST
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    mmr.getScaledFrameAtTime(timeUs, option, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
                } else {
                    // Fallback: fetch full-size, then scale ourselves
                    val raw = mmr.getFrameAtTime(timeUs, option) ?: return null
                    val scaled = Bitmap.createScaledBitmap(raw, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, true)
                    if (scaled != raw) raw.recycle()
                    scaled
                }
            } catch (e: Exception) {
                Log.w(TAG, "frameAt $timeMs failed: ${e.message}")
                null
            }
        }

        override fun close() {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /**
     * Compute a 32-bin Y-channel (luminance) histogram from a downscaled bitmap.
     * Y is computed using the standard ITU-R BT.601 weights.
     */
    fun yHistogram(bmp: Bitmap, bins: Int = 32): IntArray {
        val hist = IntArray(bins)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val divisor = 256 / bins  // for 32 bins: 8 (each bin covers 8 luminance levels)
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            // BT.601 luminance — precomputed to integer for speed
            val y = (77 * r + 150 * g + 29 * b) shr 8  // ~= 0.299R + 0.587G + 0.114B
            val bin = (y / divisor).coerceIn(0, bins - 1)
            hist[bin]++
        }
        return hist
    }

    /**
     * Compute the mean-absolute-difference between two bitmaps' luminance channels.
     * Used as a fast motion proxy. Both bitmaps must be the same size.
     *
     * Returns a value in [0, 255] — higher means more motion between the two frames.
     */
    fun meanAbsoluteDifference(a: Bitmap, b: Bitmap): Float {
        if (a.width != b.width || a.height != b.height) return 0f
        val w = a.width
        val h = a.height
        val pixA = IntArray(w * h)
        val pixB = IntArray(w * h)
        a.getPixels(pixA, 0, w, 0, 0, w, h)
        b.getPixels(pixB, 0, w, 0, 0, w, h)
        var sum = 0L
        for (i in pixA.indices) {
            val pa = pixA[i]
            val pb = pixB[i]
            val ya = (77 * ((pa shr 16) and 0xFF) + 150 * ((pa shr 8) and 0xFF) + 29 * (pa and 0xFF)) shr 8
            val yb = (77 * ((pb shr 16) and 0xFF) + 150 * ((pb shr 8) and 0xFF) + 29 * (pb and 0xFF)) shr 8
            sum += kotlin.math.abs(ya - yb)
        }
        return sum.toFloat() / pixA.size.toFloat()
    }

    /**
     * Pearson correlation between two normalized histograms.
     * Returns a value in [-1, 1]. 1.0 = identical, near 0 = uncorrelated.
     */
    fun histogramCorrelation(h1: IntArray, h2: IntArray): Float {
        if (h1.size != h2.size) return 0f
        val n = h1.size
        var mean1 = 0.0
        var mean2 = 0.0
        for (i in 0 until n) {
            mean1 += h1[i]
            mean2 += h2[i]
        }
        mean1 /= n
        mean2 /= n

        var num = 0.0
        var den1 = 0.0
        var den2 = 0.0
        for (i in 0 until n) {
            val d1 = h1[i] - mean1
            val d2 = h2[i] - mean2
            num += d1 * d2
            den1 += d1 * d1
            den2 += d2 * d2
        }
        if (den1 == 0.0 || den2 == 0.0) return 0f
        return (num / kotlin.math.sqrt(den1 * den2)).toFloat()
    }
}
