package com.camcompiler.app

import kotlin.math.abs

/**
 * Determines the best chronological order for a list of clips by combining:
 *   1. Timestamp embedded in filename (if parseable)
 *   2. File modification time
 *   3. Sequence number in filename (if filename has no full timestamp)
 *
 * Returns a SortResult that includes the ordered list AND a confidence note
 * so we can warn the user if signals disagree.
 */
object ChronologicalSorter {

    data class SortResult(
        val ordered: List<VideoClip>,
        val strategy: String,           // human-readable description
        val warning: String? = null     // null if all signals agree
    )

    fun sort(clips: List<VideoClip>): SortResult {
        if (clips.isEmpty()) {
            return SortResult(emptyList(), "Empty list")
        }

        // Parse filename timestamps for each clip
        val parsed = clips.map { clip ->
            val result = FilenameTimestampParser.parse(clip.name)
            Triple(clip, result, clip.lastModified)
        }

        val absoluteCount = parsed.count { it.second is FilenameTimestampParser.TimestampResult.Absolute }
        val sequentialCount = parsed.count { it.second is FilenameTimestampParser.TimestampResult.Sequential }

        // Strategy 1: Most clips have absolute timestamps in their filenames
        if (absoluteCount >= clips.size * 0.8) {
            val sortedByName = parsed.sortedBy {
                when (val r = it.second) {
                    is FilenameTimestampParser.TimestampResult.Absolute -> r.epochMs
                    else -> it.first.lastModified // fall back for the few without
                }
            }.map { it.first }

            // Cross-validate with file modification time
            val sortedByMtime = clips.sortedBy { it.lastModified }
            val agreement = orderAgreement(sortedByName, sortedByMtime)
            val warning = if (agreement < 0.9) {
                "Note: filename dates and file modification times disagree on ordering. Used filename dates. ($absoluteCount of ${clips.size} clips had recognizable date in filename.)"
            } else null

            return SortResult(
                ordered = sortedByName,
                strategy = "Sorted by date in filename ($absoluteCount/${clips.size} clips)",
                warning = warning
            )
        }

        // Strategy 2: Sequential numbering in filenames is dominant
        if (sequentialCount >= clips.size * 0.8) {
            val sortedBySeq = parsed.sortedBy {
                when (val r = it.second) {
                    is FilenameTimestampParser.TimestampResult.Sequential -> r.sequence
                    else -> Long.MAX_VALUE
                }
            }.map { it.first }

            val sortedByMtime = clips.sortedBy { it.lastModified }
            val agreement = orderAgreement(sortedBySeq, sortedByMtime)
            val warning = if (agreement < 0.9) {
                "Note: filename sequence numbers and file modification times disagree on ordering. Used filename sequence."
            } else null

            return SortResult(
                ordered = sortedBySeq,
                strategy = "Sorted by sequence number in filename",
                warning = warning
            )
        }

        // Strategy 3: Fallback to file modification time (camera write time)
        val sortedByMtime = clips.sortedBy { it.lastModified }
        return SortResult(
            ordered = sortedByMtime,
            strategy = "Sorted by file modification time (filenames had no clear pattern)",
            warning = "Could not extract dates or sequence numbers from filenames. Used file modification times — may be unreliable if files were copied or modified after recording."
        )
    }

    /**
     * Returns 0.0 to 1.0 measuring how similar two orderings are.
     * Compares position of each clip; identical = 1.0, fully reversed = 0.0.
     */
    private fun orderAgreement(a: List<VideoClip>, b: List<VideoClip>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val posInB = b.withIndex().associate { (idx, clip) -> clip.uri to idx }
        var totalDiff = 0
        a.forEachIndexed { idxA, clip ->
            val idxB = posInB[clip.uri] ?: return@forEachIndexed
            totalDiff += abs(idxA - idxB)
        }
        val maxPossibleDiff = a.size * a.size / 2.0
        return 1.0 - (totalDiff / maxPossibleDiff).coerceAtMost(1.0)
    }
}
