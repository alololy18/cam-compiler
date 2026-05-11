package com.camcompiler.app

/**
 * Transition types between adjacent segments (clips in a merge OR ranges in a clip's trim).
 *
 * All non-NONE transitions force re-encoding because they require pixel-level work
 * (fade-to-black requires modulating brightness over time; hold-black requires
 * inserting black frames).
 */
enum class Transition(val displayName: String, val shortLabel: String) {
    NONE("No transition", "None"),
    FADE_BLACK("Fade to black", "Fade"),
    HOLD_BLACK("Hold on black", "Hold");

    /** Cycle to the next transition type. */
    fun next(): Transition = entries[(ordinal + 1) % entries.size]

    /** True if this transition requires re-encoding (anything that's not NONE). */
    fun requiresReencode(): Boolean = this != NONE

    companion object {
        /** Default transition duration in milliseconds. */
        const val DURATION_MS = 500L

        /** Duration of the held black insert for HOLD_BLACK. */
        const val HOLD_DURATION_MS = 300L
    }
}
