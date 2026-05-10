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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MergeEngine {

    sealed class Result {
        data class Success(val outputName: String, val method: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Merges the given clips in order using Media3 Transformer.
     * Reports progress via callback.
     */
    suspend fun merge(
        ctx: Context,
        clipUris: List<Uri>,
        onProgress: (Float, String) -> Unit
    ): Result = coroutineScope {
        if (clipUris.isEmpty()) return@coroutineScope Result.Failure("No clips to merge")

        val outputFile = createTempOutputFile(ctx)

        onProgress(0f, "Merging with Media3 (hardware-accelerated)...")
        val (success, errorMsg) = tryMedia3(ctx, clipUris, outputFile, onProgress)
        if (success) {
            saveToDownloads(ctx, outputFile)
            outputFile.delete()
            return@coroutineScope Result.Success(outputFile.name, "Media3")
        }

        outputFile.delete()
        return@coroutineScope Result.Failure(
            "Merge failed: $errorMsg. This usually means the clips have mismatched codecs/resolutions. Try fewer clips at a time to identify which one is problematic."
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun tryMedia3(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Pair<Boolean, String>>()

        val mediaItems = clipUris.map { uri ->
            EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
        }
        val sequence = EditedMediaItemSequence.Builder(mediaItems).build()
        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    deferred.complete(true to "")
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    deferred.complete(false to (e.message ?: "unknown export error"))
                }
            })
            .build()

        try {
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            return@withContext false to (e.message ?: "failed to start transformer")
        }

        // Poll progress on a separate coroutine
        val progressHolder = ProgressHolder()
        launch {
            while (isActive && !deferred.isCompleted) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f, "Merging... (Media3)")
                }
                delay(500)
            }
        }

        deferred.await()
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
