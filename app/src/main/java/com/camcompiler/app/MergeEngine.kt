package com.camcompiler.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MergeEngine {

    sealed class Result {
        data class Success(val outputName: String, val method: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Merges the given clips in the given order. Reports progress via callback.
     * Tries Media3 Transformer first (fast, hardware-accelerated).
     * On failure, falls back to FFmpegKit (slower but more permissive).
     */
    suspend fun merge(
        ctx: Context,
        clipUris: List<Uri>,
        onProgress: (Float, String) -> Unit
    ): Result = coroutineScope {
        if (clipUris.isEmpty()) return@coroutineScope Result.Failure("No clips to merge")

        val outputFile = createTempOutputFile(ctx)

        // Try Media3 first
        onProgress(0f, "Merging with Media3 (hardware-accelerated)...")
        val media3Result = tryMedia3(ctx, clipUris, outputFile, onProgress)
        if (media3Result != null) {
            saveToDownloads(ctx, outputFile)
            outputFile.delete()
            return@coroutineScope Result.Success(outputFile.name, "Media3")
        }

        // Fallback: FFmpegKit
        onProgress(0f, "Media3 couldn't handle these clips. Falling back to FFmpegKit (slower)...")
        outputFile.delete()
        val ffmpegOutput = createTempOutputFile(ctx)
        val ffmpegSuccess = tryFFmpeg(ctx, clipUris, ffmpegOutput, onProgress)
        if (ffmpegSuccess) {
            saveToDownloads(ctx, ffmpegOutput)
            ffmpegOutput.delete()
            return@coroutineScope Result.Success(ffmpegOutput.name, "FFmpegKit")
        }

        ffmpegOutput.delete()
        return@coroutineScope Result.Failure(
            "Both Media3 and FFmpegKit failed. Likely a codec issue. Try fewer clips first to identify which one is problematic."
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun tryMedia3(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()

        val mediaItems = clipUris.map { uri ->
            EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
        }
        val sequence = EditedMediaItemSequence.Builder(mediaItems).build()
        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    deferred.complete(true)
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    deferred.complete(false)
                }
            })
            .build()

        try {
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            return@withContext false
        }

        // Poll progress on a separate coroutine
        val progressHolder = ProgressHolder()
        launch {
            while (isActive && !deferred.isCompleted) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f, "Merging with Media3...")
                }
                delay(500)
            }
        }

        deferred.await()
    }

    private suspend fun tryFFmpeg(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // FFmpeg can't read content:// URIs directly; copy to local cache files
            onProgress(0.0f, "Preparing files for FFmpeg...")
            val tempDir = File(ctx.cacheDir, "ffmpeg_input").apply {
                deleteRecursively()
                mkdirs()
            }

            val localFiles = mutableListOf<File>()
            clipUris.forEachIndexed { idx, uri ->
                val out = File(tempDir, "clip_%04d.mp4".format(idx))
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                localFiles.add(out)
                onProgress(
                    (idx + 1).toFloat() / clipUris.size * 0.3f,
                    "Copying clip ${idx + 1} of ${clipUris.size}..."
                )
            }

            // Build concat list
            val listFile = File(tempDir, "list.txt")
            listFile.writeText(localFiles.joinToString("\n") { "file '${it.absolutePath}'" })

            // Try stream copy first (fast, no re-encode)
            onProgress(0.35f, "Concatenating with FFmpeg (stream copy)...")
            val streamCopyCmd = "-y -f concat -safe 0 -i ${listFile.absolutePath} -c copy ${outputFile.absolutePath}"
            val session = FFmpegKit.execute(streamCopyCmd)

            if (ReturnCode.isSuccess(session.returnCode)) {
                tempDir.deleteRecursively()
                onProgress(1f, "Done!")
                return@withContext true
            }

            // Stream copy failed -> try re-encode (slower but handles mixed codecs)
            onProgress(0.4f, "Stream copy failed, re-encoding (this is slower)...")
            val reEncodeCmd = "-y -f concat -safe 0 -i ${listFile.absolutePath} -c:v libx264 -preset ultrafast -crf 23 -c:a aac ${outputFile.absolutePath}"
            val session2 = FFmpegKit.execute(reEncodeCmd)
            tempDir.deleteRecursively()

            if (ReturnCode.isSuccess(session2.returnCode)) {
                onProgress(1f, "Done!")
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            return@withContext false
        }
    }

    private fun createTempOutputFile(ctx: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(ctx.cacheDir, "vlog_$timestamp.mp4")
    }

    private fun saveToDownloads(ctx: Context, sourceFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    ctx.contentResolver.openOutputStream(it)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    }
                }
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists()) downloads.mkdirs()
                val dest = File(downloads, sourceFile.name)
                sourceFile.copyTo(dest, overwrite = true)
            }
        } catch (e: Exception) {
            // best-effort save
        }
    }
}
