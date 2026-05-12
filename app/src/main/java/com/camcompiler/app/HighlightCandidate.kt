package com.camcompiler.app

import android.net.Uri

/**
 * A single detected highlight moment.
 *
 * Phase 1/2: produced by HighlightDetector based on scene-change and motion-peak signals.
 * Users can review, delete, or accept highlights before export.
 */
data class HighlightCandidate(
    val sourceClipIdx: Int,           // index into the input clip list
    val sourceUri: Uri,               // direct URI for convenience
    val sourceClipName: String,       // for display
    val startMs: Long,                // window start (snapped to keyframe when possible)
    val endMs: Long,                  // window end
    val peakMs: Long,                 // where the score peaked (for display, may differ from window center)
    val sceneScore: Float,            // 0.0 to 1.0 — scene-change contribution
    val motionScore: Float,           // 0.0 to 1.0 — motion-peak contribution
    val score: Float,                 // 0.0 to 1.0 — combined final score
    val signal: HighlightSignal,      // the dominant signal that triggered this candidate
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Which signal triggered the highlight. Used for UI labels.
 */
enum class HighlightSignal(val displayName: String, val shortLabel: String) {
    SCENE_CHANGE("Scene change",  "▸ scene"),
    MOTION_PEAK("Motion peak",    "▲ motion"),
    COMBINED("Combined signal",   "★ combined"),
    AUDIO_PEAK("Audio peak",      "♫ audio")   // Phase 3+ — reserved
}

/**
 * User-tunable settings for a detection run.
 */
data class HighlightSettings(
    val targetCount: Int = 10,
    val sensitivity: Sensitivity = Sensitivity.BALANCED,
    val defaultTransition: Transition = Transition.FADE_BLACK,
    val windowDurationMs: Long = 15_000L,        // 15s per highlight
    val sceneWeight: Float = 0.6f,               // weight of scene score in combined
    val motionWeight: Float = 0.4f,              // weight of motion score in combined
) {
    enum class Sensitivity(
        val displayName: String,
        val sceneThreshold: Float,
        val motionThreshold: Float,
    ) {
        STRICT("Strict",   0.50f, 0.50f),
        BALANCED("Balanced", 0.30f, 0.30f),
        LOOSE("Loose",     0.15f, 0.15f);

        fun next(): Sensitivity = entries[(ordinal + 1) % entries.size]
    }
}

/**
 * Phase indicator for progress reporting.
 */
enum class DetectionPhase(val displayName: String) {
    INDEXING("Indexing keyframes"),
    SCORING("Analyzing scenes and motion"),
    REFINING("Refining around peaks"),
    FINALIZING("Selecting top highlights"),
    DONE("Done"),
    CANCELLED("Cancelled"),
    FAILED("Failed")
}

/**
 * Live progress emitted by the detector.
 */
data class DetectionProgress(
    val phase: DetectionPhase,
    val percent: Float,                 // 0.0 to 1.0 across the whole run
    val currentClipIdx: Int = 0,
    val totalClips: Int = 1,
    val sceneCandidatesFound: Int = 0,
    val motionCandidatesFound: Int = 0,
    val estimatedRemainingMs: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * Result of running the detector on one or more clips. Contains ALL candidates
 * (before top-K selection) so the user can re-tune sensitivity without re-running.
 */
data class HighlightAnalysis(
    val candidates: List<HighlightCandidate>,
    val sourceClips: List<VideoClip>,
    val totalSourceDurationMs: Long,
    val settings: HighlightSettings,
) {
    /** Returns the top N candidates by score, sorted chronologically across source clips. */
    fun topK(n: Int): List<HighlightCandidate> {
        val top = candidates.sortedByDescending { it.score }.take(n)
        // Sort chronologically: first by source clip index, then by start time within clip
        return top.sortedWith(compareBy({ it.sourceClipIdx }, { it.startMs }))
    }
}
