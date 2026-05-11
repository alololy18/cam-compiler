package com.camcompiler.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Fullscreen multi-range trim editor.
 *
 * Layout:
 *   - Top bar: Title + Cancel + Save
 *   - ExoPlayer preview
 *   - Time readouts + playback controls
 *   - Multi-region scrubber (tap a region to select; drag handles to resize)
 *   - "Add range" button + Mode toggle (Keep / Remove)
 *   - Selected range: number entry fields + Delete button + reorder arrows (Keep mode)
 *   - "Snap to keyframes" toggle
 *
 * The dialog operates on a local working copy of the ClipEdit, only committing
 * back via onSave when the user taps Save.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimEditorDialog(
    clip: VideoClip,
    initialEdit: ClipEdit,
    onSave: (ClipEdit) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val clipDurationMs = clip.durationSec * 1000L

    // Working copies of edit state
    var ranges by remember { mutableStateOf(initialEdit.ranges) }
    var trimMode by remember { mutableStateOf(initialEdit.mode) }
    var playOrder by remember { mutableStateOf(initialEdit.playOrder) }
    var selectedRangeIdx by remember { mutableStateOf<Int?>(null) }

    var snapToKeyframes by remember { mutableStateOf(true) }
    var keyframes by remember { mutableStateOf<List<Long>>(emptyList()) }
    var keyframesLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(clip.uri) {
        val kfs = withContext(Dispatchers.IO) { KeyframeIndex.extract(context, clip.uri) }
        keyframes = kfs
        keyframesLoaded = true
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clip.uri))
            prepare()
            playWhenReady = false
        }
    }

    var playerPositionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    // Build a "preview range" — the trimmed playback range. We stop playback at the end of the
    // current effective range when looping through segments. For now we stop at the end of
    // the first/selected range during preview.
    val previewRange: TrimRange? = remember(ranges, trimMode, selectedRangeIdx, playOrder) {
        val effective = ClipEdit(clip.uri, ranges, trimMode, playOrder).effectiveRanges(clipDurationMs)
        // Use the selected range if there is one, otherwise the first effective range
        if (effective.isEmpty()) null
        else if (selectedRangeIdx != null && selectedRangeIdx!! < ranges.size && trimMode == TrimMode.KEEP_RANGES) {
            ranges.getOrNull(selectedRangeIdx!!)
        } else effective.first()
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            playerPositionMs = exoPlayer.currentPosition
            isPlaying = exoPlayer.isPlaying
            val pr = previewRange
            if (pr != null && exoPlayer.isPlaying && exoPlayer.currentPosition >= pr.endMs) {
                exoPlayer.pause()
                exoPlayer.seekTo(pr.endMs)
            }
            delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    fun snapStart(ms: Long): Long =
        if (snapToKeyframes && keyframes.isNotEmpty())
            KeyframeIndex.nearestKeyframeAtOrBefore(keyframes, ms)
        else ms

    fun snapEnd(ms: Long): Long =
        if (snapToKeyframes && keyframes.isNotEmpty())
            KeyframeIndex.nearestKeyframeAtOrAfter(keyframes, ms)
        else ms

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trim Clip", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(
                        onClick = {
                            val newEdit = ClipEdit(
                                sourceUri = clip.uri,
                                ranges = ranges,
                                mode = trimMode,
                                playOrder = playOrder
                            )
                            onSave(newEdit)
                        }
                    ) { Text("Save") }
                }
                Text(clip.name, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1)

                Spacer(Modifier.height(8.dp))

                // Video preview (fixed aspect ratio for predictable layout)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Time + summary
                val effectiveDur = ClipEdit(clip.uri, ranges, trimMode, playOrder)
                    .effectiveDurationMs(clipDurationMs)
                val effectiveRangeCount = ClipEdit(clip.uri, ranges, trimMode, playOrder)
                    .effectiveRanges(clipDurationMs).size

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(playerPositionMs), fontSize = 12.sp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Output: ${formatTime(effectiveDur)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (effectiveRangeCount > 1) {
                            Text(
                                "$effectiveRangeCount segments",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Text(formatTime(clipDurationMs), fontSize = 12.sp)
                }

                Spacer(Modifier.height(8.dp))

                // Playback controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = {
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            val pr = previewRange
                            if (pr != null && (playerPositionMs >= pr.endMs || playerPositionMs < pr.startMs)) {
                                exoPlayer.seekTo(pr.startMs)
                            }
                            exoPlayer.play()
                        }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Multi-region scrubber
                MultiRegionScrubber(
                    durationMs = clipDurationMs,
                    ranges = ranges,
                    trimMode = trimMode,
                    selectedIdx = selectedRangeIdx,
                    playheadMs = playerPositionMs,
                    keyframes = keyframes,
                    onRangeSelect = { idx -> selectedRangeIdx = idx },
                    onRangeUpdate = { idx, newRange ->
                        val newRanges = ranges.toMutableList()
                        newRanges[idx] = newRange
                        ranges = newRanges
                    },
                    onPlayheadSeek = { ms ->
                        exoPlayer.seekTo(ms.coerceIn(0L, clipDurationMs))
                    },
                    snapStart = ::snapStart,
                    snapEnd = ::snapEnd
                )

                Spacer(Modifier.height(8.dp))

                // Mode toggle + Add range
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = trimMode == TrimMode.KEEP_RANGES,
                        onClick = { trimMode = TrimMode.KEEP_RANGES },
                        label = { Text("Keep", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = trimMode == TrimMode.REMOVE_RANGES,
                        onClick = { trimMode = TrimMode.REMOVE_RANGES },
                        label = { Text("Remove", fontSize = 12.sp) }
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            // Add a range at playhead, default 5 seconds long (or to clip end)
                            val start = snapStart(playerPositionMs)
                            val end = snapEnd((playerPositionMs + 5000).coerceAtMost(clipDurationMs))
                            if (end > start) {
                                ranges = ranges + TrimRange(start, end)
                                selectedRangeIdx = ranges.size - 1
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add range", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Mode help
                Text(
                    when (trimMode) {
                        TrimMode.KEEP_RANGES -> "Keep mode: marked segments are kept, the rest is discarded"
                        TrimMode.REMOVE_RANGES -> "Remove mode: marked segments are CUT OUT, the rest is kept"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )

                Spacer(Modifier.height(8.dp))

                // Range list (selectable, with delete + reorder for selected)
                if (ranges.isNotEmpty()) {
                    RangeList(
                        ranges = ranges,
                        trimMode = trimMode,
                        playOrder = playOrder,
                        selectedIdx = selectedRangeIdx,
                        clipDurationMs = clipDurationMs,
                        onSelect = { selectedRangeIdx = it },
                        onDelete = { idx ->
                            val newRanges = ranges.toMutableList().also { it.removeAt(idx) }
                            ranges = newRanges
                            // Clean playOrder
                            playOrder = playOrder
                                .filter { it != idx }
                                .map { if (it > idx) it - 1 else it }
                            if (selectedRangeIdx == idx) selectedRangeIdx = null
                            else if (selectedRangeIdx != null && selectedRangeIdx!! > idx) {
                                selectedRangeIdx = selectedRangeIdx!! - 1
                            }
                        },
                        onMoveUp = { idx ->
                            // Move idx earlier in playOrder
                            val currentOrder = if (playOrder.isEmpty()) ranges.indices.toList() else playOrder
                            val pos = currentOrder.indexOf(idx)
                            if (pos > 0) {
                                val newOrder = currentOrder.toMutableList()
                                newOrder[pos] = newOrder[pos - 1].also { newOrder[pos - 1] = newOrder[pos] }
                                playOrder = newOrder
                            }
                        },
                        onMoveDown = { idx ->
                            val currentOrder = if (playOrder.isEmpty()) ranges.indices.toList() else playOrder
                            val pos = currentOrder.indexOf(idx)
                            if (pos in 0 until currentOrder.size - 1) {
                                val newOrder = currentOrder.toMutableList()
                                newOrder[pos] = newOrder[pos + 1].also { newOrder[pos + 1] = newOrder[pos] }
                                playOrder = newOrder
                            }
                        }
                    )
                }

                // Number entry for selected range
                val selIdx = selectedRangeIdx
                if (selIdx != null && selIdx < ranges.size) {
                    val selectedRange = ranges[selIdx]
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TimeEntryField(
                            label = "Start",
                            value = selectedRange.startMs,
                            onValueChange = { newValue ->
                                val snapped = snapStart(newValue)
                                val end = selectedRange.endMs
                                if (snapped < end) {
                                    val newRanges = ranges.toMutableList()
                                    newRanges[selIdx] = TrimRange(snapped.coerceAtLeast(0L), end)
                                    ranges = newRanges
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TimeEntryField(
                            label = "End",
                            value = selectedRange.endMs,
                            onValueChange = { newValue ->
                                val snapped = snapEnd(newValue)
                                val start = selectedRange.startMs
                                if (snapped > start) {
                                    val newRanges = ranges.toMutableList()
                                    newRanges[selIdx] = TrimRange(start, snapped.coerceAtMost(clipDurationMs))
                                    ranges = newRanges
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Snap toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = snapToKeyframes,
                        onCheckedChange = { snapToKeyframes = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Snap to keyframes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (snapToKeyframes)
                                "Fast merge (lossless) — exact cut may shift by ±1s"
                            else
                                "Precise trim — will require re-encoding (slow)",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                if (!keyframesLoaded) {
                    Text("Loading keyframes...", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                } else {
                    Text("${keyframes.size} keyframes found",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * Multi-region scrubber.
 *
 * Visual: a horizontal track. Each range is painted as a colored block.
 * Tapping a block selects it. Selected block shows handles for resize.
 * Drag anywhere on the track outside ranges to scrub the playhead.
 */
@Composable
private fun MultiRegionScrubber(
    durationMs: Long,
    ranges: List<TrimRange>,
    trimMode: TrimMode,
    selectedIdx: Int?,
    playheadMs: Long,
    keyframes: List<Long>,
    onRangeSelect: (Int?) -> Unit,
    onRangeUpdate: (Int, TrimRange) -> Unit,
    onPlayheadSeek: (Long) -> Unit,
    snapStart: (Long) -> Long,
    snapEnd: (Long) -> Long
) {
    val density = LocalDensity.current
    val rangeColor = when (trimMode) {
        TrimMode.KEEP_RANGES -> MaterialTheme.colorScheme.primary
        TrimMode.REMOVE_RANGES -> MaterialTheme.colorScheme.error
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.toPx() }
            fun msToX(ms: Long): Float =
                if (durationMs > 0) (ms.toFloat() / durationMs) * widthPx else 0f
            fun xToMs(x: Float): Long =
                if (widthPx > 0) ((x / widthPx) * durationMs).toLong() else 0L

            // Background track + scrub gestures
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(durationMs) {
                        detectDragGestures { change, _ ->
                            onPlayheadSeek(xToMs(change.position.x))
                        }
                    }
            ) {
                // Keyframe ticks
                keyframes.forEach { kfMs ->
                    val xDp = with(density) { msToX(kfMs).toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    )
                }
            }

            // Range blocks
            ranges.forEachIndexed { idx, range ->
                val startXDp = with(density) { msToX(range.startMs).toDp() }
                val endXDp = with(density) { msToX(range.endMs).toDp() }
                val widthDp = (endXDp - startXDp).coerceAtLeast(2.dp)
                val isSelected = idx == selectedIdx
                Box(
                    modifier = Modifier
                        .offset(x = startXDp, y = 10.dp)
                        .width(widthDp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(rangeColor.copy(alpha = if (isSelected) 0.6f else 0.35f))
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) rangeColor else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { onRangeSelect(idx) }
                )
            }

            // Handles for selected range
            val sel = selectedIdx
            if (sel != null && sel < ranges.size) {
                val r = ranges[sel]
                val startXDp = with(density) { msToX(r.startMs).toDp() }
                val endXDp = with(density) { msToX(r.endMs).toDp() }

                // Start handle — smooth drag, snap on release
                var startDragMs by remember(sel, ranges) { mutableStateOf(r.startMs) }
                Box(
                    modifier = Modifier
                        .offset(x = startXDp - 24.dp, y = 4.dp)
                        .width(48.dp)
                        .height(48.dp)
                        .pointerInput(sel, ranges) {
                            detectDragGestures(
                                onDragStart = { startDragMs = r.startMs },
                                onDragEnd = {
                                    val snapped = snapStart(startDragMs)
                                    onRangeUpdate(sel, TrimRange(
                                        snapped.coerceIn(0L, r.endMs - 100L),
                                        r.endMs
                                    ))
                                },
                                onDragCancel = { },
                                onDrag = { _, dragAmount ->
                                    val deltaMs = xToMs(dragAmount.x)
                                    val newStart = (startDragMs + deltaMs).coerceIn(0L, r.endMs - 100L)
                                    startDragMs = newStart
                                    onRangeUpdate(sel, TrimRange(newStart, r.endMs))
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(10.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(rangeColor)
                    )
                }

                // End handle — smooth drag, snap on release
                var endDragMs by remember(sel, ranges) { mutableStateOf(r.endMs) }
                Box(
                    modifier = Modifier
                        .offset(x = endXDp - 24.dp, y = 4.dp)
                        .width(48.dp)
                        .height(48.dp)
                        .pointerInput(sel, ranges) {
                            detectDragGestures(
                                onDragStart = { endDragMs = r.endMs },
                                onDragEnd = {
                                    val snapped = snapEnd(endDragMs)
                                    onRangeUpdate(sel, TrimRange(
                                        r.startMs,
                                        snapped.coerceIn(r.startMs + 100L, durationMs)
                                    ))
                                },
                                onDragCancel = { },
                                onDrag = { _, dragAmount ->
                                    val deltaMs = xToMs(dragAmount.x)
                                    val newEnd = (endDragMs + deltaMs).coerceIn(r.startMs + 100L, durationMs)
                                    endDragMs = newEnd
                                    onRangeUpdate(sel, TrimRange(r.startMs, newEnd))
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(10.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(rangeColor)
                    )
                }
            }

            // Playhead
            val playheadDp = with(density) { msToX(playheadMs).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = playheadDp - 1.dp, y = 6.dp)
                    .width(2.dp)
                    .height(44.dp)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun RangeList(
    ranges: List<TrimRange>,
    trimMode: TrimMode,
    playOrder: List<Int>,
    selectedIdx: Int?,
    clipDurationMs: Long,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    // Build the display order: if KEEP + playOrder, use that; otherwise source order
    val displayOrder = if (trimMode == TrimMode.KEEP_RANGES && playOrder.isNotEmpty())
        playOrder.filter { it < ranges.size }
    else ranges.indices.toList()

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(displayOrder) { displayPos, rangeIdx ->
            val range = ranges[rangeIdx]
            val isSelected = rangeIdx == selectedIdx
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(rangeIdx) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "#${displayPos + 1}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${formatTime(range.startMs)} → ${formatTime(range.endMs)}",
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        "${"%.1f".format(range.durationMs / 1000.0)}s",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSelected) {
                    Spacer(Modifier.width(4.dp))
                    Column {
                        // Reorder arrows only meaningful in KEEP mode
                        if (trimMode == TrimMode.KEEP_RANGES && ranges.size > 1) {
                            IconButton(
                                onClick = { onMoveUp(rangeIdx) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Filled.ArrowUpward, "Move earlier",
                                    modifier = Modifier.size(14.dp))
                            }
                            IconButton(
                                onClick = { onMoveDown(rangeIdx) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Filled.ArrowDownward, "Move later",
                                    modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    IconButton(
                        onClick = { onDelete(rangeIdx) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Close, "Delete range",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEntryField(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(formatTime(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            parseTime(newText)?.let { onValueChange(it) }
        },
        label = { Text(label, fontSize = 11.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        singleLine = true,
        modifier = modifier
    )
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds - (minutes * 60)
    return "%02d:%04.1f".format(minutes, seconds)
}

fun parseTime(text: String): Long? {
    return try {
        val parts = text.trim().split(":")
        when (parts.size) {
            1 -> (parts[0].toDouble() * 1000).toLong()
            2 -> ((parts[0].toLong() * 60 + parts[1].toDouble()) * 1000).toLong()
            else -> null
        }
    } catch (_: Exception) { null }
}
