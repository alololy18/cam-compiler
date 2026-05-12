package com.camcompiler.app

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Highlight detection engine.
 *
 * Implements the 6-pass pipeline from the spec:
 *   Pass 1: I-frame index → list of keyframe timestamps
 *   Pass 2: Per-keyframe Y-histogram + motion vs prev → score arrays
 *   Pass 3: Peak detection on the combined score curve
 *   Pass 4: Window expansion (15s windows centered on peaks)
 *   Pass 5: Adaptive refinement — finer sampling inside each window
 *   Pass 6: Deduplication + global ranking across multi-clip input
 *
 * Reports progress via a Flow that emits DetectionProgress.
 * Cancellable: collecting code can cancel the surrounding coroutine to abort cleanly.
 */
class HighlightDetector(
    private val ctx: Context,
    private val clips: List<VideoClip>,
    private val settings: HighlightSettings,
) {
    companion object {
        private const val TAG = "HighlightDetector"

        // Minimum gap between adjacent peaks to count them as distinct (in score-array indices,
        // which correspond roughly to keyframes — typically 1-2s apart).
        private const val MIN_PEAK_GAP_SAMPLES = 4

        // After window expansion, refinement samples this many times inside the window for
        // finer peak localization.
        private const val REFINEMENT_SAMPLES_PER_WINDOW = 8

        // After motion MAD is computed, normalize against this max for the score in [0,1].
        // 30 is a tunable: typical bike-vlog motion MAD is 5-25; we want 0-1 range.
        private const val MOTION_MAD_NORMALIZER = 30f

        // Deduplication: candidates whose windows overlap or are within this distance get merged.
        private const val MERGE_GAP_MS = 2_000L
    }

    /**
     * Per-keyframe scores. Indices align 1:1 with the keyframe sample list.
     */
    private data class ScoreCurve(
        val timeMs: LongArray,
        val sceneScore: FloatArray,    // 1 - histogram correlation (0 = same, 1 = totally different)
        val motionScore: FloatArray,   // normalized MAD between consecutive frames
        val combinedScore: FloatArray, // sceneWeight * scene + motionWeight * motion
    )

    /**
     * Run the full detection. Returns a Flow of progress updates ending with one
     * terminal emission where phase == DONE/CANCELLED/FAILED.
     *
     * The final analysis is delivered via [onResult] when phase is DONE.
     */
    fun runFlow(onResult: (HighlightAnalysis) -> Unit): Flow<DetectionProgress> = channelFlow {
        try {
            val analysis = runInternal(this)
            send(DetectionProgress(DetectionPhase.DONE, 1f,
                totalClips = clips.size,
                currentClipIdx = clips.size - 1,
                sceneCandidatesFound = analysis.candidates.count { it.signal == HighlightSignal.SCENE_CHANGE || it.signal == HighlightSignal.COMBINED },
                motionCandidatesFound = analysis.candidates.count { it.signal == HighlightSignal.MOTION_PEAK || it.signal == HighlightSignal.COMBINED },
            ))
            onResult(analysis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            send(DetectionProgress(DetectionPhase.CANCELLED, 0f))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            send(DetectionProgress(DetectionPhase.FAILED, 0f, errorMessage = e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun runInternal(progressChannel: SendChannel<DetectionProgress>): HighlightAnalysis {
        val startTimeMs = System.currentTimeMillis()
        val totalSourceDurationMs = clips.sumOf { it.durationSec * 1000L }

        // Allocate roughly equal time budget per clip for progress reporting.
        val allCandidates = mutableListOf<HighlightCandidate>()
        val clipDurations = LongArray(clips.size) { clips[it].durationSec * 1000L }
        val totalDurMs = clipDurations.sum().coerceAtLeast(1L)

        for ((clipIdx, clip) in clips.withIndex()) {
            coroutineContext.ensureActive()
            val clipFractionBase = clipDurations.take(clipIdx).sum().toFloat() / totalDurMs.toFloat()
            val clipFractionSize = clipDurations[clipIdx].toFloat() / totalDurMs.toFloat()

            // --- PASS 1: I-frame index ---
            progressChannel.send(DetectionProgress(
                phase = DetectionPhase.INDEXING,
                percent = clipFractionBase,
                currentClipIdx = clipIdx,
                totalClips = clips.size,
            ))
            val kfTimestamps = FrameSampler.extractKeyframeTimestamps(ctx, clip.uri)
            if (kfTimestamps.size < 3) {
                Log.w(TAG, "Clip ${clip.name} has < 3 keyframes; skipping")
                continue
            }
            Log.d(TAG, "Clip ${clip.name}: ${kfTimestamps.size} keyframes")

            // --- PASS 2: Sample + score each keyframe in a SINGLE pass ---
            // We compute scene-change AND motion-MAD as we walk frames.
            // To keep memory bounded, we only retain the previous frame's bitmap (recycled each step).
            progressChannel.send(DetectionProgress(
                phase = DetectionPhase.SCORING,
                percent = clipFractionBase + clipFractionSize * 0.1f,
                currentClipIdx = clipIdx,
                totalClips = clips.size,
            ))
            val curve = computeScoreCurveSinglePass(
                ctx = ctx,
                clip = clip,
                keyframeTimestamps = kfTimestamps,
                onProgress = { kfIdx, total ->
                    if (kfIdx % 16 == 0) {
                        val kfProgress = kfIdx.toFloat() / total.toFloat()
                        val overall = clipFractionBase + clipFractionSize * (0.1f + 0.7f * kfProgress)
                        val elapsedMs = System.currentTimeMillis() - startTimeMs
                        val estTotalMs = if (overall > 0.02f) (elapsedMs / overall).toLong() else 0L
                        val estRemainingMs = (estTotalMs - elapsedMs).coerceAtLeast(0L)
                        progressChannel.send(DetectionProgress(
                            phase = DetectionPhase.SCORING,
                            percent = overall,
                            currentClipIdx = clipIdx,
                            totalClips = clips.size,
                            estimatedRemainingMs = estRemainingMs,
                        ))
                    }
                }
            ) ?: continue  // skip clip if scoring failed
            if (curve.timeMs.size < 3) continue

            // --- PASS 3: peak detection ---
            val sensitivity = settings.sensitivity
            val peakIndices = findPeaks(curve.combinedScore,
                threshold = min(sensitivity.sceneThreshold, sensitivity.motionThreshold) * 0.7f,
                minGap = MIN_PEAK_GAP_SAMPLES)
            Log.d(TAG, "Clip ${clip.name}: ${peakIndices.size} raw peaks (sensitivity=${sensitivity.name})")

            // --- PASS 4: window expansion ---
            val rawCandidates = peakIndices.map { idx ->
                val peakMs = curve.timeMs[idx]
                val halfWindow = settings.windowDurationMs / 2
                val rawStart = (peakMs - halfWindow).coerceAtLeast(0L)
                val rawEnd = (peakMs + halfWindow).coerceAtMost(clipDurations[clipIdx])
                val sceneScore = curve.sceneScore[idx]
                val motionScore = curve.motionScore[idx]
                val sig = pickSignal(sceneScore, motionScore, sensitivity)
                HighlightCandidate(
                    sourceClipIdx = clipIdx,
                    sourceUri = clip.uri,
                    sourceClipName = clip.name,
                    startMs = rawStart,
                    endMs = rawEnd,
                    peakMs = peakMs,
                    sceneScore = sceneScore,
                    motionScore = motionScore,
                    score = curve.combinedScore[idx],
                    signal = sig,
                )
            }

            // --- PASS 5: refinement (sample more finely around top peaks) ---
            progressChannel.send(DetectionProgress(
                phase = DetectionPhase.REFINING,
                percent = clipFractionBase + clipFractionSize * 0.7f,
                currentClipIdx = clipIdx,
                totalClips = clips.size,
                sceneCandidatesFound = allCandidates.count { it.signal == HighlightSignal.SCENE_CHANGE || it.signal == HighlightSignal.COMBINED },
                motionCandidatesFound = allCandidates.count { it.signal == HighlightSignal.MOTION_PEAK || it.signal == HighlightSignal.COMBINED },
            ))
            val refined = refinePeaks(ctx, clip, clipDurations[clipIdx], rawCandidates)

            // Snap window edges to keyframes for clean cuts
            val snappedKfs = kfTimestamps  // same list, used as keyframe index
            val withSnap = refined.map { c ->
                val newStart = snapToKeyframeAtOrBefore(snappedKfs, c.startMs)
                val newEnd = snapToKeyframeAtOrAfter(snappedKfs, c.endMs, clipDurations[clipIdx])
                c.copy(startMs = newStart, endMs = newEnd)
            }

            allCandidates += withSnap
        }

        // --- PASS 6: global dedup + ranking across all clips ---
        coroutineContext.ensureActive()
        progressChannel.send(DetectionProgress(
            phase = DetectionPhase.FINALIZING,
            percent = 0.95f,
            currentClipIdx = clips.size - 1,
            totalClips = clips.size,
        ))
        val deduped = dedupAndRank(allCandidates)
        Log.d(TAG, "Total candidates after dedup: ${deduped.size}")

        return HighlightAnalysis(
            candidates = deduped,
            sourceClips = clips,
            totalSourceDurationMs = totalSourceDurationMs,
            settings = settings,
        )
    }

    /**
     * Compute scene + motion + combined scores in a single pass, keeping memory bounded.
     * We hold only the previous bitmap (for motion MAD) and the previous histogram (for
     * scene correlation), recycling as we step forward.
     *
     * @param onProgress called occasionally with (currentIdx, total) for UI updates.
     * @return ScoreCurve, or null if extraction failed before producing enough samples.
     */
    private suspend fun computeScoreCurveSinglePass(
        ctx: Context,
        clip: VideoClip,
        keyframeTimestamps: List<Long>,
        onProgress: suspend (Int, Int) -> Unit,
    ): ScoreCurve? = withContext(Dispatchers.Default) {
        val total = keyframeTimestamps.size
        if (total < 3) return@withContext null

        val tMsList = mutableListOf<Long>()
        val sceneList = mutableListOf<Float>()
        val motionList = mutableListOf<Float>()

        FrameSampler.open(ctx, clip.uri).use { sampler ->
            var prevHist: IntArray? = null
            var prevBmp: android.graphics.Bitmap? = null

            for ((kfIdx, tMs) in keyframeTimestamps.withIndex()) {
                coroutineContext.ensureActive()
                val bmp = sampler.frameAt(tMs, syncOnly = true)
                if (bmp == null) {
                    // Skip this keyframe but continue with the next
                    continue
                }
                val hist = FrameSampler.yHistogram(bmp)

                // Scene score: 1 - correlation vs previous histogram
                val sceneScore = if (prevHist != null) {
                    val corr = FrameSampler.histogramCorrelation(prevHist!!, hist)
                    (1f - corr).coerceIn(0f, 1f)
                } else 0f

                // Motion score: normalized MAD vs previous bitmap
                val motionScore = if (prevBmp != null) {
                    (FrameSampler.meanAbsoluteDifference(prevBmp!!, bmp) / MOTION_MAD_NORMALIZER)
                        .coerceIn(0f, 1f)
                } else 0f

                tMsList.add(tMs)
                sceneList.add(sceneScore)
                motionList.add(motionScore)

                // Recycle previous bitmap, advance state
                prevBmp?.recycle()
                prevBmp = bmp
                prevHist = hist

                onProgress(kfIdx, total)
            }
            prevBmp?.recycle()
        }

        if (tMsList.size < 3) return@withContext null

        val n = tMsList.size
        val tMsArr = LongArray(n) { tMsList[it] }
        val sceneArr = FloatArray(n) { sceneList[it] }
        val motionArr = FloatArray(n) { motionList[it] }
        val combined = FloatArray(n) { i ->
            (settings.sceneWeight * sceneArr[i] + settings.motionWeight * motionArr[i])
                .coerceIn(0f, 1f)
        }
        ScoreCurve(tMsArr, sceneArr, motionArr, combined)
    }

    /**
     * Find local maxima in a score array. A point is a peak if:
     *  - It's >= threshold
     *  - It's strictly greater than at least one of its neighbors within MIN_PEAK_GAP_SAMPLES range
     *  - It's the maximum within its MIN_PEAK_GAP_SAMPLES neighborhood
     */
    private fun findPeaks(scores: FloatArray, threshold: Float, minGap: Int): List<Int> {
        val peaks = mutableListOf<Int>()
        var i = 0
        while (i < scores.size) {
            val s = scores[i]
            if (s < threshold) { i++; continue }
            // Check it's a local max within [i-minGap, i+minGap]
            val from = max(0, i - minGap)
            val to = min(scores.size - 1, i + minGap)
            var isMax = true
            for (j in from..to) {
                if (j != i && scores[j] > s) { isMax = false; break }
            }
            if (isMax) {
                peaks.add(i)
                i += minGap + 1  // skip past the neighborhood to avoid double-counting
            } else {
                i++
            }
        }
        return peaks
    }

    private fun pickSignal(
        sceneScore: Float,
        motionScore: Float,
        sensitivity: HighlightSettings.Sensitivity,
    ): HighlightSignal {
        val scenePass = sceneScore >= sensitivity.sceneThreshold
        val motionPass = motionScore >= sensitivity.motionThreshold
        return when {
            scenePass && motionPass -> HighlightSignal.COMBINED
            scenePass -> HighlightSignal.SCENE_CHANGE
            motionPass -> HighlightSignal.MOTION_PEAK
            // Below both thresholds — this peak qualified via combined-only score.
            // Pick the dominant signal.
            sceneScore >= motionScore -> HighlightSignal.SCENE_CHANGE
            else -> HighlightSignal.MOTION_PEAK
        }
    }

    /**
     * For each candidate, sample finer-grained frames inside its window and
     * relocate the peak to where the score actually maxes out at finer resolution.
     * Then re-center the window on the refined peak.
     */
    private suspend fun refinePeaks(
        ctx: Context,
        clip: VideoClip,
        clipDurationMs: Long,
        rawCandidates: List<HighlightCandidate>,
    ): List<HighlightCandidate> {
        if (rawCandidates.isEmpty()) return emptyList()
        val refined = mutableListOf<HighlightCandidate>()
        FrameSampler.open(ctx, clip.uri).use { sampler ->
            for (cand in rawCandidates) {
                coroutineContext.ensureActive()
                // Sample at evenly-spaced times inside the window
                val n = REFINEMENT_SAMPLES_PER_WINDOW
                val sampleTimes = LongArray(n) { i ->
                    cand.startMs + (cand.durationMs * i) / (n - 1).coerceAtLeast(1)
                }

                // Compute scene change (vs window-start frame) and motion (vs prev) at each sample
                val firstBmp = sampler.frameAt(sampleTimes[0], syncOnly = false)
                if (firstBmp == null) {
                    refined.add(cand)  // can't refine — keep raw
                    continue
                }
                val baseHist = FrameSampler.yHistogram(firstBmp)
                var prevBmp = firstBmp

                var bestT = cand.peakMs
                var bestScore = cand.score

                for (i in 1 until n) {
                    coroutineContext.ensureActive()
                    val tMs = sampleTimes[i]
                    val bmp = sampler.frameAt(tMs, syncOnly = false) ?: continue
                    val hist = FrameSampler.yHistogram(bmp)
                    val corr = FrameSampler.histogramCorrelation(baseHist, hist)
                    val sceneSub = (1f - corr).coerceIn(0f, 1f)
                    val motionSub = FrameSampler.meanAbsoluteDifference(prevBmp, bmp) / MOTION_MAD_NORMALIZER
                    val combinedSub = settings.sceneWeight * sceneSub +
                                      settings.motionWeight * motionSub.coerceIn(0f, 1f)
                    if (combinedSub > bestScore) {
                        bestScore = combinedSub
                        bestT = tMs
                    }
                    if (prevBmp !== firstBmp) prevBmp.recycle()
                    prevBmp = bmp
                }
                if (prevBmp !== firstBmp) prevBmp.recycle()
                firstBmp.recycle()

                // Re-center window on refined peak, clamp to clip
                val halfWindow = settings.windowDurationMs / 2
                val newStart = (bestT - halfWindow).coerceAtLeast(0L)
                val newEnd = (bestT + halfWindow).coerceAtMost(clipDurationMs)
                refined.add(cand.copy(
                    peakMs = bestT,
                    startMs = newStart,
                    endMs = newEnd,
                    score = bestScore,
                ))
            }
        }
        return refined
    }

    private fun snapToKeyframeAtOrBefore(keyframes: List<Long>, ms: Long): Long {
        if (keyframes.isEmpty()) return ms
        var best = keyframes[0]
        for (kf in keyframes) {
            if (kf <= ms) best = kf else break
        }
        // If snap distance > 2 seconds, don't snap (avoid weird jumps)
        return if (ms - best > 2_000L) ms else best
    }

    private fun snapToKeyframeAtOrAfter(keyframes: List<Long>, ms: Long, maxMs: Long): Long {
        if (keyframes.isEmpty()) return ms
        for (kf in keyframes) {
            if (kf >= ms) {
                return if (kf - ms > 2_000L) ms else kf
            }
        }
        return ms.coerceAtMost(maxMs)
    }

    /**
     * Global dedup + ranking across all clips:
     *  1. Sort by score, descending
     *  2. Greedy: add highest-scoring candidate if it doesn't overlap with already-selected
     *  3. Continue down the list
     *
     * Note: we keep ALL candidates (not just top-K) so the user can re-tune sensitivity
     * on the review screen without re-running analysis. Top-K selection happens via
     * HighlightAnalysis.topK() at display time.
     */
    private fun dedupAndRank(candidates: List<HighlightCandidate>): List<HighlightCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.score }
        val kept = mutableListOf<HighlightCandidate>()
        for (cand in sorted) {
            val conflicts = kept.any { other ->
                other.sourceClipIdx == cand.sourceClipIdx && overlapsOrAdjacent(cand, other)
            }
            if (!conflicts) kept.add(cand)
        }
        // Final: sort by score desc (the analysis will be displayed top-K, then chronologically)
        return kept.sortedByDescending { it.score }
    }

    private fun overlapsOrAdjacent(a: HighlightCandidate, b: HighlightCandidate): Boolean {
        val aStart = a.startMs - MERGE_GAP_MS
        val aEnd = a.endMs + MERGE_GAP_MS
        return !(aEnd < b.startMs || b.endMs < aStart)
    }
}
