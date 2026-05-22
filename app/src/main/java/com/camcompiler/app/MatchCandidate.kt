package com.camcompiler.app

import android.net.Uri

/**
 * A detected segment matching a natural-language query.
 *
 * For Mode A (FIND_ONE_MOMENT): each candidate is an independent option for the user.
 * For Mode B (BUILD_REEL): candidates are combined into a multi-scene reel.
 */
data class MatchCandidate(
    val sourceUri: Uri,
    val sourceClipName: String,
    val startMs: Long,
    val endMs: Long,
    /** Where the best match was within the candidate window. */
    val peakMs: Long,
    /** Combined CLIP + motion score at the peak (0..1+, higher is better). */
    val score: Float,
    /** Average score over the whole window (0..1+). */
    val avgScore: Float,
    /** Which signal contributed most to the score: useful for debugging. */
    val dominantSignal: Signal = Signal.CLIP,
) {
    val durationMs: Long get() = endMs - startMs

    enum class Signal { CLIP, MOTION, COMBINED }
}

/**
 * Settings for a find-by-description run.
 */
data class FindMomentSettings(
    val query: String,
    val mode: SearchMode,
    val targetLengthMs: Long = 45_000L,
    val defaultTransition: Transition = Transition.FADE_BLACK,
) {
    enum class SearchMode {
        /** Find ONE continuous segment matching the query — Mode A. */
        FIND_ONE_MOMENT,
        /** Compile MULTIPLE short scenes into ONE reel — Mode B. */
        BUILD_REEL,
    }
}

/**
 * Result of running find-by-description on a single clip.
 *
 * Mode A returns 3-5 candidates as options (user picks one).
 * Mode B returns N candidates already arranged into a reel (user can edit/delete).
 */
data class FindMomentAnalysis(
    val candidates: List<MatchCandidate>,
    val sourceClip: VideoClip,
    val settings: FindMomentSettings,
    /** All query phrasings tried, for transparency. */
    val expandedQueries: List<String>,
)

/**
 * Phase indicator for the analyzing screen.
 */
enum class FindMomentPhase(val displayName: String) {
    EMBEDDING_QUERIES("Understanding your description"),
    SAMPLING("Sampling video frames"),
    SCORING("Matching frames to description"),
    COMPOSING("Picking the best moments"),
    DONE("Done"),
    CANCELLED("Cancelled"),
    FAILED("Failed"),
}

/**
 * Progress emitted while detection runs. Used to drive the progress bar + ETA.
 */
data class FindMomentProgress(
    val phase: FindMomentPhase,
    val percent: Float,
    val framesProcessed: Int = 0,
    val framesTotal: Int = 0,
    val estimatedRemainingMs: Long = 0L,
    val errorMessage: String? = null,
)
