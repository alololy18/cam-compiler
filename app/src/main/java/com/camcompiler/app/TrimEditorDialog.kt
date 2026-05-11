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
import androidx.compose.ui.unit.Dp
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
 * Fullscreen multi-range trim editor — Samsung-inspired scrubber design.
 *
 * Key design choices:
 *  - Each range gets its own distinct color from a rotating palette
 *  - Handles hold LOCAL drag state (currentMs) so dragging is not "fighting"
 *    against parent state updates mid-gesture
 *  - Handles render at LOCAL drag state during drag → smooth finger tracking
 *  - On drag end: commit value + apply snap
 *  - New ranges start where the previous range ended (chained)
 *  - Full body is scrollable so snap toggle is always reachable
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimEditorDialog(
    clip: VideoClip,
    initialEdit: ClipEdit,
    onSave: (ClipEdit) -> Unit,
    onExport: (ClipEdit) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val clipDurationMs = clip.durationSec * 1000L

    var ranges by remember { mutableStateOf(initialEdit.ranges) }
    var trimMode by remember { mutableStateOf(initialEdit.mode) }
    var playOrder by remember { mutableStateOf(initialEdit.playOrder) }
    var rangeTransitions by remember { mutableStateOf(initialEdit.rangeTransitions) }
    var selectedRangeIdx by remember { mutableStateOf<Int?>(null) }

    // Export preview state: when true, we're in the preview-before-commit overlay
    var showExportPreview by remember { mutableStateOf(false) }

    var snapToKeyframes by remember { mutableStateOf(false) }
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

    // Polling loop: tracks player position and play state for UI. Does NOT enforce
    // any "stop at preview range end" behavior — that would fight against the user's
    // manual seek (e.g. they drag the playhead outside the range and try to play).
    // The user can manually pause when desired.
    LaunchedEffect(exoPlayer) {
        while (true) {
            playerPositionMs = exoPlayer.currentPosition
            isPlaying = exoPlayer.isPlaying
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
            Box(modifier = Modifier.fillMaxSize()) {
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
                    Spacer(Modifier.width(2.dp))
                    OutlinedButton(
                        onClick = {
                            onSave(ClipEdit(
                                sourceUri = clip.uri,
                                ranges = ranges,
                                mode = trimMode,
                                playOrder = playOrder,
                                rangeTransitions = rangeTransitions
                            ))
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Save", fontSize = 13.sp) }
                    Spacer(Modifier.width(2.dp))
                    Button(
                        onClick = {
                            // Pause player and switch to preview overlay
                            exoPlayer.pause()
                            showExportPreview = true
                        },
                        enabled = ranges.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Export", fontSize = 13.sp) }
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

                // ============ SCROLLABLE: rest ============
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            if (isPlaying) {
                                exoPlayer.pause()
                            } else {
                                // Just play from current position. Don't force-seek into a
                                // preview range — the user may have intentionally seeked
                                // outside the range and wants to play from there.
                                // If position is past end-of-clip, restart from 0.
                                if (playerPositionMs >= clipDurationMs - 50L) {
                                    exoPlayer.seekTo(0L)
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

                    MultiRegionScrubber(
                        durationMs = clipDurationMs,
                        ranges = ranges,
                        trimMode = trimMode,
                        selectedIdx = selectedRangeIdx,
                        playheadMs = playerPositionMs,
                        keyframes = keyframes,
                        onRangeTap = { idx ->
                            // Toggle selection. If newly selecting, seek preview to range start.
                            val newSel = if (selectedRangeIdx == idx) null else idx
                            selectedRangeIdx = newSel
                            if (newSel != null) {
                                ranges.getOrNull(newSel)?.let { r ->
                                    exoPlayer.seekTo(r.startMs)
                                }
                            }
                        },
                        onRangeSelect = { idx ->
                            // Always select (don't toggle off) and seek preview
                            selectedRangeIdx = idx
                            ranges.getOrNull(idx)?.let { r ->
                                exoPlayer.seekTo(r.startMs)
                            }
                        },
                        onRangeStartCommit = { idx, finalMs ->
                            val newRanges = ranges.toMutableList()
                            val current = newRanges[idx]
                            val snapped = snapStart(finalMs).coerceIn(0L, current.endMs - 100L)
                            newRanges[idx] = TrimRange(snapped, current.endMs)
                            ranges = newRanges
                        },
                        onRangeEndCommit = { idx, finalMs ->
                            val newRanges = ranges.toMutableList()
                            val current = newRanges[idx]
                            val snapped = snapEnd(finalMs).coerceIn(current.startMs + 100L, clipDurationMs)
                            newRanges[idx] = TrimRange(current.startMs, snapped)
                            ranges = newRanges
                        },
                        onPlayheadSeek = { ms ->
                            exoPlayer.seekTo(ms.coerceIn(0L, clipDurationMs))
                        }
                    )

                    // Dedicated playhead control strip below the scrubber.
                    // Grab the pinhead and drag to seek through the source clip.
                    PlayheadStrip(
                        durationMs = clipDurationMs,
                        playheadMs = playerPositionMs,
                        onPlayheadSeek = { ms ->
                            exoPlayer.seekTo(ms.coerceIn(0L, clipDurationMs))
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    // Add range + Mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                // New range starts after the last range ends, with a small gap
                                // so the handles don't sit directly on top of each other.
                                val startPoint = if (ranges.isNotEmpty()) {
                                    (ranges.maxOf { it.endMs } + 200L).coerceAtMost(clipDurationMs - 100L)
                                } else {
                                    playerPositionMs.coerceAtLeast(0L)
                                }
                                val rawStart = startPoint
                                val rawEnd = (startPoint + 5000).coerceAtMost(clipDurationMs)
                                val start = snapStart(rawStart).coerceAtLeast(0L)
                                val end = snapEnd(rawEnd).coerceAtMost(clipDurationMs)
                                if (end > start + 100) {
                                    ranges = ranges + TrimRange(start, end)
                                    selectedRangeIdx = ranges.size - 1
                                    exoPlayer.seekTo(start)
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

                    if (ranges.isNotEmpty()) {
                        RangeListVertical(
                            ranges = ranges,
                            trimMode = trimMode,
                            playOrder = playOrder,
                            rangeTransitions = rangeTransitions,
                            selectedIdx = selectedRangeIdx,
                            onSelect = { selectedRangeIdx = it },
                            onDelete = { idx ->
                                val newRanges = ranges.toMutableList().also { it.removeAt(idx) }
                                ranges = newRanges
                                playOrder = playOrder
                                    .filter { it != idx }
                                    .map { if (it > idx) it - 1 else it }
                                // Shift rangeTransitions to match. The transition at position N is
                                // between range N and N+1. When deleting range idx:
                                //  - if idx == 0: drop transition 0 (between old #0 and old #1)
                                //  - if idx == last: drop transition idx-1 (between old #idx-1 and old #idx)
                                //  - else: drop transition at idx (between old #idx and old #idx+1)
                                //    This keeps transition[idx-1] joining new ranges [idx-1] and [idx].
                                if (rangeTransitions.isNotEmpty()) {
                                    val dropAt = if (idx == 0) 0
                                        else if (idx >= rangeTransitions.size) rangeTransitions.size - 1
                                        else idx
                                    rangeTransitions = rangeTransitions.toMutableList().also {
                                        it.removeAt(dropAt)
                                    }
                                }
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
                            },
                            onTransitionCycle = { displayPos, newT ->
                                // Ensure rangeTransitions has enough slots: ranges.size - 1
                                val needed = (ranges.size - 1).coerceAtLeast(0)
                                val current = rangeTransitions.toMutableList()
                                while (current.size < needed) current.add(Transition.NONE)
                                if (displayPos in current.indices) {
                                    current[displayPos] = newT
                                }
                                rangeTransitions = current
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
                                Text("Tap 'Add range' to mark a segment.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

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
                                Text("Snap to keyframes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

                    Spacer(Modifier.height(20.dp))
                }
            }

            // Export preview overlay — covers the editor body
            if (showExportPreview) {
                ExportPreviewOverlay(
                    clip = clip,
                    edit = ClipEdit(clip.uri, ranges, trimMode, playOrder, rangeTransitions),
                    onConfirm = {
                        val finalEdit = ClipEdit(clip.uri, ranges, trimMode, playOrder, rangeTransitions)
                        showExportPreview = false
                        onExport(finalEdit)
                    },
                    onBack = { showExportPreview = false }
                )
            }
            }
        }
    }
}

// ============================================================================
// Distinct color palette for ranges (Samsung-inspired distinct hues)
// ============================================================================

private val RangePalette = listOf(
    Color(0xFFFF6B6B), // Coral
    Color(0xFF4ECDC4), // Teal
    Color(0xFFFFE066), // Amber yellow
    Color(0xFFA78BFA), // Purple
    Color(0xFF60A5FA), // Sky blue
    Color(0xFF34D399), // Emerald
    Color(0xFFF472B6), // Pink
    Color(0xFFFB923C)  // Orange
)

private fun colorForRange(idx: Int, isCutMode: Boolean): Color {
    // In cut-out mode, override with red shades to convey "these are being removed"
    return if (isCutMode) {
        // Slightly varied reds so multiple cuts are still distinguishable
        val reds = listOf(
            Color(0xFFEF4444),
            Color(0xFFDC2626),
            Color(0xFFB91C1C),
            Color(0xFFF87171),
            Color(0xFFFCA5A5)
        )
        reds[idx % reds.size]
    } else {
        RangePalette[idx % RangePalette.size]
    }
}

// ============================================================================
// Multi-region scrubber — Samsung-style with per-range colors
// ============================================================================

@Composable
private fun MultiRegionScrubber(
    durationMs: Long,
    ranges: List<TrimRange>,
    trimMode: TrimMode,
    selectedIdx: Int?,
    playheadMs: Long,
    keyframes: List<Long>,
    onRangeTap: (Int) -> Unit,
    onRangeSelect: (Int) -> Unit,
    onRangeStartCommit: (Int, Long) -> Unit,
    onRangeEndCommit: (Int, Long) -> Unit,
    onPlayheadSeek: (Long) -> Unit
) {
    val density = LocalDensity.current
    val isCutMode = trimMode == TrimMode.REMOVE_RANGES

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

            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            onPlayheadSeek(xToMs(offset.x).coerceIn(0L, durationMs))
                        }
                    }
                    .pointerInput(durationMs) {
                        detectDragGestures { change, _ ->
                            onPlayheadSeek(xToMs(change.position.x).coerceIn(0L, durationMs))
                        }
                    }
            ) {
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

            // Per-range blocks + handles. Each range owns its drag state.
            ranges.forEachIndexed { idx, range ->
                val rangeColor = colorForRange(idx, isCutMode)
                val isSelected = idx == selectedIdx

                RangeWithHandles(
                    rangeIdx = idx,
                    rangeStartMs = range.startMs,
                    rangeEndMs = range.endMs,
                    durationMs = durationMs,
                    isSelected = isSelected,
                    color = rangeColor,
                    msToX = msToX,
                    xToMs = xToMs,
                    density = density,
                    onTap = { onRangeTap(idx) },
                    onSelectOnly = { onRangeSelect(idx) },
                    onStartCommit = { finalMs -> onRangeStartCommit(idx, finalMs) },
                    onEndCommit = { finalMs -> onRangeEndCommit(idx, finalMs) }
                )
            }

            // Playhead line — non-interactive; the user drags the pinhead strip below.
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

// ============================================================================
// Dedicated playhead control strip — sits below the scrubber.
//
// A thin row containing a horizontal reference line and a draggable pinhead.
// The pinhead is always grabbable regardless of what's in the scrubber above.
// During drag, the player seeks live so frames update under the user's finger.
// ============================================================================

@Composable
private fun PlayheadStrip(
    durationMs: Long,
    playheadMs: Long,
    onPlayheadSeek: (Long) -> Unit
) {
    val density = LocalDensity.current

    // CRITICAL: pointerInput lambdas capture references at the time they're established.
    // If we just read `playheadMs` inside the lambda, we get a stale value (whatever it was
    // when the lambda was first created). That caused the playhead to "jump back to zero"
    // when dragging — onDragStart was reading playheadMs from initial composition.
    //
    // Fix: route prop access through rememberUpdatedState so the lambda sees the
    // latest value via .value (a stable State reference).
    val playheadMsState = rememberUpdatedState(playheadMs)
    val onPlayheadSeekState = rememberUpdatedState(onPlayheadSeek)

    // Absolute-position drag state: during drag, this overrides the prop-driven position.
    // During drag we do NOT seek the player live — only the visual moves. On release,
    // we seek once. This avoids player state churn and the polling-loop tug-of-war.
    var dragAbsoluteMs by remember { mutableStateOf<Long?>(null) }
    val renderMs = dragAbsoluteMs ?: playheadMs

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val msToX = { ms: Long ->
                if (durationMs > 0) (ms.toFloat() / durationMs) * widthPx else 0f
            }
            val xToMs = { x: Float ->
                if (widthPx > 0) ((x / widthPx) * durationMs).toLong() else 0L
            }

            // Reference line through center of strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )

            // Tap-to-seek anywhere on the strip (drawn behind pinhead so pinhead drag wins)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            onPlayheadSeekState.value(
                                xToMs(offset.x).coerceIn(0L, durationMs)
                            )
                        }
                    }
            )

            // Pinhead at playhead position. 48dp touch target, 18dp visible circle.
            val pinDp = with(density) { msToX(renderMs).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = pinDp - 24.dp, y = 0.dp)
                    .width(48.dp)
                    .height(28.dp)
                    .pointerInput(durationMs) {
                        detectDragGestures(
                            onDragStart = {
                                // Snapshot the LATEST playheadMs via the state ref
                                dragAbsoluteMs = playheadMsState.value
                            },
                            onDragEnd = {
                                val finalMs = (dragAbsoluteMs ?: playheadMsState.value)
                                    .coerceIn(0L, durationMs)
                                dragAbsoluteMs = null
                                onPlayheadSeekState.value(finalMs)
                            },
                            onDragCancel = { dragAbsoluteMs = null },
                            onDrag = { _, dragAmount ->
                                val deltaMs = xToMs(dragAmount.x)
                                val current = dragAbsoluteMs ?: playheadMsState.value
                                val proposed = (current + deltaMs).coerceIn(0L, durationMs)
                                dragAbsoluteMs = proposed
                                // NOTE: no seekTo during drag. Only on release.
                            }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(9.dp)
                        )
                )
            }
        }
    }
}

/**
 * A single range + its two handles.
 *
 * Architecture (offset-based drag):
 *  - rangeStartMs / rangeEndMs (props) = source of truth from parent
 *  - During drag, only an OFFSET is accumulated locally (startDragOffsetMs / endDragOffsetMs)
 *  - Render position = prop + offset (or just prop when not dragging)
 *  - On drag end: commit absolute value (prop + offset) and clear offset.
 *    Parent recomposes with new prop, render returns to prop directly.
 *
 * This avoids the "stale closure" bug where local state could go out of sync
 * with props during reselection or reorder.
 */
@Composable
private fun RangeWithHandles(
    rangeIdx: Int,
    rangeStartMs: Long,
    rangeEndMs: Long,
    durationMs: Long,
    isSelected: Boolean,
    color: Color,
    msToX: (Long) -> Float,
    xToMs: (Float) -> Long,
    density: androidx.compose.ui.unit.Density,
    onTap: () -> Unit,
    onSelectOnly: () -> Unit,
    onStartCommit: (Long) -> Unit,
    onEndCommit: (Long) -> Unit
) {
    // Drag offset model: null when not dragging, accumulated delta when dragging.
    // Rendering position = props + offset.
    // After drag end, we commit absolute value and clear offset; parent recomposes
    // with new props, and rendering returns to using props directly.
    var startDragOffsetMs by remember { mutableStateOf<Long?>(null) }
    var endDragOffsetMs by remember { mutableStateOf<Long?>(null) }

    // Compute render positions
    val renderStartMs = if (startDragOffsetMs != null) {
        (rangeStartMs + startDragOffsetMs!!).coerceIn(0L, rangeEndMs - 100L)
    } else rangeStartMs

    val renderEndMs = if (endDragOffsetMs != null) {
        (rangeEndMs + endDragOffsetMs!!).coerceIn(renderStartMs + 100L, durationMs)
    } else rangeEndMs

    val startXDp = with(density) { msToX(renderStartMs).toDp() }
    val endXDp = with(density) { msToX(renderEndMs).toDp() }
    val widthDp = (endXDp - startXDp).coerceAtLeast(4.dp)

    // Range body — tap to select (toggles selection)
    Box(
        modifier = Modifier
            .offset(x = startXDp, y = 18.dp)
            .width(widthDp)
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (isSelected) 0.7f else 0.45f))
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) color else color.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp)
            )
            .pointerInput(rangeIdx) {
                detectTapGestures { onTap() }
            }
    ) {
        Text(
            "#${rangeIdx + 1}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
        )
    }

    // START handle (pill shape above the track, at left edge of range)
    DragHandle(
        xDp = startXDp,
        topDp = 0.dp,
        color = color,
        isStartHandle = true,
        onDragStart = {
            startDragOffsetMs = 0L
            onSelectOnly()  // select range without toggling
        },
        onDragDelta = { deltaPx ->
            val deltaMs = xToMs(deltaPx)
            val current = startDragOffsetMs ?: 0L
            // Clamp the offset so the resulting position stays within valid bounds.
            // This prevents the "sticky" feeling where the offset runs far past
            // the limits during a fast drag and then has to come all the way back.
            val proposed = current + deltaMs
            val minOffset = -rangeStartMs  // can't go below 0
            val maxOffset = (rangeEndMs - 100L) - rangeStartMs  // can't cross end - 100ms
            startDragOffsetMs = proposed.coerceIn(minOffset, maxOffset)
        },
        onDragEndCommit = {
            val finalOffset = startDragOffsetMs ?: 0L
            startDragOffsetMs = null
            onStartCommit(rangeStartMs + finalOffset)
        }
    )

    // END handle (pill shape below the track, at right edge of range)
    DragHandle(
        xDp = endXDp,
        topDp = 78.dp,
        color = color,
        isStartHandle = false,
        onDragStart = {
            endDragOffsetMs = 0L
            onSelectOnly()
        },
        onDragDelta = { deltaPx ->
            val deltaMs = xToMs(deltaPx)
            val current = endDragOffsetMs ?: 0L
            val proposed = current + deltaMs
            val minOffset = (rangeStartMs + 100L) - rangeEndMs  // can't go below start + 100ms
            val maxOffset = durationMs - rangeEndMs  // can't go past clip duration
            endDragOffsetMs = proposed.coerceIn(minOffset, maxOffset)
        },
        onDragEndCommit = {
            val finalOffset = endDragOffsetMs ?: 0L
            endDragOffsetMs = null
            onEndCommit(rangeEndMs + finalOffset)
        }
    )
}

