package com.camcompiler.app

/**
 * Transition types between adjacent segments (clips in a merge OR ranges in a clip's trim).
 *
 * All non-NONE transitions force re-encoding because they require pixel-level work.
 *
 * Implementation note (Media3 1.4.1):
 *  - Each non-NONE transition is rendered as an inserted image segment (black or white)
 *    with the specified duration.
 *  - True alpha-fade / cross-dissolve transitions are not feasible in 1.4.1 without
 *    custom shaders — those are planned for v9 with Media3 1.5+.
 */
enum class Transition(
    val displayName: String,
    val shortLabel: String,
    val durationMs: Long,
    val isWhite: Boolean = false
) {
    NONE("No transition", "None", 0L),
    CUT_BLACK("Cut to black (short)", "Cut", 200L),
    HOLD_BLACK("Hold on black", "Hold", 500L),
    FADE_BLACK("Fade through black (slow)", "Fade", 1500L),
    FLASH_WHITE("White flash", "Flash", 400L, isWhite = true);

    /** Cycle to the next transition type. */
    fun next(): Transition = entries[(ordinal + 1) % entries.size]

    /** True if this transition requires re-encoding (anything that's not NONE). */
    fun requiresReencode(): Boolean = this != NONE

    companion object {
        // Kept for compatibility; new code should use Transition.durationMs directly.
        const val DURATION_MS = 500L
        const val HOLD_DURATION_MS = 300L
    }
}
