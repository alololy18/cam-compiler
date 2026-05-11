package com.camcompiler.app

import android.net.Uri

/**
 * Project-level edit state, including a list of clip edits and music settings.
 *
 * Music addition forces re-encoding (the audio streams need to be mixed).
 * If musicUri is null AND every clip's edits are keyframe-aligned, we can
 * still take the fast stream-copy path.
 */
data class EditProject(
    val clipEdits: List<ClipEdit>,
    val musicUri: Uri? = null,
    val musicVolume: Float = 0.5f,           // 0..1; default music ducked to half
    val originalAudioVolume: Float = 1.0f    // 0..1; default original audio at full
) {
    /** True if music is in the project — re-encoding will be required. */
    fun hasMusic(): Boolean = musicUri != null

    /** True if any clip has non-default edits applied. */
    fun hasClipEdits(): Boolean = clipEdits.any { it.hasEdits() }
}