/**
 * A drag handle pill. 48dp wide touch target with 14dp visible bar.
 *
 * Calls onDragDelta with per-frame pixel delta. The parent accumulates this
 * into its own local state.
 */
@Composable
private fun DragHandle(
    xDp: Dp,
    topDp: Dp,
    color: Color,
    isStartHandle: Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEndCommit: () -> Unit
) {
    Box(
        modifier = Modifier
            .offset(x = xDp - 24.dp, y = topDp)
            .width(48.dp)
            .height(18.dp)
            .pointerInput(isStartHandle) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEndCommit() },
                    onDragCancel = { onDragEndCommit() },
                    onDrag = { _, dragAmount ->
                        onDragDelta(dragAmount.x)
                    }
                )
            }
    ) {
        // Visible pill: 14dp wide, full height, rounded
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(14.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(color)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(7.dp)
                )
        )
    }
}

// ============================================================================
// Range list (vertical, with per-range color dots)
// ============================================================================

@Composable
private fun RangeListVertical(
    ranges: List<TrimRange>,
    trimMode: TrimMode,
    playOrder: List<Int>,
    rangeTransitions: List<Transition>,
    selectedIdx: Int?,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onTransitionCycle: (Int, Transition) -> Unit
) {
    val isCutMode = trimMode == TrimMode.REMOVE_RANGES
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
            val rangeColor = colorForRange(rangeIdx, isCutMode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) rangeColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = if (isSelected) rangeColor else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(rangeIdx) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Color dot + number
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(rangeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${displayPos + 1}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
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
                if (trimMode == TrimMode.KEEP_RANGES && ranges.size > 1 && isSelected) {
                    IconButton(onClick = { onMoveUp(rangeIdx) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ArrowUpward, "Move earlier", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onMoveDown(rangeIdx) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ArrowDownward, "Move later", modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = { onDelete(rangeIdx) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        "Delete range",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Transition pill between this range and the next (only between, not after last)
            if (displayPos < displayOrder.size - 1) {
                val currentTransition = rangeTransitions.getOrElse(displayPos) { Transition.NONE }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TransitionPill(
                        current = currentTransition,
                        onCycle = { newT -> onTransitionCycle(displayPos, newT) }
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

// ============================================================================
// Export preview overlay — walks through effective ranges sequentially
// ============================================================================

/**
 * Full-screen overlay that plays each effective range one after the other,
 * showing the user exactly what the export will contain. User then confirms
 * or goes back to editing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ExportPreviewOverlay(
    clip: VideoClip,
    edit: ClipEdit,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipDurationMs = clip.durationSec * 1000L
    val effectiveRanges = remember(edit) { edit.effectiveRanges(clipDurationMs) }
    val totalDurationMs = effectiveRanges.sumOf { it.durationMs }

    // The preview is "playing through" — we track which range is currently playing
    var currentRangeIdx by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var playerPositionMs by remember { mutableStateOf(0L) }

    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clip.uri))
            prepare()
            playWhenReady = true
        }
    }

    // Transition overlay state: when non-null, we're "in" a transition and the player is paused.
    // The overlay covers the video with the transition color (black/white) for the configured duration.
    var currentTransition by remember { mutableStateOf<Transition?>(null) }

    // Seek to the first range when overlay opens
    LaunchedEffect(Unit) {
        if (effectiveRanges.isNotEmpty()) {
            previewPlayer.seekTo(effectiveRanges[0].startMs)
            previewPlayer.play()
        }
    }

    // Monitor playback: track position + advance when range ends.
    // When advancing, check if there's a transition between segments and simulate it.
    LaunchedEffect(previewPlayer, effectiveRanges, edit) {
        while (true) {
            isPlaying = previewPlayer.isPlaying
            playerPositionMs = previewPlayer.currentPosition
            if (effectiveRanges.isNotEmpty() && currentRangeIdx < effectiveRanges.size
                && currentTransition == null) {
                val r = effectiveRanges[currentRangeIdx]
                if (previewPlayer.currentPosition >= r.endMs && previewPlayer.isPlaying) {
                    val next = currentRangeIdx + 1
                    if (next < effectiveRanges.size) {
                        // Check transition between segment currentRangeIdx and next
                        val t = edit.transitionAt(currentRangeIdx)
                        if (t != Transition.NONE) {
                            // Pause player, show overlay for the transition duration, then resume
                            previewPlayer.pause()
                            currentTransition = t
                            delay(t.durationMs)
                            currentTransition = null
                            currentRangeIdx = next
                            previewPlayer.seekTo(effectiveRanges[next].startMs)
                            previewPlayer.play()
                        } else {
                            // No transition — seamless advance
                            currentRangeIdx = next
                            previewPlayer.seekTo(effectiveRanges[next].startMs)
                        }
                    } else {
                        previewPlayer.pause()
                        previewPlayer.seekTo(effectiveRanges[0].startMs)
                        currentRangeIdx = 0
                    }
                }
            }
            delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Preview Export", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${effectiveRanges.size} segment(s)  •  ${formatTime(totalDurationMs)} total",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                TextButton(onClick = onBack) { Text("Back to edit") }
            }

            // Video preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = previewPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Transition overlay — black or white covers the video during a simulated transition.
                // Matches what the engine renders: a solid color frame of the configured duration.
                val ct = currentTransition
                if (ct != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (ct.isWhite) Color.White else Color.Black)
                    )
                }
            }

            // ===== Output-timeline scrubber =====
            // Compute current output time from currentRangeIdx + player position within that range
            val outputPositionMs = run {
                if (effectiveRanges.isEmpty() || currentRangeIdx >= effectiveRanges.size) 0L
                else {
                    val priorDurations = (0 until currentRangeIdx).sumOf { effectiveRanges[it].durationMs }
                    val withinRange = (playerPositionMs - effectiveRanges[currentRangeIdx].startMs)
                        .coerceAtLeast(0L)
                        .coerceAtMost(effectiveRanges[currentRangeIdx].durationMs)
                    priorDurations + withinRange
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTime(outputPositionMs),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = outputPositionMs.toFloat().coerceIn(0f, totalDurationMs.toFloat().coerceAtLeast(1f)),
                    onValueChange = { newOutputMs ->
                        // Map output time → which range + source position
                        var remainingMs = newOutputMs.toLong().coerceAtLeast(0L)
                        var targetIdx = 0
                        for ((i, r) in effectiveRanges.withIndex()) {
                            if (remainingMs <= r.durationMs) {
                                targetIdx = i
                                break
                            }
                            remainingMs -= r.durationMs
                            targetIdx = i  // fallback to last if we exhaust
                        }
                        if (effectiveRanges.isNotEmpty() && targetIdx < effectiveRanges.size) {
                            val target = effectiveRanges[targetIdx]
                            val targetSourceMs = (target.startMs + remainingMs)
                                .coerceIn(target.startMs, target.endMs)
                            currentRangeIdx = targetIdx
                            previewPlayer.seekTo(targetSourceMs)
                        }
                    },
                    valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatTime(totalDurationMs),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }

            Spacer(Modifier.height(8.dp))

            // Currently playing segment indicator
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Now playing: Segment ${(currentRangeIdx + 1).coerceAtMost(effectiveRanges.size)} of ${effectiveRanges.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (currentRangeIdx < effectiveRanges.size) {
                    val r = effectiveRanges[currentRangeIdx]
                    Text(
                        "${formatTime(r.startMs)} → ${formatTime(r.endMs)}  (${"%.1f".format(r.durationMs / 1000.0)}s)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = {
                    if (isPlaying) previewPlayer.pause()
                    else {
                        if (effectiveRanges.isNotEmpty() && currentRangeIdx < effectiveRanges.size) {
                            val r = effectiveRanges[currentRangeIdx]
                            if (previewPlayer.currentPosition < r.startMs ||
                                previewPlayer.currentPosition >= r.endMs) {
                                previewPlayer.seekTo(r.startMs)
                            }
                        }
                        previewPlayer.play()
                    }
                }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
                TextButton(onClick = {
                    if (effectiveRanges.isNotEmpty()) {
                        currentRangeIdx = 0
                        previewPlayer.seekTo(effectiveRanges[0].startMs)
                        previewPlayer.play()
                    }
                }) {
                    Text("⏮ Restart")
                }
            }

            Spacer(Modifier.weight(1f))

            // Confirm export bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Text(
                    "Ready to export?",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "This will create a new video file with the segments above.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Back to edit")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Text("Confirm export")
                    }
                }
            }
        }
    }
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
