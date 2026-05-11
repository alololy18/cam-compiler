package com.camcompiler.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
 *  - Fixed header (title + Cancel + Save) at top
 *  - Fixed video preview below header
 *  - SCROLLABLE body with: time readouts, playback, scrubber, range list, mode toggle,
 *    time entry, snap toggle, keyframe info, instructions
 *
 * Multi-range interaction model:
 *  - Each range has its own handles visible at all times (no select-first dance)
 *  - Drag a handle to resize that specific range
 *  - Tap on a range body to select it (highlight + show delete button)
 *  - Tap on empty track area to move the playhead
 *  - Drag on empty track to scrub playhead
 *  - Floating "+" button adds a new 5-second range at current playhead
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

    // Build preview-range used to constrain playback: the selected range if any,
    // else the first effective range
    val previewRange: TrimRange? = remember(ranges, trimMode, selectedRangeIdx, playOrder) {
        val effective = ClipEdit(clip.uri, ranges, trimMode, playOrder).effectiveRanges(clipDurationMs)
        when {
            effective.isEmpty() -> null
            selectedRangeIdx != null && selectedRangeIdx!! < ranges.size && trimMode == TrimMode.KEEP_RANGES ->
                ranges.getOrNull(selectedRangeIdx!!)
            else -> effective.first()
        }
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
            Column(modifier = Modifier.fillMaxSize()) {
                // ============ FIXED: Header ============
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trim Clip", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(clip.name, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1)
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            onSave(ClipEdit(
                                sourceUri = clip.uri,
                                ranges = ranges,
                                mode = trimMode,
                                playOrder = playOrder
                            ))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Save") }
                }

                // ============ FIXED: Video preview ============
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
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

                // ============ SCROLLABLE: everything else ============
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (effectiveRangeCount > 1) {
                                Text("$effectiveRangeCount segments",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                            }
                        }
                        Text(formatTime(clipDurationMs), fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(6.dp))

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
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // ===== Multi-region scrubber (taller, clearer) =====
                    MultiRegionScrubber(
                        durationMs = clipDurationMs,
                        ranges = ranges,
                        trimMode = trimMode,
                        selectedIdx = selectedRangeIdx,
                        playheadMs = playerPositionMs,
                        keyframes = keyframes,
                        onRangeTap = { idx ->
                            selectedRangeIdx = if (selectedRangeIdx == idx) null else idx
                        },
                        onRangeStartChange = { idx, newStart ->
                            val newRanges = ranges.toMutableList()
                            newRanges[idx] = TrimRange(newStart, newRanges[idx].endMs)
                            ranges = newRanges
                        },
                        onRangeEndChange = { idx, newEnd ->
                            val newRanges = ranges.toMutableList()
                            newRanges[idx] = TrimRange(newRanges[idx].startMs, newEnd)
                            ranges = newRanges
                        },
                        onPlayheadSeek = { ms ->
                            exoPlayer.seekTo(ms.coerceIn(0L, clipDurationMs))
                        },
                        snapStart = ::snapStart,
                        snapEnd = ::snapEnd
                    )

                    Spacer(Modifier.height(10.dp))

                    // ===== Add range + Mode toggle =====
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val start = snapStart(playerPositionMs.coerceAtLeast(0L))
                                val end = snapEnd((playerPositionMs + 5000).coerceAtMost(clipDurationMs))
                                if (end > start + 100) {
                                    ranges = ranges + TrimRange(start, end)
                                    selectedRangeIdx = ranges.size - 1
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add range", fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        FilterChip(
                            selected = trimMode == TrimMode.KEEP_RANGES,
                            onClick = { trimMode = TrimMode.KEEP_RANGES },
                            label = { Text("Keep", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = trimMode == TrimMode.REMOVE_RANGES,
                            onClick = { trimMode = TrimMode.REMOVE_RANGES },
                            label = { Text("Cut out", fontSize = 12.sp) }
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        when (trimMode) {
                            TrimMode.KEEP_RANGES -> "Keep mode: marked segments are kept"
                            TrimMode.REMOVE_RANGES -> "Cut-out mode: marked segments are removed"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )

                    Spacer(Modifier.height(10.dp))

                    // ===== Range list =====
                    if (ranges.isNotEmpty()) {
                        RangeListVertical(
                            ranges = ranges,
                            trimMode = trimMode,
                            playOrder = playOrder,
                            selectedIdx = selectedRangeIdx,
                            onSelect = { selectedRangeIdx = it },
                            onDelete = { idx ->
                                val newRanges = ranges.toMutableList().also { it.removeAt(idx) }
                                ranges = newRanges
                                playOrder = playOrder
                                    .filter { it != idx }
                                    .map { if (it > idx) it - 1 else it }
                                if (selectedRangeIdx == idx) selectedRangeIdx = null
                                else if (selectedRangeIdx != null && selectedRangeIdx!! > idx) {
                                    selectedRangeIdx = selectedRangeIdx!! - 1
                                }
                            },
                            onMoveUp = { idx ->
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
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("No ranges yet",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Tap 'Add range' to mark a segment, or use the timeline above.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ===== Number entry for selected range =====
                    val selIdx = selectedRangeIdx
                    if (selIdx != null && selIdx < ranges.size) {
                        val selectedRange = ranges[selIdx]
                        Spacer(Modifier.height(10.dp))
                        Text("Selected range times (mm:ss.s):",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TimeEntryField(
                                label = "Start",
                                value = selectedRange.startMs,
                                onValueChange = { newValue ->
                                    val snapped = snapStart(newValue)
                                    if (snapped < selectedRange.endMs) {
                                        val newRanges = ranges.toMutableList()
                                        newRanges[selIdx] = TrimRange(
                                            snapped.coerceAtLeast(0L),
                                            selectedRange.endMs
                                        )
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
                                    if (snapped > selectedRange.startMs) {
                                        val newRanges = ranges.toMutableList()
                                        newRanges[selIdx] = TrimRange(
                                            selectedRange.startMs,
                                            snapped.coerceAtMost(clipDurationMs)
                                        )
                                        ranges = newRanges
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // ===== Snap toggle =====
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = snapToKeyframes,
                                onCheckedChange = { snapToKeyframes = it }
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Snap to keyframes",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (snapToKeyframes)
                                        "Fast merge — cut may shift by ±1s"
                                    else
                                        "Precise trim — requires re-encoding (slow)",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        if (!keyframesLoaded) "Loading keyframes..."
                        else "${keyframes.size} keyframes found",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )

                    // Bottom padding so the last item isn't flush against the dialog edge
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

// ============================================================================
// Multi-region scrubber — clearer, more touchable
// ============================================================================

/**
 * Scrubber design:
 *   - Total height 96dp (60dp track + 18dp top/bottom for handles)
 *   - Each range has start + end handles ALWAYS visible (drawn outside the track rect)
 *   - Tap on track empty space → move playhead
 *   - Drag empty space → drag playhead
 *   - Tap on a range body → select it
 *   - Drag a range body → move the WHOLE range (start and end together)
 *   - Drag a handle → resize that specific range
 *   - Snap-on-release behavior preserved
 */
@Composable
private fun MultiRegionScrubber(
    durationMs: Long,
    ranges: List<TrimRange>,
    trimMode: TrimMode,
    selectedIdx: Int?,
    playheadMs: Long,
    keyframes: List<Long>,
    onRangeTap: (Int) -> Unit,
    onRangeStartChange: (Int, Long) -> Unit,
    onRangeEndChange: (Int, Long) -> Unit,
    onPlayheadSeek: (Long) -> Unit,
    snapStart: (Long) -> Long,
    snapEnd: (Long) -> Long
) {
    val density = LocalDensity.current
    val rangeColor = when (trimMode) {
        TrimMode.KEEP_RANGES -> MaterialTheme.colorScheme.primary
        TrimMode.REMOVE_RANGES -> MaterialTheme.colorScheme.error
    }

    // Total height: track (60dp) + handle overhang (top 18dp + bottom 18dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val msToX = { ms: Long ->
                if (durationMs > 0) (ms.toFloat() / durationMs) * widthPx else 0f
            }
            val xToMs = { x: Float ->
                if (widthPx > 0) ((x / widthPx) * durationMs).toLong() else 0L
            }

            // ===== Track background =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(durationMs) {
                        // Tap to seek
                        detectTapGestures { offset ->
                            onPlayheadSeek(xToMs(offset.x).coerceIn(0L, durationMs))
                        }
                    }
                    .pointerInput(durationMs) {
                        // Drag to scrub
                        detectDragGestures { change, _ ->
                            onPlayheadSeek(xToMs(change.position.x).coerceIn(0L, durationMs))
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

            // ===== Range blocks =====
            ranges.forEachIndexed { idx, range ->
                val startXDp = with(density) { msToX(range.startMs).toDp() }
                val endXDp = with(density) { msToX(range.endMs).toDp() }
                val widthDp = (endXDp - startXDp).coerceAtLeast(4.dp)
                val isSelected = idx == selectedIdx

                // Range body — clickable to select; also draggable to move whole range
                Box(
                    modifier = Modifier
                        .offset(x = startXDp, y = 18.dp)
                        .width(widthDp)
                        .height(60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(rangeColor.copy(alpha = if (isSelected) 0.7f else 0.4f))
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) rangeColor else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .pointerInput(idx, range) {
                            detectTapGestures { onRangeTap(idx) }
                        }
                ) {
                    // Show range index as small label inside
                    Text(
                        "#${idx + 1}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(2.dp)
                    )
                }
            }

            // ===== Handles (drawn ABOVE/BELOW the track so they don't overlap with ranges) =====
            // Each range gets its own pair of handles, always visible.
            ranges.forEachIndexed { idx, range ->
                val isSelected = idx == selectedIdx
                val handleColor = if (isSelected) rangeColor
                    else rangeColor.copy(alpha = 0.7f)

                // START handle — extends ABOVE the track (top 18dp)
                HandleControl(
                    xDp = with(density) { msToX(range.startMs).toDp() },
                    topDp = 0.dp,
                    color = handleColor,
                    handleType = HandleType.START,
                    onDragStart = { onRangeTap(idx) },
                    onDragMs = { deltaMs ->
                        val newStart = (range.startMs + deltaMs).coerceIn(0L, range.endMs - 100L)
                        onRangeStartChange(idx, newStart)
                    },
                    onDragEnd = {
                        val current = ranges.getOrNull(idx) ?: return@HandleControl
                        val snapped = snapStart(current.startMs).coerceIn(0L, current.endMs - 100L)
                        onRangeStartChange(idx, snapped)
                    },
                    xToMs = xToMs
                )

                // END handle — extends BELOW the track (bottom 18dp)
                HandleControl(
                    xDp = with(density) { msToX(range.endMs).toDp() },
                    topDp = 78.dp,  // 96dp total - 18dp handle height = 78dp
                    color = handleColor,
                    handleType = HandleType.END,
                    onDragStart = { onRangeTap(idx) },
                    onDragMs = { deltaMs ->
                        val newEnd = (range.endMs + deltaMs).coerceIn(range.startMs + 100L, durationMs)
                        onRangeEndChange(idx, newEnd)
                    },
                    onDragEnd = {
                        val current = ranges.getOrNull(idx) ?: return@HandleControl
                        val snapped = snapEnd(current.endMs).coerceIn(current.startMs + 100L, durationMs)
                        onRangeEndChange(idx, snapped)
                    },
                    xToMs = xToMs
                )
            }

            // ===== Playhead =====
            val playheadDp = with(density) { msToX(playheadMs).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = playheadDp - 1.dp, y = 12.dp)
                    .width(2.dp)
                    .height(72.dp)
                    .background(Color.White)
            )
        }
    }
}

private enum class HandleType { START, END }

/**
 * A draggable trim handle. Rendered as a 12dp-wide visible bar with a 48dp-wide
 * invisible touch target (centered horizontally). Drag accumulates locally without
 * snapping; on release, the parent snaps.
 */
@Composable
private fun HandleControl(
    xDp: androidx.compose.ui.unit.Dp,
    topDp: androidx.compose.ui.unit.Dp,
    color: Color,
    handleType: HandleType,
    onDragStart: () -> Unit,
    onDragMs: (Long) -> Unit,
    onDragEnd: () -> Unit,
    xToMs: (Float) -> Long
) {
    // Touch target box (invisible, 48dp wide), positioned centered on xDp
    Box(
        modifier = Modifier
            .offset(x = xDp - 24.dp, y = topDp)
            .width(48.dp)
            .height(48.dp)
            .pointerInput(handleType) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { },
                    onDrag = { _, dragAmount ->
                        val deltaMs = xToMs(dragAmount.x)
                        onDragMs(deltaMs)
                    }
                )
            }
    ) {
        // Visible handle bar (12dp wide × 36dp tall, centered)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(12.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

// ============================================================================
// Range list — vertical, clearer per-range info
// ============================================================================

@Composable
private fun RangeListVertical(
    ranges: List<TrimRange>,
    trimMode: TrimMode,
    playOrder: List<Int>,
    selectedIdx: Int?,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    // Display order: KEEP mode applies playOrder, REMOVE mode = source order
    val displayOrder = if (trimMode == TrimMode.KEEP_RANGES && playOrder.isNotEmpty())
        playOrder.filter { it < ranges.size }
    else ranges.indices.toList()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Ranges (${ranges.size}):",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        displayOrder.forEachIndexed { displayPos, rangeIdx ->
            val range = ranges[rangeIdx]
            val isSelected = rangeIdx == selectedIdx
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(rangeIdx) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${displayPos + 1}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${formatTime(range.startMs)} → ${formatTime(range.endMs)}",
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        "${"%.1f".format(range.durationMs / 1000.0)} seconds",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Reorder arrows (only meaningful in KEEP mode with multiple ranges)
                if (trimMode == TrimMode.KEEP_RANGES && ranges.size > 1 && isSelected) {
                    IconButton(
                        onClick = { onMoveUp(rangeIdx) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.ArrowUpward, "Move earlier",
                            modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { onMoveDown(rangeIdx) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.ArrowDownward, "Move later",
                            modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(
                    onClick = { onDelete(rangeIdx) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        "Delete range",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
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
