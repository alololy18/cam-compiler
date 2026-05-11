package com.camcompiler.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Analyzes video clips to determine if they're compatible for stream-copy merging,
 * or if they need re-encoding (because of codec/resolution/framerate differences).
 */
object ClipAnalyzer {

    data class ClipParams(
        val uri: Uri,
        val name: String,
        val videoMime: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val audioMime: String,
        val audioSampleRate: Int,
        val audioChannels: Int
    )

    data class Mismatch(
        val clipName: String,
        val differences: List<String>
    )

    sealed class AnalysisResult {
        /** All clips share compatible parameters — stream copy will work. */
        data class Uniform(val params: ClipParams) : AnalysisResult()
        /** Clips differ — re-encoding required. */
        data class Mixed(val referenceParams: ClipParams, val mismatches: List<Mismatch>) : AnalysisResult()
        /** Could not analyze. */
        data class Failed(val message: String) : AnalysisResult()
    }

    /**
     * Analyze all clips, comparing them against the first clip's parameters.
     * Returns Uniform if they all match, Mixed if any differ.
     */
    fun analyze(ctx: Context, clipUris: List<Uri>, clipNames: List<String>): AnalysisResult {
        if (clipUris.isEmpty()) return AnalysisResult.Failed("No clips to analyze")

        val firstParams = readParams(ctx, clipUris[0], clipNames.getOrNull(0) ?: "first clip")
            ?: return AnalysisResult.Failed("Could not read first clip")

        val mismatches = mutableListOf<Mismatch>()

        for (i in 1 until clipUris.size) {
            val name = clipNames.getOrNull(i) ?: "clip $i"
            val params = readParams(ctx, clipUris[i], name) ?: continue

            val diffs = compareParams(firstParams, params)
            if (diffs.isNotEmpty()) {
                mismatches.add(Mismatch(name, diffs))
            }
        }

        return if (mismatches.isEmpty()) {
            AnalysisResult.Uniform(firstParams)
        } else {
            AnalysisResult.Mixed(firstParams, mismatches)
        }
    }

    private fun readParams(ctx: Context, uri: Uri, name: String): ClipParams? {
        val extractor = MediaExtractor()
        val pfd = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) { null } ?: return null

        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var videoMime = ""
            var width = 0
            var height = 0
            var frameRate = 0
            var audioMime = ""
            var audioSampleRate = 0
            var audioChannels = 0

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoMime.isEmpty() -> {
                        videoMime = mime
                        width = fmt.getIntOrDefault(MediaFormat.KEY_WIDTH, 0)
                        height = fmt.getIntOrDefault(MediaFormat.KEY_HEIGHT, 0)
                        frameRate = fmt.getIntOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
                    }
                    mime.startsWith("audio/") && audioMime.isEmpty() -> {
                        audioMime = mime
                        audioSampleRate = fmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
                        audioChannels = fmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0)
                    }
                }
            }
            return ClipParams(uri, name, videoMime, width, height, frameRate, audioMime, audioSampleRate, audioChannels)
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
            pfd.close()
        }
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else default
        } catch (_: Exception) { default }
    }

    private fun compareParams(reference: ClipParams, candidate: ClipParams): List<String> {
        val diffs = mutableListOf<String>()
        if (reference.videoMime != candidate.videoMime) {
            diffs.add("video codec: ${shortCodec(candidate.videoMime)} (vs ${shortCodec(reference.videoMime)})")
        }
        if (reference.width != candidate.width || reference.height != candidate.height) {
            diffs.add("resolution: ${candidate.width}×${candidate.height} (vs ${reference.width}×${reference.height})")
        }
        // Allow 1fps variation - some encoders report 29 vs 30
        if (kotlin.math.abs(reference.frameRate - candidate.frameRate) > 1) {
            diffs.add("framerate: ${candidate.frameRate}fps (vs ${reference.frameRate}fps)")
        }
        if (reference.audioMime.isNotEmpty() && candidate.audioMime.isNotEmpty()) {
            if (reference.audioMime != candidate.audioMime) {
                diffs.add("audio codec: ${shortCodec(candidate.audioMime)} (vs ${shortCodec(reference.audioMime)})")
            }
            if (reference.audioSampleRate != candidate.audioSampleRate) {
                diffs.add("audio rate: ${candidate.audioSampleRate}Hz (vs ${reference.audioSampleRate}Hz)")
            }
            if (reference.audioChannels != candidate.audioChannels) {
                diffs.add("audio channels: ${candidate.audioChannels} (vs ${reference.audioChannels})")
            }
        } else if (reference.audioMime != candidate.audioMime) {
            // One has audio and the other doesn't
            diffs.add(if (candidate.audioMime.isEmpty()) "no audio" else "has audio (reference doesn't)")
        }
        return diffs
    }

    private fun shortCodec(mime: String): String = mime.substringAfter("/").uppercase()
}
