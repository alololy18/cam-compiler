package com.camcompiler.app

import java.util.regex.Pattern

/**
 * Extracts a sortable timestamp from a video filename when possible.
 * Handles common camera filename patterns:
 *   - 2025_05_09_14_32_15.mp4    (Qubo, dashcams)
 *   - 20250509_143215.mp4         (many cameras)
 *   - VID_20250509_143215.mp4     (Android camera style)
 *   - IMG_20250509_143215.mp4
 *   - 2025-05-09 14:32:15.mp4
 *   - 250509_143215.mp4           (yy short form)
 *   - MOV0001.MP4 / DSC0042.MOV   (sequential - parsed as ordering hint)
 *   - GH010001.MP4                (GoPro)
 *   - 00001.MP4
 *
 * Returns:
 *   - TimestampResult.Absolute(epochMs) if a real date+time was extracted
 *   - TimestampResult.Sequential(num) if only a sequence number was found
 *   - TimestampResult.Unknown if nothing usable was extracted
 */
object FilenameTimestampParser {

    sealed class TimestampResult {
        data class Absolute(val epochMs: Long) : TimestampResult()
        data class Sequential(val sequence: Long) : TimestampResult()
        object Unknown : TimestampResult()
    }

    // Pattern: yyyy[sep]MM[sep]dd[sep]HH[sep]mm[sep]ss  where sep is _ - or nothing
    private val ABSOLUTE_PATTERNS = listOf(
        // 2025_05_09_14_32_15
        Pattern.compile("(\\d{4})[-_](\\d{2})[-_](\\d{2})[-_ ](\\d{2})[-_:](\\d{2})[-_:](\\d{2})"),
        // 20250509_143215  or 20250509-143215  or 20250509143215
        Pattern.compile("(\\d{4})(\\d{2})(\\d{2})[-_]?(\\d{2})(\\d{2})(\\d{2})"),
        // 250509_143215  (2-digit year)
        Pattern.compile("(?<![0-9])(\\d{2})(\\d{2})(\\d{2})[-_](\\d{2})(\\d{2})(\\d{2})(?![0-9])")
    )

    // Pattern for sequential numbering: extract the largest run of digits
    private val SEQUENTIAL_PATTERN = Pattern.compile("(\\d{3,})")

    fun parse(filename: String): TimestampResult {
        val nameWithoutExt = filename.substringBeforeLast('.')

        // Try absolute date+time patterns first
        for ((idx, pattern) in ABSOLUTE_PATTERNS.withIndex()) {
            val matcher = pattern.matcher(nameWithoutExt)
            if (matcher.find()) {
                try {
                    val year = if (idx == 2) 2000 + matcher.group(1)!!.toInt() else matcher.group(1)!!.toInt()
                    val month = matcher.group(2)!!.toInt()
                    val day = matcher.group(3)!!.toInt()
                    val hour = matcher.group(4)!!.toInt()
                    val min = matcher.group(5)!!.toInt()
                    val sec = matcher.group(6)!!.toInt()

                    if (isValidDate(year, month, day, hour, min, sec)) {
                        val cal = java.util.Calendar.getInstance()
                        cal.clear()
                        cal.set(year, month - 1, day, hour, min, sec)
                        return TimestampResult.Absolute(cal.timeInMillis)
                    }
                } catch (_: Exception) { /* try next pattern */ }
            }
        }

        // Fallback: extract sequence number from the longest run of digits
        val matcher = SEQUENTIAL_PATTERN.matcher(nameWithoutExt)
        var longest: String? = null
        while (matcher.find()) {
            val match = matcher.group(1) ?: continue
            if (longest == null || match.length > longest.length) longest = match
        }
        if (longest != null) {
            return TimestampResult.Sequential(longest.toLong())
        }

        return TimestampResult.Unknown
    }

    private fun isValidDate(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Boolean {
        if (y < 2000 || y > 2100) return false
        if (mo < 1 || mo > 12) return false
        if (d < 1 || d > 31) return false
        if (h < 0 || h > 23) return false
        if (mi < 0 || mi > 59) return false
        if (s < 0 || s > 59) return false
        return true
    }
}
