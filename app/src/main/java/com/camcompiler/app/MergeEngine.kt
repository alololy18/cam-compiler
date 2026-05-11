package com.camcompiler.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Merge engine v8.
 *
 * Accepts an EditProject and chooses one of two paths:
 *
 *   FAST       — no music. For each clip, MediaMuxer stream-copies its
 *                effective trim ranges (in order). Lossless.
 *
 *   COMPATIBLE — music present OR caller explicitly requests re-encode.
 *                Media3 Transformer composition with one EditedMediaItem
 *                per (clip, range) pair, plus an optional looping music sequence.
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
        project: EditProject,
        outputUri: Uri,
        mode: Mode,
        onProgress: (Float, String) -> Unit
    ): Result = coroutineScope {
        if (project.clipEdits.isEmpty()) return@coroutineScope Result.Failure("No clips to merge")

        val tempFile = createTempFile(ctx)
        try {
            when (mode) {
                Mode.FAST -> {
                    val label = if (project.hasClipEdits()) "Fast merge (stream copy with trim)..."
                        else "Fast merge (stream copy)..."
                    onProgress(0f, label)
                    val r = withContext(Dispatchers.IO) {
                        muxClipsFast(ctx, project, tempFile, onProgress)
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
                    val r = mergeCompatible(ctx, project, tempFile, onProgress)
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

    // ===== Fast mode: MediaMuxer stream copy with multi-range trim =====

    private data class MuxResult(val success: Boolean, val error: String = "", val skippedClips: Int = 0)

    private fun muxClipsFast(
        ctx: Context,
        project: EditProject,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): MuxResult {
        if (project.hasMusic()) {
            return MuxResult(false, "Music requires re-encoding (Compatible mode)")
        }

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoOutTrack = -1
        var audioOutTrack = -1
        var globalTimeOffsetUs = 0L
        var skipped = 0

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            val firstFormats = readClipFormats(ctx, project.clipEdits[0].sourceUri)
                ?: return MuxResult(false, "Could not read first clip")
            firstFormats.videoFormat?.let { videoOutTrack = muxer.addTrack(it) }
            firstFormats.audioFormat?.let { audioOutTrack = muxer.addTrack(it) }
            if (videoOutTrack < 0) return MuxResult(false, "First clip has no video track")

            val frameDurationUs = deriveFrameDurationUs(firstFormats.videoFormat)
            muxer.start()
            muxerStarted = true

            for ((clipIdx, edit) in project.clipEdits.withIndex()) {
                val baseProgress = clipIdx.toFloat() / project.clipEdits.size

                // Resolve duration so we can compute effective ranges
                val clipDurationMs = readDurationMs(ctx, edit.sourceUri)
                val ranges = edit.effectiveRanges(clipDurationMs)

                val extractor = MediaExtractor()
                val pfd = openPfd(ctx, edit.sourceUri)
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

                    // For each effective range, copy its samples with appropriate offset
                    for ((rangeIdx, range) in ranges.withIndex()) {
                        val rangeFraction = (rangeIdx + 1).toFloat() / ranges.size
                        onProgress(
                            (baseProgress + rangeFraction / project.clipEdits.size) * 0.95f,
                            "Merging clip ${clipIdx + 1}/${project.clipEdits.size} (segment ${rangeIdx + 1}/${ranges.size})..."
                        )

                        val trimStartUs = range.startMs * 1000L
                        val trimEndUs = range.endMs * 1000L

                        // Find each track's actual landing PTS after seek
                        extractor.selectTrack(vTrack)
                        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val vFirstPts = extractor.sampleTime.coerceAtLeast(0L)
                        extractor.unselectTrack(vTrack)

                        val aFirstPts = if (aTrack >= 0) {
                            extractor.selectTrack(aTrack)
                            extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            extractor.unselectTrack(aTrack)
                            pts
                        } else 0L

                        val vEnd = copyTrackWithTrim(
                            extractor, vTrack, muxer, videoOutTrack,
                            globalTimeOffsetUs, vFirstPts, trimStartUs, trimEndUs, buffer
                        )
                        var aEnd = 0L
                        if (aTrack >= 0 && audioOutTrack >= 0) {
                            aEnd = copyTrackWithTrim(
                                extractor, aTrack, muxer, audioOutTrack,
                                globalTimeOffsetUs, aFirstPts, trimStartUs, trimEndUs, buffer
                            )
                        }

                        val rangeEnd = maxOf(vEnd, aEnd)
                        if (rangeEnd > 0) {
                            globalTimeOffsetUs = rangeEnd + frameDurationUs
                        }
                    }
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

    private fun copyTrackWithTrim(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        baseOffsetUs: Long,
        firstPtsUs: Long,
        trimStartUs: Long,
        trimEndUs: Long,
        buffer: ByteBuffer
    ): Long {
        extractor.selectTrack(inputTrack)
        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val info = MediaCodec.BufferInfo()
        var maxOutPts = 0L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val originalPts = extractor.sampleTime
            if (originalPts >= trimEndUs) break
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

    // ===== Compatible mode: Media3 Transformer with multi-range + looping music =====

    @OptIn(UnstableApi::class)
    private suspend fun mergeCompatible(
        ctx: Context,
        project: EditProject,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Pair<Boolean, String>>()

        // Determine which color images we need based on the transitions in use.
        val allTransitions = mutableListOf<Transition>()
        allTransitions += project.clipTransitions
        for (edit in project.clipEdits) allTransitions += edit.rangeTransitions
        val needsBlack = allTransitions.any { it != Transition.NONE && !it.isWhite }
        val needsWhite = allTransitions.any { it != Transition.NONE && it.isWhite }
        val blackImageUri: Uri? = if (needsBlack) getOrCreateColorImageUri(ctx, false) else null
        val whiteImageUri: Uri? = if (needsWhite) getOrCreateColorImageUri(ctx, true) else null

        // Build the sequence with transitions interspersed.
        val items = mutableListOf<EditedMediaItem>()

        for ((clipIdx, edit) in project.clipEdits.withIndex()) {
            val clipDurationMs = readDurationMs(ctx, edit.sourceUri)
            val ranges = edit.effectiveRanges(clipDurationMs)

            for ((rangeIdx, range) in ranges.withIndex()) {
                val mediaItem = MediaItem.Builder()
                    .setUri(edit.sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(range.startMs)
                            .setEndPositionMs(range.endMs)
                            .build()
                    )
                    .build()
                items.add(EditedMediaItem.Builder(mediaItem).build())

                // Range-to-range transition (within the same clip)
                if (rangeIdx < ranges.size - 1) {
                    val rt = edit.transitionAt(rangeIdx)
                    val uri = if (rt.isWhite) whiteImageUri else blackImageUri
                    if (rt != Transition.NONE && uri != null) {
                        items.add(buildTransitionItem(uri, rt))
                    }
                }
            }

            // Clip-to-clip transition (between clips)
            if (clipIdx < project.clipEdits.size - 1) {
                val ct = project.transitionAt(clipIdx)
                val uri = if (ct.isWhite) whiteImageUri else blackImageUri
                if (ct != Transition.NONE && uri != null) {
                    items.add(buildTransitionItem(uri, ct))
                }
            }
        }

        if (items.isEmpty()) {
            return@withContext false to "No segments to merge after applying trim"
        }

        val mainSequence = EditedMediaItemSequence(items)

        val composition: Composition = if (project.musicUri != null) {
            val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(project.musicUri)).build()
            val musicSequence = EditedMediaItemSequence(listOf(musicItem), /* isLooping = */ true)
            Composition.Builder(listOf(mainSequence, musicSequence)).build()
        } else {
            Composition.Builder(listOf(mainSequence)).build()
        }

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

    /**
     * Build an EditedMediaItem for a transition. Uses the transition's color (black or white)
     * and the configured duration.
     */
    @OptIn(UnstableApi::class)
    private fun buildTransitionItem(colorImageUri: Uri, transition: Transition): EditedMediaItem {
        val durationMs = transition.durationMs.coerceAtLeast(1L)
        val mediaItem = MediaItem.Builder()
            .setUri(colorImageUri)
            .setImageDurationMs(durationMs)
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setFrameRate(30)
            .setDurationUs(durationMs * 1000L)
            .build()
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

    private fun readDurationMs(ctx: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, uri)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            retriever.release()
            d
        } catch (_: Exception) { 0L }
    }

    private fun deriveFrameDurationUs(fmt: MediaFormat?): Long {
        if (fmt == null) return DEFAULT_FRAME_DURATION_US
        return try {
            val fr = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
            if (fr > 0) (1_000_000L / fr) else DEFAULT_FRAME_DURATION_US
        } catch (_: Exception) { DEFAULT_FRAME_DURATION_US }
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

    /**
     * Generates a small solid-color PNG and writes it to a stable file in cache.
     * Returns a content/file URI usable by Media3's image MediaItem.
     * Cached file is reused across merges (one per color).
     */
    private fun getOrCreateColorImageUri(ctx: Context, white: Boolean): Uri {
        val name = if (white) "transition_white_1280x720.png" else "transition_black_1280x720.png"
        val cached = File(ctx.cacheDir, name)
        if (cached.exists() && cached.length() > 0) {
            return cached.toUri()
        }
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (white) Color.WHITE else Color.BLACK)
        FileOutputStream(cached).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        bmp.recycle()
        return cached.toUri()
    }
}
