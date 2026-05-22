package com.camcompiler.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Find-by-description detector. Implements the 7-pass algorithm from the spec:
 *
 *   PASS 1: Sample frames at 1s intervals
 *   PASS 2: Compute image embeddings via MobileCLIP (+motion MAD in same pass)
 *   PASS 3: Embed query + expanded alternatives
 *   PASS 4: Compute combined similarity curve
 *   PASS 5: Peak finding (different for Mode A vs Mode B)
 *   PASS 6: Window expansion / composition
 *   PASS 7: Final ranking and output
 *
 * Combined scoring: clipScore is the max CLIP cosine across all phrasings.
 * Motion is mean-absolute-difference of luminance vs previous sampled frame,
 * normalized against MOTION_NORMALIZER. Weights default to 0.7 CLIP + 0.3 motion,
 * auto-boosting motion to 0.5 for action-y queries (downhill, fast, swerve, etc.).
 */
class FindMomentDetector(
    private val ctx: Context,
    private val clip: VideoClip,
    private val settings: FindMomentSettings,
) {
    fun analyze(): Flow<FindMomentEvent> = flow {
        val startTimeMs = System.currentTimeMillis()
        try {
            coroutineScope {
                val progressChannel = Channel<FindMomentProgress>(Channel.UNLIMITED)
                val resultChannel = Channel<FindMomentAnalysis>(1)

                launch {
                    runDetection(progressChannel, resultChannel, startTimeMs)
                }

                // Multiplex progress and result events into a single Flow
                val progressFlow = progressChannel.consumeAsFlow()
                progressFlow.collect { emit(FindMomentEvent.Progress(it)) }
                val result = resultChannel.tryReceive().getOrNull()
                if (result != null) emit(FindMomentEvent.Done(result))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            emit(FindMomentEvent.Progress(FindMomentProgress(
                phase = FindMomentPhase.CANCELLED, percent = 0f
            )))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}", e)
            emit(FindMomentEvent.Progress(FindMomentProgress(
                phase = FindMomentPhase.FAILED, percent = 0f,
                errorMessage = e.message ?: "Unknown error"
            )))
        }
    }

    private suspend fun runDetection(
        progressChannel: Channel<FindMomentProgress>,
        resultChannel: Channel<FindMomentAnalysis>,
        startTimeMs: Long,
    ) {
        // --- PASS 0: Load model ---
        val clipModel = MobileClipInference.getInstance(ctx)
            ?: throw IllegalStateException(
                "MobileCLIP model could not be loaded. " +
                "Verify the .tflite files are in app/src/main/assets/."
            )

        // --- PASS 3 (moved earlier): Expand query and embed all phrasings ---
        // We do text first because it's fast and lets us start frame work with embeddings ready.
        progressChannel.send(FindMomentProgress(
            phase = FindMomentPhase.EMBEDDING_QUERIES, percent = 0.02f
        ))
        val phrasings = QueryExpander.expand(settings.query)
        Log.d(TAG, "Query '${settings.query}' expanded to ${phrasings.size} phrasings: $phrasings")
        val textEmbeddings: List<FloatArray> = phrasings.map { clipModel.embedText(it) }
        Log.d(TAG, "Text embeddings: dim=${textEmbeddings.firstOrNull()?.size ?: -1}")

        // Action query? Auto-boost motion weight.
        val isAction = QueryExpander.isActionQuery(settings.query)
        val clipWeight = if (isAction) 0.5f else 0.7f
        val motionWeight = if (isAction) 0.5f else 0.3f
        Log.d(TAG, "Scoring weights: clip=$clipWeight motion=$motionWeight (action=$isAction)")

        // --- PASS 1+2: Sample frames + compute embeddings + motion ---
        val clipDurMs = clip.durationSec * 1000L
        if (clipDurMs < SAMPLE_INTERVAL_MS * 3) {
            throw IllegalStateException("Clip too short to analyze (${clipDurMs}ms)")
        }
        val sampleTimes = buildList {
            var t = 0L
            while (t < clipDurMs) {
                add(t)
                t += SAMPLE_INTERVAL_MS
            }
        }
        val numSamples = sampleTimes.size
        Log.d(TAG, "Sampling $numSamples frames at ${SAMPLE_INTERVAL_MS}ms intervals")

        progressChannel.send(FindMomentProgress(
            phase = FindMomentPhase.SAMPLING, percent = 0.05f,
            framesProcessed = 0, framesTotal = numSamples
        ))

        val mmr = MediaMetadataRetriever()
        val embeddings = arrayOfNulls<FloatArray>(numSamples)
        val motionScores = FloatArray(numSamples)
        var prevBmp: Bitmap? = null

        try {
            mmr.setDataSource(ctx, clip.uri)

            for (i in sampleTimes.indices) {
                coroutineContext.ensureActive()
                val tMs = sampleTimes[i]
                val bmp = mmr.getFrameAtTime(
                    tMs * 1000L,  // MMR wants microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue

                // Embed BEFORE motion (recycleInput=false because we still need the bitmap)
                embeddings[i] = clipModel.embedImage(bmp, recycleInput = false)

                // Motion vs previous frame
                if (prevBmp != null) {
                    motionScores[i] = FrameSampler.meanAbsoluteDifference(prevBmp!!, bmp)
                }

                // Rotate prevBmp; recycle the older one
                prevBmp?.recycle()
                prevBmp = bmp

                // Progress update every 8 frames
                if (i % 8 == 0) {
                    val frac = (i + 1).toFloat() / numSamples.toFloat()
                    val overall = 0.05f + 0.85f * frac
                    val elapsedMs = System.currentTimeMillis() - startTimeMs
                    val estTotalMs = if (overall > 0.05f) (elapsedMs / overall).toLong() else 0L
                    val estRemainingMs = (estTotalMs - elapsedMs).coerceAtLeast(0L)
                    progressChannel.send(FindMomentProgress(
                        phase = FindMomentPhase.SCORING,
                        percent = overall,
                        framesProcessed = i + 1,
                        framesTotal = numSamples,
                        estimatedRemainingMs = estRemainingMs,
                    ))
                }
            }
            prevBmp?.recycle()
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        // --- PASS 4: Combined similarity curve ---
        progressChannel.send(FindMomentProgress(
            phase = FindMomentPhase.COMPOSING, percent = 0.92f,
            framesProcessed = numSamples, framesTotal = numSamples
        ))

        val scores = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val emb = embeddings[i] ?: continue
            // Max similarity across all phrasings (best-matching phrasing wins)
            var bestClipSim = -1f
            for (textEmb in textEmbeddings) {
                val sim = clipModel.similarity(emb, textEmb)
                if (sim > bestClipSim) bestClipSim = sim
            }
            // CLIP cosine is typically in [-0.3, 0.5] for real text-image pairs.
            // Normalize to roughly 0..1 by shifting and scaling.
            val normClip = ((bestClipSim + 0.1f) / 0.4f).coerceIn(0f, 1f)
            val normMotion = (motionScores[i] / MOTION_NORMALIZER).coerceIn(0f, 1f)
            scores[i] = clipWeight * normClip + motionWeight * normMotion
        }

        // Light smoothing — 3-sample sliding average to reduce noise spikes
        val smoothed = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = (i - 1).coerceAtLeast(0)
            val hi = (i + 1).coerceAtMost(numSamples - 1)
            var sum = 0f; var n = 0
            for (j in lo..hi) { sum += scores[j]; n++ }
            smoothed[i] = sum / n
        }

        // --- PASS 5-7: Mode-specific peak finding + window composition ---
        val candidates = when (settings.mode) {
            FindMomentSettings.SearchMode.FIND_ONE_MOMENT ->
                modeASelectContinuousSegments(smoothed, sampleTimes, clipDurMs)
            FindMomentSettings.SearchMode.BUILD_REEL ->
                modeBComposeReel(smoothed, sampleTimes, clipDurMs)
        }

        Log.d(TAG, "Detection complete: ${candidates.size} candidates")
        resultChannel.send(FindMomentAnalysis(
            candidates = candidates,
            sourceClip = clip,
            settings = settings,
            expandedQueries = phrasings,
        ))
        progressChannel.send(FindMomentProgress(
            phase = FindMomentPhase.DONE, percent = 1f,
            framesProcessed = numSamples, framesTotal = numSamples
        ))
        progressChannel.close()
        resultChannel.close()
    }

    // =====================================================================
    // MODE A: find 3-5 continuous segments matching the query
    // =====================================================================

    private fun modeASelectContinuousSegments(
        scores: FloatArray,
        sampleTimes: List<Long>,
        clipDurMs: Long,
    ): List<MatchCandidate> {
        val numSamples = scores.size
        if (numSamples == 0) return emptyList()

        val targetLengthMs = settings.targetLengthMs
        val halfWindowMs = targetLengthMs / 2

        // Find all local maxima above threshold
        val peaks = mutableListOf<Pair<Int, Float>>()  // (sample index, score)
        for (i in 0 until numSamples) {
            val s = scores[i]
            if (s < MODE_A_MIN_THRESHOLD) continue
            val lo = (i - 2).coerceAtLeast(0)
            val hi = (i + 2).coerceAtMost(numSamples - 1)
            var isMax = true
            for (j in lo..hi) {
                if (j != i && scores[j] > s) { isMax = false; break }
            }
            if (isMax) peaks.add(i to s)
        }
        if (peaks.isEmpty()) {
            // Fall back: take top-3 raw scores regardless of "peak-ness"
            val ranked = scores.withIndex().sortedByDescending { it.value }.take(3)
            return ranked.map { (idx, score) ->
                buildCandidate(idx, score, sampleTimes, clipDurMs, scores, targetLengthMs, halfWindowMs)
            }
        }

        // Sort peaks by score desc, dedupe by minimum time-gap (no two candidates overlap)
        val sortedPeaks = peaks.sortedByDescending { it.second }
        val chosenIndices = mutableListOf<Int>()
        for ((idx, _) in sortedPeaks) {
            val tThis = sampleTimes[idx]
            val tooClose = chosenIndices.any { ci ->
                kotlin.math.abs(sampleTimes[ci] - tThis) < targetLengthMs
            }
            if (!tooClose) chosenIndices.add(idx)
            if (chosenIndices.size >= MODE_A_MAX_CANDIDATES) break
        }

        return chosenIndices.map { idx ->
            buildCandidate(idx, scores[idx], sampleTimes, clipDurMs, scores, targetLengthMs, halfWindowMs)
        }
    }

    private fun buildCandidate(
        peakIdx: Int,
        peakScore: Float,
        sampleTimes: List<Long>,
        clipDurMs: Long,
        scores: FloatArray,
        targetLengthMs: Long,
        halfWindowMs: Long,
    ): MatchCandidate {
        val peakMs = sampleTimes[peakIdx]
        var start = (peakMs - halfWindowMs).coerceAtLeast(0L)
        var end = start + targetLengthMs
        if (end > clipDurMs) {
            end = clipDurMs
            start = (end - targetLengthMs).coerceAtLeast(0L)
        }

        // Average score within the window
        var sum = 0f; var n = 0
        for (i in sampleTimes.indices) {
            if (sampleTimes[i] in start..end) { sum += scores[i]; n++ }
        }
        val avg = if (n > 0) sum / n else peakScore

        return MatchCandidate(
            sourceUri = clip.uri,
            sourceClipName = clip.name,
            startMs = start,
            endMs = end,
            peakMs = peakMs,
            score = peakScore,
            avgScore = avg,
            dominantSignal = MatchCandidate.Signal.CLIP,  // simplification — could compute from weights
        )
    }

    // =====================================================================
    // MODE B: compose multiple short scenes into a reel of targetLengthMs total
    // =====================================================================

    private fun modeBComposeReel(
        scores: FloatArray,
        sampleTimes: List<Long>,
        clipDurMs: Long,
    ): List<MatchCandidate> {
        val numSamples = scores.size
        if (numSamples == 0) return emptyList()

        // Find ALL peaks above a lower threshold
        val peaks = mutableListOf<Pair<Int, Float>>()
        for (i in 0 until numSamples) {
            val s = scores[i]
            if (s < MODE_B_MIN_THRESHOLD) continue
            val lo = (i - 1).coerceAtLeast(0)
            val hi = (i + 1).coerceAtMost(numSamples - 1)
            var isMax = true
            for (j in lo..hi) {
                if (j != i && scores[j] > s) { isMax = false; break }
            }
            if (isMax) peaks.add(i to s)
        }

        if (peaks.isEmpty()) return emptyList()

        // Build moment windows around each peak. Scene length depends on how peaky the score is.
        // For now use a fixed short window of 8s.
        val sceneLengthMs = MODE_B_SCENE_LENGTH_MS
        val sceneHalfMs = sceneLengthMs / 2

        data class Scene(val peakIdx: Int, val peakMs: Long, val score: Float, val start: Long, val end: Long)
        val sceneCandidates = peaks.map { (idx, score) ->
            val peakMs = sampleTimes[idx]
            var start = (peakMs - sceneHalfMs).coerceAtLeast(0L)
            var end = start + sceneLengthMs
            if (end > clipDurMs) { end = clipDurMs; start = (end - sceneLengthMs).coerceAtLeast(0L) }
            Scene(idx, peakMs, score, start, end)
        }.sortedByDescending { it.score }

        // Greedy: pick highest-scoring scenes, skipping overlaps, until total length ≈ target
        val chosen = mutableListOf<Scene>()
        var totalMs = 0L
        for (sc in sceneCandidates) {
            val overlaps = chosen.any { c ->
                sc.start < c.end + MODE_B_MIN_GAP_MS && sc.end > c.start - MODE_B_MIN_GAP_MS
            }
            if (overlaps) continue
            if (totalMs + (sc.end - sc.start) > settings.targetLengthMs + 5000L) continue
            chosen.add(sc)
            totalMs += sc.end - sc.start
            if (totalMs >= settings.targetLengthMs) break
        }

        // Reorder chronologically for the final reel
        val chronological = chosen.sortedBy { it.start }

        return chronological.map { sc ->
            val windowScores = sampleTimes.withIndex()
                .filter { it.value in sc.start..sc.end }
                .map { scores[it.index] }
            val avg = if (windowScores.isNotEmpty()) windowScores.average().toFloat() else sc.score
            MatchCandidate(
                sourceUri = clip.uri,
                sourceClipName = clip.name,
                startMs = sc.start,
                endMs = sc.end,
                peakMs = sc.peakMs,
                score = sc.score,
                avgScore = avg,
                dominantSignal = MatchCandidate.Signal.CLIP,
            )
        }
    }

    companion object {
        private const val TAG = "FindMomentDetector"

        // Frame sampling: every 1 second is a reasonable density for finding moments
        // in a multi-minute video while keeping analysis time bounded.
        private const val SAMPLE_INTERVAL_MS = 1000L

        // Mode A: thresholds and limits for finding distinct moments
        // CLIP normalized scores typically peak around 0.5-0.8 for strong matches.
        // 0.3 is roughly "borderline good" after our combined-weight formula.
        private const val MODE_A_MIN_THRESHOLD = 0.30f
        private const val MODE_A_MAX_CANDIDATES = 5

        // Mode B: more permissive threshold to find many short moments
        private const val MODE_B_MIN_THRESHOLD = 0.25f

        // Each scene in a Mode B reel is this long. Short enough to feel like a "moment",
        // long enough to read on screen.
        private const val MODE_B_SCENE_LENGTH_MS = 8_000L

        // Minimum spacing between adjacent scenes in a reel (so they don't blend)
        private const val MODE_B_MIN_GAP_MS = 500L

        // Motion MAD normalizer — typical bike-vlog motion MAD is 5-25.
        // 30 maps that range to roughly 0..1. Same value used in HighlightDetector.
        private const val MOTION_NORMALIZER = 30f
    }
}

/**
 * Events emitted by the detector flow. Either progress updates or a final result.
 */
sealed class FindMomentEvent {
    data class Progress(val progress: FindMomentProgress) : FindMomentEvent()
    data class Done(val analysis: FindMomentAnalysis) : FindMomentEvent()
}
