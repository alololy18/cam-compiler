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

/**
 * Two-mode merge engine:
 *
 * FAST: pure stream copy via MediaMuxer + MediaExtractor.
 *   Use when all clips share codec/resolution/framerate. Lossless. Fast.
 *
 * COMPATIBLE: re-encode via Media3 Transformer.
 *   Use when clips have differing parameters. Slower; one generation of
 *   quality loss. Outputs uniform H.264/AAC.
 *
 * The caller (MainActivity) decides which mode based on ClipAnalyzer's result
 * and (when clips differ) the user's choice in the mismatch dialog.
 */
object MergeEngine {

    enum class Mode { FAST, COMPATIBLE }

    private const val BUFFER_SIZE = 1024 * 1024
    private const val DEFAULT_FRAME_DURATION_US = 33_333L

    sealed class Result {
        data class Success(val method: String, val outputBytes: Long, val skipped: Int) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun merge(
        ctx: Context,
        clipUris: List<Uri>,
        outputUri: Uri,
        mode: Mode,
        onProgress: (Float, String) -> Unit
    ): Result = coroutineScope {
        if (clipUris.isEmpty()) return@coroutineScope Result.Failure("No clips to merge")

        val tempFile = createTempFile(ctx)
        try {
            when (mode) {
                Mode.FAST -> {
                    onProgress(0f, "Fast merge (stream copy)...")
                    val r = withContext(Dispatchers.IO) {
                        muxClipsFast(ctx, clipUris, tempFile, onProgress)
                    }
                    if (!r.success) return@coroutineScope Result.Failure(r.error)
                    onProgress(0.95f, "Saving to chosen location...")
                    if (!withContext(Dispatchers.IO) { copyTempToOutput(ctx, tempFile, outputUri) }) {
                        return@coroutineScope Result.Failure("Could not save to chosen location")
                    }
                    onProgress(1f, "Done")
                    Result.Success("Fast (stream copy)", tempFile.length(), r.skippedClips)
                }
                Mode.COMPATIBLE -> {
                    onProgress(0f, "Compatible merge (re-encoding)...")
                    val r = mergeCompatible(ctx, clipUris, tempFile, onProgress)
                    if (!r.first) return@coroutineScope Result.Failure(r.second)
                    onProgress(0.95f, "Saving to chosen location...")
                    if (!withContext(Dispatchers.IO) { copyTempToOutput(ctx, tempFile, outputUri) }) {
                        return@coroutineScope Result.Failure("Could not save to chosen location")
                    }
                    onProgress(1f, "Done")
                    Result.Success("Compatible (re-encoded)", tempFile.length(), 0)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    // ===== Fast mode: MediaMuxer stream copy =====

    private data class MuxResult(val success: Boolean, val error: String = "", val skippedClips: Int = 0)

    private fun muxClipsFast(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): MuxResult {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoOutTrack = -1
        var audioOutTrack = -1
        var clipTimeOffsetUs = 0L
        var skipped = 0

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            val firstFormats = readClipFormats(ctx, clipUris[0])
                ?: return MuxResult(false, "Could not read first clip")
            firstFormats.videoFormat?.let { videoOutTrack = muxer.addTrack(it) }
            firstFormats.audioFormat?.let { audioOutTrack = muxer.addTrack(it) }
            if (videoOutTrack < 0) return MuxResult(false, "First clip has no video track")

            val frameDurationUs = deriveFrameDurationUs(firstFormats.videoFormat)
            muxer.start()
            muxerStarted = true

            for ((idx, uri) in clipUris.withIndex()) {
                onProgress(idx.toFloat() / clipUris.size * 0.95f, "Merging clip ${idx + 1}/${clipUris.size}...")
                val extractor = MediaExtractor()
                val pfd = openPfd(ctx, uri)
                if (pfd == null) { skipped++; continue }
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    var vTrack = -1
                    var aTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") && vTrack < 0 -> vTrack = i
                            mime.startsWith("audio/") && aTrack < 0 -> aTrack = i
                        }
                    }
                    if (vTrack < 0) { skipped++; continue }

                    val vFirstPts = peekFirstPts(extractor, vTrack)
                    val aFirstPts = if (aTrack >= 0) peekFirstPts(extractor, aTrack) else 0L

                    val vEnd = copyTrack(extractor, vTrack, muxer, videoOutTrack, clipTimeOffsetUs, vFirstPts, buffer)
                    var aEnd = 0L
                    if (aTrack >= 0 && audioOutTrack >= 0) {
                        aEnd = copyTrack(extractor, aTrack, muxer, audioOutTrack, clipTimeOffsetUs, aFirstPts, buffer)
                    }

                    val clipEnd = maxOf(vEnd, aEnd)
                    if (clipEnd > 0) clipTimeOffsetUs = clipEnd + frameDurationUs
                } finally {
                    extractor.release()
                    pfd.close()
                }
            }
            return MuxResult(true, skippedClips = skipped)
        } catch (e: Exception) {
            return MuxResult(false, e.message ?: "Unknown muxer error", skipped)
        } finally {
            try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    // ===== Compatible mode: Media3 Transformer re-encode =====

    @OptIn(UnstableApi::class)
    private suspend fun mergeCompatible(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        val items = clipUris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence(items)
        val composition: Composition = Composition.Builder(listOf(sequence)).build()

        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    deferred.complete(true to "")
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    deferred.complete(false to (e.message ?: "re-encoding failed"))
                }
            })
            .build()

