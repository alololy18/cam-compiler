package com.camcompiler.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Merge engine using Android's built-in MediaMuxer + MediaExtractor.
 *
 * This is sample-by-sample stream copy — no decoding, no re-encoding.
 * Output is guaranteed to be playable on Android because it goes through
 * the same framework as the system video player.
 *
 * Performance characteristic: bottlenecked by storage I/O, not CPU.
 * For typical action-cam clips (same codec, same resolution, same fps),
 * merge time ≈ (total input size) / (read speed + write speed).
 *
 * Why we dropped mp4parser: its output has known Android compatibility
 * issues (GitHub issue sannies/mp4parser#102) — files play in VLC but not
 * in Android's stock player.
 *
 * Why we dropped Media3 Transformer: when input clips have any minor
 * difference (codec config, fps variation, audio sample-rate), Media3
 * silently re-encodes, which takes 10-50x longer.
 */
object MergeEngine {

    private const val BUFFER_SIZE = 1024 * 1024  // 1 MB sample buffer

    sealed class Result {
        data class Success(val method: String, val outputBytes: Long, val skipped: Int) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun merge(
        ctx: Context,
        clipUris: List<Uri>,
        outputUri: Uri,
        onProgress: (Float, String) -> Unit
    ): Result = coroutineScope {
        if (clipUris.isEmpty()) return@coroutineScope Result.Failure("No clips to merge")

        val tempFile = createTempFile(ctx)
        try {
            onProgress(0f, "Preparing to merge ${clipUris.size} clips...")
            val muxResult = withContext(Dispatchers.IO) {
                muxClips(ctx, clipUris, tempFile, onProgress)
            }
            if (!muxResult.success) {
                return@coroutineScope Result.Failure(muxResult.error)
            }

            onProgress(0.95f, "Saving to chosen location...")
            if (!withContext(Dispatchers.IO) { copyTempToOutput(ctx, tempFile, outputUri) }) {
                return@coroutineScope Result.Failure("Merge succeeded but could not save to chosen location")
            }
            onProgress(1f, "Done")
            return@coroutineScope Result.Success(
                method = "MediaMuxer",
                outputBytes = tempFile.length(),
                skipped = muxResult.skippedClips
            )
        } finally {
            tempFile.delete()
        }
    }

    private data class MuxResult(
        val success: Boolean,
        val error: String = "",
        val skippedClips: Int = 0
    )

    private fun muxClips(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): MuxResult {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoOutTrack = -1
        var audioOutTrack = -1
        var videoTimeOffsetUs = 0L
        var audioTimeOffsetUs = 0L
        var skipped = 0

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            // Read first clip's format to set up muxer tracks
            val firstFormats = readClipFormats(ctx, clipUris[0])
                ?: return MuxResult(false, "Could not read format of first clip")
            firstFormats.videoFormat?.let { videoOutTrack = muxer.addTrack(it) }
            firstFormats.audioFormat?.let { audioOutTrack = muxer.addTrack(it) }

            if (videoOutTrack < 0) {
                return MuxResult(false, "First clip has no video track")
            }
            muxer.start()
            muxerStarted = true

            for ((idx, uri) in clipUris.withIndex()) {
                val baseProgress = idx.toFloat() / clipUris.size
                onProgress(baseProgress * 0.95f, "Merging clip ${idx + 1}/${clipUris.size}...")

                val extractor = MediaExtractor()
                val pfd = openPfd(ctx, uri)
                if (pfd == null) {
                    skipped++
                    continue
                }
                try {
                    extractor.setDataSource(pfd.fileDescriptor)

                    var videoTrackIdx = -1
                    var audioTrackIdx = -1
                    for (i in 0 until extractor.trackCount) {
                        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") && videoTrackIdx < 0 -> videoTrackIdx = i
                            mime.startsWith("audio/") && audioTrackIdx < 0 -> audioTrackIdx = i
                        }
                    }

                    if (videoTrackIdx < 0) {
                        skipped++
                        continue
                    }

                    val videoEnd = copyTrack(extractor, videoTrackIdx, muxer, videoOutTrack, videoTimeOffsetUs, buffer)
                    var audioEnd = 0L
                    if (audioTrackIdx >= 0 && audioOutTrack >= 0) {
                        audioEnd = copyTrack(extractor, audioTrackIdx, muxer, audioOutTrack, audioTimeOffsetUs, buffer)
                    }

                    if (videoEnd > 0) videoTimeOffsetUs = videoEnd + 33_333
                    if (audioEnd > 0) audioTimeOffsetUs = audioEnd + 23_220
                } finally {
                    extractor.release()
                    pfd.close()
                }
            }

            return MuxResult(true, skippedClips = skipped)
        } catch (e: Exception) {
            return MuxResult(false, e.message ?: "Unknown muxer error", skipped)
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private data class ClipFormats(val videoFormat: MediaFormat?, val audioFormat: MediaFormat?)

    private fun readClipFormats(ctx: Context, uri: Uri): ClipFormats? {
        val extractor = MediaExtractor()
        val pfd = openPfd(ctx, uri) ?: return null
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && video == null -> video = fmt
                    mime.startsWith("audio/") && audio == null -> audio = fmt
                }
            }
            return ClipFormats(video, audio)
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
            pfd.close()
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        timeOffsetUs: Long,
        buffer: ByteBuffer
    ): Long {
        extractor.selectTrack(inputTrack)
        val info = MediaCodec.BufferInfo()
        var maxTimeUs = 0L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.size = size
            info.offset = 0
            info.presentationTimeUs = extractor.sampleTime + timeOffsetUs
            info.flags = extractor.sampleFlags
            if (info.presentationTimeUs > maxTimeUs) maxTimeUs = info.presentationTimeUs
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(inputTrack)
        return maxTimeUs
    }

    private fun openPfd(ctx: Context, uri: Uri): ParcelFileDescriptor? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")
    } catch (_: Exception) { null }

    private fun copyTempToOutput(ctx: Context, tempFile: File, outputUri: Uri): Boolean {
        return try {
            ctx.contentResolver.openOutputStream(outputUri, "wt")?.use { out ->
                tempFile.inputStream().use { it.copyTo(out, bufferSize = 1024 * 1024) }
                out.flush()
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private fun createTempFile(ctx: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(ctx.cacheDir, "merge_temp_$timestamp.mp4")
    }
}
