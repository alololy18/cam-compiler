package com.camcompiler.app

import android.net.Uri

/**
 * Project-level edit state for a merge.
 *
 * Music or any non-NONE transition forces re-encoding. Bulk Merge produces
 * an EditProject with no music and no transitions to take the fast path.
 */
data class EditProject(
    val clipEdits: List<ClipEdit>,
    val musicUri: Uri? = null,
    val musicVolume: Float = 0.5f,
    val originalAudioVolume: Float = 1.0f,
    /**
     * Transitions BETWEEN adjacent clips in the merge. Position N is the
     * transition from clip N to clip N+1. Should have clipEdits.size - 1 elements;
     * if shorter, missing positions default to Transition.NONE.
     */
    val clipTransitions: List<Transition> = emptyList(),
    /**
     * If true, the engine is forced to use COMPATIBLE (re-encode) mode even when
     * no transition or music is set. Used by the new Merge tile which always
     * re-encodes regardless of content (per user choice — picks "creative" lane).
     */
    val forceReencode: Boolean = false
) {
    fun hasMusic(): Boolean = musicUri != null

    fun hasClipEdits(): Boolean = clipEdits.any { it.hasEdits() }

    /** True if any clip transition is non-NONE. */
    fun hasClipTransitions(): Boolean = clipTransitions.any { it != Transition.NONE }

    /** True if any clip has internal range transitions set. */
    fun hasAnyRangeTransitions(): Boolean = clipEdits.any { it.hasTransitions() }

    /** Overall: requires re-encoding for any reason. */
    fun requiresReencode(): Boolean =
        forceReencode || hasMusic() || hasClipTransitions() || hasAnyRangeTransitions()

    /** Transition at position N (between clip N and N+1), or NONE if out of bounds. */
    fun transitionAt(idx: Int): Transition =
        clipTransitions.getOrElse(idx) { Transition.NONE }
}
