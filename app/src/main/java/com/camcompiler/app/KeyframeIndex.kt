package com.camcompiler.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Reads the keyframe (sync sample) timestamps from a video file.
 *
 * Used by the trim UI to snap user-selected trim points to the nearest
 * keyframe so we can preserve fast stream-copy mode.
 *
 * Building the index requires walking through every sample's metadata
 * (no sample data is decoded), which on Android is fast but not free —
 * roughly 100ms for a 1-minute clip. Cache the result per clip.
 */
object KeyframeIndex {

    /**
     * Returns sorted keyframe presentation timestamps (in ms) for the video
     * track of the given clip. Empty list if no video track or read failed.
     */
    fun extract(ctx: Context, uri: Uri): List<Long> {
        val extractor = MediaExtractor()
        val pfd = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) { null } ?: return emptyList()

        return try {
            extractor.setDataSource(pfd.fileDescriptor)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { videoTrack = i; break }
            }
            if (videoTrack < 0) return emptyList()

            extractor.selectTrack(videoTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val keyframes = mutableListOf<Long>()
            while (true) {
                val sampleFlags = extractor.sampleFlags
                if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    val timeUs = extractor.sampleTime
                    if (timeUs >= 0) keyframes.add(timeUs / 1000L) // µs → ms
                }
                if (!extractor.advance()) break
            }
            keyframes.sorted()
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    /** Maximum distance to snap (in ms). If no keyframe is within this window of
     *  the requested time, the requested time is returned unchanged. This prevents
     *  surprising large jumps when the user drags far past the keyframe range
     *  (e.g. into very-end-of-clip territory that the keyframe index didn't reach). */
    private const val SNAP_WINDOW_MS = 1500L

    /**
     * Given a list of sorted keyframe timestamps and a requested time (ms),
     * return the closest keyframe at or before the requested time. Used for
     * trim-start so we never lose frames at the beginning.
     *
     * Safe-snap behavior: if the chosen keyframe is more than SNAP_WINDOW_MS
     * before the requested value, returns requestedMs instead. This prevents
     * "snap back to start" when no keyframe is anywhere near the user's choice.
     */
    fun nearestKeyframeAtOrBefore(keyframes: List<Long>, requestedMs: Long): Long {
        if (keyframes.isEmpty()) return requestedMs
        var best: Long? = null
        for (kf in keyframes) {
            if (kf <= requestedMs) best = kf else break
        }
        if (best == null) return requestedMs
        // Don't snap further than SNAP_WINDOW_MS backward
        return if (requestedMs - best > SNAP_WINDOW_MS) requestedMs else best
    }

    /**
     * For trim-end, we want the closest keyframe at or AFTER the requested
     * end time, so the trimmed range includes all frames up to that point.
     *
     * Safe-snap behavior: if no keyframe exists at or after the requested value
     * (i.e. user dragged past the last keyframe), returns requestedMs unchanged.
     * If the next keyframe is more than SNAP_WINDOW_MS away, also returns
     * requestedMs unchanged. Previously this returned keyframes.last() which
     * could cause a surprising jump backward by many seconds.
     */
    fun nearestKeyframeAtOrAfter(keyframes: List<Long>, requestedMs: Long): Long {
        if (keyframes.isEmpty()) return requestedMs
        for (kf in keyframes) {
            if (kf >= requestedMs) {
                // Don't snap further than SNAP_WINDOW_MS forward
                return if (kf - requestedMs > SNAP_WINDOW_MS) requestedMs else kf
            }
        }
        // No keyframe at or after — user dragged past the indexed range. Don't snap.
        return requestedMs
    }
}
