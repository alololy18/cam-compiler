package com.camcompiler.app

import android.net.Uri

/**
 * A single time range within a clip (start and end in ms).
 * Ranges are open-ended: [startMs, endMs).
 */
data class TrimRange(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** True if this range overlaps another. Used to validate user input. */
    fun overlaps(other: TrimRange): Boolean =
        startMs < other.endMs && other.startMs < endMs
}

/**
 * Per-clip edit state. A clip can have multiple kept-segments or multiple
 * removed-segments depending on TrimMode.
 *
 * KEEP_RANGES: the segments to KEEP. Empty list = keep the whole clip.
 * REMOVE_RANGES: the segments to CUT OUT. Empty list = keep the whole clip.
 * Either way, the merge engine resolves to a list of "effective output ranges"
 * via effectiveRanges(clipDurationMs).
 */
data class ClipEdit(
    val sourceUri: Uri,
    val ranges: List<TrimRange> = emptyList(),
    val mode: TrimMode = TrimMode.KEEP_RANGES,
    /**
     * For KEEP_RANGES: indices into `ranges` defining playback order.
     * If empty, ranges play in source (time) order.
     * For REMOVE_RANGES: order is always source order; this field is unused.
     */
    val playOrder: List<Int> = emptyList(),
    /**
     * Transitions BETWEEN adjacent effective ranges. Position N is the transition
     * from segment N to segment N+1. Should have effectiveRanges.size - 1 elements;
     * if shorter, missing positions default to Transition.NONE.
     */
    val rangeTransitions: List<Transition> = emptyList()
) {
    /** True if any non-default edit applies to this clip. */
    fun hasEdits(): Boolean = ranges.isNotEmpty()

    /** True if any transition between ranges is non-NONE. */
    fun hasTransitions(): Boolean = rangeTransitions.any { it != Transition.NONE }

    /**
     * Returns the transition at position N, or NONE if out of bounds.
     */
    fun transitionAt(idx: Int): Transition =
        rangeTransitions.getOrElse(idx) { Transition.NONE }

    /**
     * Resolves to a list of TrimRanges that the merge engine should output,
     * in playback order. For KEEP_RANGES, returns the ranges (optionally
     * reordered). For REMOVE_RANGES, returns the COMPLEMENT — i.e. the
     * gaps between/around the removed ranges.
     */
    fun effectiveRanges(clipDurationMs: Long): List<TrimRange> {
        if (ranges.isEmpty()) {
            // No edits → return single full-clip range
            return listOf(TrimRange(0L, clipDurationMs))
        }

        // Normalize: ensure each range is in-bounds, start < end, sorted by startMs
        val cleaned = ranges
            .map { TrimRange(
                it.startMs.coerceIn(0L, clipDurationMs),
                it.endMs.coerceIn(0L, clipDurationMs)
            )}
            .filter { it.durationMs > 0 }
            .sortedBy { it.startMs }

        if (cleaned.isEmpty()) return listOf(TrimRange(0L, clipDurationMs))

        return when (mode) {
            TrimMode.KEEP_RANGES -> {
                // Merge any overlapping/adjacent ranges first (defensive)
                val merged = mergeOverlapping(cleaned)
                // Apply play order if specified
                if (playOrder.isEmpty()) {
                    merged
                } else {
                    // Reorder by playOrder, ignoring out-of-bounds indices
                    val byIdx = merged.withIndex().associate { it.index to it.value }
                    val reordered = playOrder.mapNotNull { byIdx[it] }
                    if (reordered.isEmpty()) merged else reordered
                }
            }
            TrimMode.REMOVE_RANGES -> {
                // Compute complement: the gaps between/around the removed ranges
                val merged = mergeOverlapping(cleaned)
                buildComplement(merged, clipDurationMs)
            }
        }
    }

    /** Sum of effective range durations — i.e. the trimmed output length. */
    fun effectiveDurationMs(clipDurationMs: Long): Long =
        effectiveRanges(clipDurationMs).sumOf { it.durationMs }

    /** Adds a new range, returns a new ClipEdit. */
    fun withAddedRange(range: TrimRange): ClipEdit =
        copy(ranges = ranges + range)

    /** Removes a range by index, returns a new ClipEdit. */
    fun withRemovedRange(idx: Int): ClipEdit {
        if (idx < 0 || idx >= ranges.size) return this
        val newRanges = ranges.toMutableList().also { it.removeAt(idx) }
        // Also clean up playOrder — drop the removed index and shift greater indices
        val newPlayOrder = playOrder
            .filter { it != idx }
            .map { if (it > idx) it - 1 else it }
        return copy(ranges = newRanges, playOrder = newPlayOrder)
    }

    /** Updates a range by index. */
    fun withUpdatedRange(idx: Int, newRange: TrimRange): ClipEdit {
        if (idx < 0 || idx >= ranges.size) return this
        val newRanges = ranges.toMutableList().also { it[idx] = newRange }
        return copy(ranges = newRanges)
    }

    companion object {
        private fun mergeOverlapping(sorted: List<TrimRange>): List<TrimRange> {
            if (sorted.size <= 1) return sorted
            val result = mutableListOf<TrimRange>()
            var current = sorted[0]
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.startMs <= current.endMs) {
                    // Overlapping or adjacent — merge
                    current = TrimRange(current.startMs, maxOf(current.endMs, next.endMs))
                } else {
                    result.add(current)
                    current = next
                }
            }
            result.add(current)
            return result
        }

        private fun buildComplement(removed: List<TrimRange>, totalMs: Long): List<TrimRange> {
            // Walk through [0..totalMs), emitting gaps between removed ranges
            val result = mutableListOf<TrimRange>()
            var cursor = 0L
            for (r in removed) {
                if (r.startMs > cursor) result.add(TrimRange(cursor, r.startMs))
                cursor = maxOf(cursor, r.endMs)
            }
            if (cursor < totalMs) result.add(TrimRange(cursor, totalMs))
            return result
        }
    }
}

enum class TrimMode {
    /** Ranges are SEGMENTS TO KEEP. Default and intuitive. */
    KEEP_RANGES,
    /** Ranges are SEGMENTS TO REMOVE. The kept output is the complement. */
    REMOVE_RANGES
}
