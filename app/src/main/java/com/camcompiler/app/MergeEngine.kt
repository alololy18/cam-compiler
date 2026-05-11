package com.camcompiler.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MergeEngine {

    private const val BUFFER_SIZE = 1024 * 1024

    sealed class Result {
        data class Success(val method: String, val outputBytes: Long, val skipped: Int) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Merges clipUris in the given order, writing the merged video to outputUri.
     */
    suspend fun merge(
        ctx: Context,
        clipUris: List<Uri>,
        outputUri: Uri,
        onProgress: (Float, String) -> Unit
    ): Result = coroutineScope {
        if (clipUris.isEmpty()) return@coroutineScope Result.Failure("No clips to merge")

        // Always produce to a temp file first, then copy to the chosen output URI.
        val tempFile = createTempFile(ctx)

        try {
            // Tier 1: mp4parser
            onProgress(0f, "Fast merge (mp4parser)...")
            val mp4r = withContext(Dispatchers.IO) {
                Mp4ParserMerger.merge(ctx, clipUris, tempFile) { p, s -> onProgress(p * 0.7f, s) }
            }
            if (mp4r.success) {
                // mp4parser may produce non-standard MP4 atoms that some video players reject
                // (even though MediaExtractor can parse them). Always re-mux through MediaMuxer
                // for guaranteed compatibility. This step is fast — just copies streams.
                onProgress(0.72f, "Re-muxing for compatibility...")
                val remuxFile = createTempFile(ctx)
                val remuxResult = tryMediaMuxer(ctx, listOf(Uri.fromFile(tempFile)), remuxFile) { p, s ->
                    onProgress(0.72f + (p * 0.20f), "Compatibility pass: $s")
                }
                if (remuxResult.success && withContext(Dispatchers.IO) { isPlayable(remuxFile) }) {
                    onProgress(0.95f, "Saving to chosen location...")
                    if (copyTempToOutput(ctx, remuxFile, outputUri)) {
                        val r = Result.Success("mp4parser+compat", remuxFile.length(), mp4r.skippedCount)
                        remuxFile.delete()
                        return@coroutineScope r
                    }
                    remuxFile.delete()
                    return@coroutineScope Result.Failure("Merge succeeded but could not save to chosen location")
                }
                // Compatibility re-mux failed. Fall back to direct mp4parser output but warn.
                remuxFile.delete()
                if (withContext(Dispatchers.IO) { isPlayable(tempFile) }) {
                    onProgress(0.95f, "Saving (compatibility pass skipped)...")
                    if (copyTempToOutput(ctx, tempFile, outputUri)) {
                        return@coroutineScope Result.Success("mp4parser-raw", tempFile.length(), mp4r.skippedCount)
                    }
                    return@coroutineScope Result.Failure("Merge succeeded but could not save to chosen location")
                }
                // Output not playable at all — fall through to MediaMuxer-from-scratch
                onProgress(0.0f, "mp4parser output unusable — re-merging with MediaMuxer...")
            }
            val mp4ParserError = if (mp4r.success) "Output not playable" else mp4r.error
            tempFile.delete()

            // Tier 2: MediaMuxer
            onProgress(0f, "Trying MediaMuxer fallback...")
            val tempFile2 = createTempFile(ctx)
            val muxResult = tryMediaMuxer(ctx, clipUris, tempFile2, onProgress)
            if (muxResult.success) {
                onProgress(0.95f, "Saving to chosen location...")
                if (copyTempToOutput(ctx, tempFile2, outputUri)) {
                    val r = Result.Success("MediaMuxer", tempFile2.length(), 0)
                    tempFile2.delete()
                    return@coroutineScope r
                }
                tempFile2.delete()
                return@coroutineScope Result.Failure("Merge succeeded but could not save to chosen location")
            }
            val muxError = muxResult.error
            tempFile2.delete()

            // Tier 3: Media3 (slow re-encode possible)
            onProgress(0f, "Trying Media3 fallback (slowest)...")
            val tempFile3 = createTempFile(ctx)
            val media3Result = tryMedia3(ctx, clipUris, tempFile3, onProgress)
            if (media3Result.first) {
                onProgress(0.95f, "Saving to chosen location...")
                if (copyTempToOutput(ctx, tempFile3, outputUri)) {
                    val r = Result.Success("Media3", tempFile3.length(), 0)
                    tempFile3.delete()
                    return@coroutineScope r
                }
                tempFile3.delete()
                return@coroutineScope Result.Failure("Merge succeeded but could not save to chosen location")
            }
            tempFile3.delete()

            return@coroutineScope Result.Failure(
                "All three merge methods failed. mp4parser: $mp4ParserError. MediaMuxer: $muxError. Media3: ${media3Result.second}"
            )
        } finally {
            tempFile.delete()
        }
    }

    /** Returns true if Android's MediaExtractor can parse the file as playable video. */
    private fun isPlayable(file: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { hasVideo = true; break }
            }
            extractor.release()
            hasVideo
        } catch (_: Exception) {
            false
        }
    }

    private fun copyTempToOutput(ctx: Context, tempFile: File, outputUri: Uri): Boolean {
        return try {
            // "wt" = write+truncate, ensures we don't leave stale bytes if the new file is shorter
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

    // ===== Tier 2: MediaMuxer =====

    private data class MuxResult(val success: Boolean, val error: String = "")

    private suspend fun tryMediaMuxer(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): MuxResult = withContext(Dispatchers.IO) {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoOutTrack = -1
        var audioOutTrack = -1
        var videoTimeOffsetUs = 0L
        var audioTimeOffsetUs = 0L

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            val firstExtractor = MediaExtractor()
            val firstPfd = openPfd(ctx, clipUris[0])
                ?: return@withContext MuxResult(false, "Could not open first clip")
            try {
                firstExtractor.setDataSource(firstPfd.fileDescriptor)
                for (i in 0 until firstExtractor.trackCount) {
                    val fmt = firstExtractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && videoOutTrack < 0 -> videoOutTrack = muxer.addTrack(fmt)
                        mime.startsWith("audio/") && audioOutTrack < 0 -> audioOutTrack = muxer.addTrack(fmt)
                    }
                }
            } finally {
                firstExtractor.release()
                firstPfd.close()
            }

            if (videoOutTrack < 0) return@withContext MuxResult(false, "No video track in first clip")
            muxer.start()
            muxerStarted = true

            for ((idx, uri) in clipUris.withIndex()) {
                onProgress(idx.toFloat() / clipUris.size, "Copying clip ${idx + 1}/${clipUris.size}...")
                val extractor = MediaExtractor()
                val pfd = openPfd(ctx, uri) ?: continue
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    var clipVideoTrack = -1
                    var clipAudioTrack = -1
                    var maxV = 0L
                    var maxA = 0L
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") && clipVideoTrack < 0 -> clipVideoTrack = i
                            mime.startsWith("audio/") && clipAudioTrack < 0 -> clipAudioTrack = i
                        }
                    }
                    if (clipVideoTrack >= 0) maxV = copyTrack(extractor, clipVideoTrack, muxer, videoOutTrack, videoTimeOffsetUs, buffer)
                    if (clipAudioTrack >= 0 && audioOutTrack >= 0) maxA = copyTrack(extractor, clipAudioTrack, muxer, audioOutTrack, audioTimeOffsetUs, buffer)
                    if (maxV > 0) videoTimeOffsetUs = maxV + 33_000
                    if (maxA > 0) audioTimeOffsetUs = maxA + 23_000
                } finally {
                    extractor.release()
                    pfd.close()
                }
            }
            return@withContext MuxResult(true)
        } catch (e: Exception) {
            return@withContext MuxResult(false, e.message ?: "Unknown muxer error")
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        offsetUs: Long,
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
            info.presentationTimeUs = extractor.sampleTime + offsetUs
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

    // ===== Tier 3: Media3 =====

    @OptIn(UnstableApi::class)
    private suspend fun tryMedia3(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        val mediaItems = clipUris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence(mediaItems)
        val composition: Composition = Composition.Builder(listOf(sequence)).build()

        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    deferred.complete(true to "")
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    deferred.complete(false to (e.message ?: "unknown error"))
                }
            })
            .build()

        try {
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            return@withContext false to (e.message ?: "failed to start")
        }

        val ph = ProgressHolder()
        launch {
            while (isActive && !deferred.isCompleted) {
                val s = transformer.getProgress(ph)
                if (s == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(ph.progress / 100f, "Media3 (re-encoding, slow)...")
                }
                delay(500)
            }
        }
        deferred.await()
    }
}