        try {
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            return@withContext false to (e.message ?: "could not start Transformer")
        }

        val ph = ProgressHolder()
        launch {
            while (isActive && !deferred.isCompleted) {
                val s = transformer.getProgress(ph)
                if (s == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(ph.progress / 100f * 0.95f, "Re-encoding... ${ph.progress}%")
                }
                delay(500)
            }
        }
        deferred.await()
    }

    // ===== Shared helpers =====

    private data class ClipFormats(val videoFormat: MediaFormat?, val audioFormat: MediaFormat?)

    private fun readClipFormats(ctx: Context, uri: Uri): ClipFormats? {
        val extractor = MediaExtractor()
        val pfd = openPfd(ctx, uri) ?: return null
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var v: MediaFormat? = null
            var a: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && v == null -> v = fmt
                    mime.startsWith("audio/") && a == null -> a = fmt
                }
            }
            return ClipFormats(v, a)
        } catch (_: Exception) { return null }
        finally { extractor.release(); pfd.close() }
    }

    private fun deriveFrameDurationUs(fmt: MediaFormat?): Long {
        if (fmt == null) return DEFAULT_FRAME_DURATION_US
        return try {
            val fr = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
            if (fr > 0) (1_000_000L / fr) else DEFAULT_FRAME_DURATION_US
        } catch (_: Exception) { DEFAULT_FRAME_DURATION_US }
    }

    private fun peekFirstPts(extractor: MediaExtractor, trackIdx: Int): Long {
        extractor.selectTrack(trackIdx)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val pts = extractor.sampleTime.coerceAtLeast(0L)
        extractor.unselectTrack(trackIdx)
        return pts
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        baseOffsetUs: Long,
        firstPtsUs: Long,
        buffer: ByteBuffer
    ): Long {
        extractor.selectTrack(inputTrack)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val info = MediaCodec.BufferInfo()
        var maxOutPts = 0L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val originalPts = extractor.sampleTime
            val outPts = (originalPts - firstPtsUs).coerceAtLeast(0L) + baseOffsetUs
            info.size = size
            info.offset = 0
            info.presentationTimeUs = outPts
            info.flags = extractor.sampleFlags
            if (outPts > maxOutPts) maxOutPts = outPts
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(inputTrack)
        return maxOutPts
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
        } catch (_: Exception) { false }
    }

    private fun createTempFile(ctx: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(ctx.cacheDir, "merge_temp_$ts.mp4")
    }
}
