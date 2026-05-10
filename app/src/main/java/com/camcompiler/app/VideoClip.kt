package com.camcompiler.app

import android.net.Uri

data class VideoClip(
    val uri: Uri,
    val name: String,
    val sizeMb: Double,
    val durationSec: Long,
    val lastModified: Long
)

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
