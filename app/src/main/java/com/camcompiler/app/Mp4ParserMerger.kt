package com.camcompiler.app

import android.content.Context
import android.net.Uri
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Track
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import org.mp4parser.muxer.tracks.AppendTrack
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel

/**
 * mp4parser-based merger — pure byte-level MP4 concatenation.
 *
 * Much faster than MediaMuxer for large jobs because it works directly on
 * MP4 atoms/boxes without involving Android's media framework. Doesn't
 * decode or re-encode anything. Output size is essentially sum of inputs
 * plus a small container overhead (typically <1%).
 */
object Mp4ParserMerger {

    data class Result(val success: Boolean, val error: String = "", val skippedCount: Int = 0)

    /**
     * Concatenates the given clip URIs into outputFile.
     * Reports progress through onProgress(fraction, statusText).
     * Returns a Result describing success/failure plus how many clips were skipped.
     */
    fun merge(
        ctx: Context,
        clipUris: List<Uri>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Result {
        if (clipUris.isEmpty()) return Result(false, "No clips provided")

        // Stage 1: copy URI content to local cache files (mp4parser needs File or seekable channels)
        val cacheDir = File(ctx.cacheDir, "mp4parser_in").apply {
            deleteRecursively()
            mkdirs()
        }
        val localFiles = mutableListOf<File>()
        var skipped = 0

        try {
            for ((idx, uri) in clipUris.withIndex()) {
                onProgress(
                    idx.toFloat() / clipUris.size * 0.4f,
                    "Preparing clip ${idx + 1}/${clipUris.size}..."
                )
                val local = File(cacheDir, "in_%04d.mp4".format(idx))
                val copied = copyUriToFile(ctx, uri, local)
                if (copied && local.length() > 1024) {
                    localFiles.add(local)
                } else {
                    skipped++
                    local.delete()
                }
            }

            if (localFiles.isEmpty()) {
                return Result(false, "No valid clips could be read", skipped)
            }

            // Stage 2: parse each MP4 and group tracks by handler type (video, audio, etc.)
            onProgress(0.45f, "Reading MP4 structure...")
            val movies = mutableListOf<Movie>()
            for ((idx, file) in localFiles.withIndex()) {
                try {
                    movies.add(MovieCreator.build(file.absolutePath))
                } catch (e: Exception) {
                    skipped++
                    // continue; we'll work with what we have
                }
                if (idx % 10 == 0) {
                    onProgress(
                        0.45f + (idx.toFloat() / localFiles.size) * 0.1f,
                        "Reading MP4 structure ${idx + 1}/${localFiles.size}..."
                    )
                }
            }

            if (movies.isEmpty()) {
                return Result(false, "No clips could be parsed as MP4", skipped)
            }

            // Group tracks by handler (video/audio/etc); each group will be a single track in output
            val tracksByHandler = mutableMapOf<String, MutableList<Track>>()
            for (movie in movies) {
                for (track in movie.tracks) {
                    val handler = track.handler ?: continue
                    tracksByHandler.getOrPut(handler) { mutableListOf() }.add(track)
                }
            }

            if (tracksByHandler.isEmpty()) {
                return Result(false, "No tracks found in inputs", skipped)
            }

            // Stage 3: build a new movie by appending all tracks of each handler type
            onProgress(0.6f, "Building merged movie structure...")
            val outputMovie = Movie()
            for ((_, tracks) in tracksByHandler) {
                if (tracks.size == 1) {
                    outputMovie.addTrack(tracks[0])
                } else {
                    outputMovie.addTrack(AppendTrack(*tracks.toTypedArray()))
                }
            }

            // Stage 4: write output
            onProgress(0.7f, "Writing merged file...")
            val container = DefaultMp4Builder().build(outputMovie)
            FileOutputStream(outputFile).use { fos ->
                val channel: FileChannel = fos.channel
                container.writeContainer(channel)
            }

            onProgress(1f, "Done")
            return Result(true, skippedCount = skipped)
        } catch (e: OutOfMemoryError) {
            return Result(false, "Out of memory — try fewer clips per merge", skipped)
        } catch (e: Exception) {
            return Result(false, e.message ?: "Unknown mp4parser error", skipped)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun copyUriToFile(ctx: Context, uri: Uri, dest: File): Boolean {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            } != null
        } catch (_: Exception) {
            false
        }
    }
}
