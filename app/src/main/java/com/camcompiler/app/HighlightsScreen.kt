package com.camcompiler.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ============================================================================
// HIGHLIGHTS SCREEN — top-level dispatcher
//
// Three states:
//   1. PICKING — user picks clips + tunes settings, taps Analyze
//   2. ANALYZING — detector runs, progress shown
//   3. REVIEWING — user sees candidates, can delete/refine/export
//
// Owns its own state in HighlightsState. Communicates with MainViewModel for
// folder/clips access and for triggering the export merge.
// ============================================================================

class HighlightsState {
    enum class Mode { PICKING, ANALYZING, REVIEWING }

    var mode by mutableStateOf(Mode.PICKING)
    var selectedClipUris by mutableStateOf<Set<android.net.Uri>>(emptySet())
    var settings by mutableStateOf(HighlightSettings())
    var analysis by mutableStateOf<HighlightAnalysis?>(null)
    var displayedCandidates by mutableStateOf<List<HighlightCandidate>>(emptyList())
    var transitions by mutableStateOf<List<Transition>>(emptyList()) // between adjacent displayed candidates
    var progress by mutableStateOf<DetectionProgress?>(null)
    var detectorJob by mutableStateOf<Job?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    /** Recompute displayedCandidates from analysis using current target count. */
    fun refreshDisplayed() {
        val a = analysis ?: run {
            displayedCandidates = emptyList()
            transitions = emptyList()
            return
        }
        val top = a.topK(settings.targetCount)
        displayedCandidates = top
        // Initialize/resize transitions to match: one transition slot between adjacent pairs
        val needed = (top.size - 1).coerceAtLeast(0)
        transitions = if (transitions.size == needed) transitions
                      else List(needed) { transitions.getOrNull(it) ?: settings.defaultTransition }
    }

    fun reset() {
        mode = Mode.PICKING
        analysis = null
        displayedCandidates = emptyList()
        transitions = emptyList()
        progress = null
        detectorJob = null
        errorMessage = null
    }
}

@Composable
fun HighlightsScreen(
    vm: MainViewModel,
    state: HighlightsState,
    onExport: (HighlightAnalysis, List<HighlightCandidate>, List<Transition>) -> Unit,
    onRefineInEdit: (HighlightCandidate) -> Unit,
) {
    // If the user navigated away mid-analysis and came back, the detector job will have
    // been cancelled. Reset the state to PICKING so the UI isn't stuck on the analyzing
    // screen with no actual work happening.
    LaunchedEffect(Unit) {
        if (state.mode == HighlightsState.Mode.ANALYZING) {
            val job = state.detectorJob
            if (job == null || !job.isActive) {
                state.reset()
            }
        }
    }

    when (state.mode) {
        HighlightsState.Mode.PICKING -> HighlightsPickerScreen(vm = vm, state = state)
        HighlightsState.Mode.ANALYZING -> HighlightsAnalyzingScreen(state = state)
        HighlightsState.Mode.REVIEWING -> HighlightsReviewScreen(
            state = state,
            onExport = onExport,
            onRefineInEdit = onRefineInEdit
        )
    }
}

// ============================================================================
// SCREEN 1 — Clip picker + settings
// ============================================================================

@Composable
private fun HighlightsPickerScreen(
    vm: MainViewModel,
    state: HighlightsState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Clean up stale selections: if the user switched folders, drop URIs that aren't
    // in the current clip list anymore.
    LaunchedEffect(vm.clips) {
        val currentUris = vm.clips.map { it.uri }.toSet()
        val cleaned = state.selectedClipUris.filter { it in currentUris }.toSet()
        if (cleaned.size != state.selectedClipUris.size) {
            state.selectedClipUris = cleaned
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "Auto-detect highlights",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick one or more clips. We'll analyze scene changes and motion peaks to surface highlight moments.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        )

        Spacer(Modifier.height(16.dp))

        // Settings card (always visible — short enough not to need collapse)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Detection settings", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))

                // Sensitivity — 3-button segmented control
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivity", fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    HighlightSettings.Sensitivity.entries.forEach { s ->
                        val selected = state.settings.sensitivity == s
                        Surface(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clickable { state.settings = state.settings.copy(sensitivity = s) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                s.displayName,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Target count slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Target count", fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Slider(
                        value = state.settings.targetCount.toFloat(),
                        onValueChange = { state.settings = state.settings.copy(targetCount = it.toInt()) },
                        valueRange = 3f..20f,
                        steps = 16,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${state.settings.targetCount}", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }

                Spacer(Modifier.height(8.dp))

                // Default transition pill
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transition", fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Surface(
                        modifier = Modifier.clickable {
                            state.settings = state.settings.copy(
                                defaultTransition = state.settings.defaultTransition.next()
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.SwapHoriz, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(4.dp))
                            Text(state.settings.defaultTransition.displayName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Clips (${state.selectedClipUris.size} selected)",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(8.dp))

        // Clip list with checkboxes
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(vm.clips) { clip ->
                val isSelected = clip.uri in state.selectedClipUris
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.selectedClipUris = if (isSelected)
                                state.selectedClipUris - clip.uri
                            else
                                state.selectedClipUris + clip.uri
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                         else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(clip.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Text("${formatDuration(clip.durationSec)}  •  ${"%.0f".format(clip.sizeMb)} MB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Analyze button
        val canAnalyze = state.selectedClipUris.isNotEmpty()
        Button(
            onClick = {
                val selectedClips = vm.clips.filter { it.uri in state.selectedClipUris }
                if (selectedClips.isEmpty()) return@Button
                state.mode = HighlightsState.Mode.ANALYZING
                state.progress = DetectionProgress(DetectionPhase.INDEXING, 0f, totalClips = selectedClips.size)
                state.errorMessage = null
                val detector = HighlightDetector(context, selectedClips, state.settings)
                state.detectorJob = scope.launch {
                    detector.runFlow(onResult = { result ->
                        state.analysis = result
                    }).collectLatest { p ->
                        state.progress = p
                        when (p.phase) {
                            DetectionPhase.DONE -> {
                                state.refreshDisplayed()
                                state.mode = HighlightsState.Mode.REVIEWING
                            }
                            DetectionPhase.CANCELLED -> {
                                state.mode = HighlightsState.Mode.PICKING
                            }
                            DetectionPhase.FAILED -> {
                                state.errorMessage = p.errorMessage ?: "Detection failed"
                                state.mode = HighlightsState.Mode.PICKING
                            }
                            else -> { /* still working */ }
                        }
                    }
                }
            },
            enabled = canAnalyze,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text(if (canAnalyze) "Analyze ${state.selectedClipUris.size} clip(s)" else "Select clips to analyze")
        }

        state.errorMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ============================================================================
// SCREEN 2 — Analyzing (live progress)
// ============================================================================

@Composable
private fun HighlightsAnalyzingScreen(state: HighlightsState) {
    val p = state.progress
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Analyzing...",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            p?.phase?.displayName ?: "Starting",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { (p?.percent ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "${((p?.percent ?: 0f) * 100).toInt()}%${formatRemainingTime(p?.estimatedRemainingMs)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        if (p != null && p.totalClips > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Clip ${p.currentClipIdx + 1} of ${p.totalClips}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.height(24.dp))

        // Live counters
        if (p != null && (p.sceneCandidatesFound > 0 || p.motionCandidatesFound > 0)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Detected so far", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    Text("▸ ${p.sceneCandidatesFound} scene changes", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text("▲ ${p.motionCandidatesFound} motion peaks", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        OutlinedButton(
            onClick = {
                state.detectorJob?.cancel()
                state.detectorJob = null
                state.mode = HighlightsState.Mode.PICKING
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }
}

private fun formatRemainingTime(estMs: Long?): String {
    if (estMs == null || estMs <= 0) return ""
    val secs = estMs / 1000
    if (secs < 5) return ""
    return when {
        secs < 60 -> " • about ${secs}s remaining"
        secs < 3600 -> " • about ${secs / 60}m remaining"
        else -> " • a while remaining"
    }
}

// ============================================================================
// SCREEN 3 — Review highlights, edit, export
// ============================================================================

@Composable
private fun HighlightsReviewScreen(
    state: HighlightsState,
    onExport: (HighlightAnalysis, List<HighlightCandidate>, List<Transition>) -> Unit,
    onRefineInEdit: (HighlightCandidate) -> Unit,
) {
    val analysis = state.analysis ?: run {
        // Defensive — shouldn't happen
        Text("No analysis available", modifier = Modifier.padding(16.dp))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        val totalSeconds = state.displayedCandidates.sumOf { it.durationMs } / 1000L
        val sourceTotalMin = analysis.totalSourceDurationMs / 60_000L
        val sourceTotalSec = (analysis.totalSourceDurationMs / 1000L) % 60L
        Text(
            "${state.displayedCandidates.size} highlights found",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Total ${formatDuration(totalSeconds)} • from ${sourceTotalMin}:${"%02d".format(sourceTotalSec)} source",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        )

        Spacer(Modifier.height(12.dp))

        // Selectivity re-slider
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show top", fontSize = 12.sp, modifier = Modifier.width(72.dp))
                    Slider(
                        value = state.settings.targetCount.toFloat(),
                        onValueChange = {
                            state.settings = state.settings.copy(targetCount = it.toInt())
                            state.refreshDisplayed()
                        },
                        valueRange = 3f..(analysis.candidates.size.coerceAtLeast(3)).toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text("${state.settings.targetCount}", fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                }
                Text("of ${analysis.candidates.size} candidates",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }

        Spacer(Modifier.height(12.dp))

        if (state.displayedCandidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Info, null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No highlights found", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    Text("Try a Loose sensitivity or different clips.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center)
                }
            }
        } else {
            // Highlight rows with transitions between them
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                state.displayedCandidates.forEachIndexed { idx, cand ->
                    item(key = "hl-$idx-${cand.peakMs}") {
                        HighlightRow(
                            cand = cand,
                            onDelete = {
                                val newList = state.displayedCandidates.toMutableList()
                                newList.removeAt(idx)
                                state.displayedCandidates = newList
                                // Resize transitions
                                val needed = (newList.size - 1).coerceAtLeast(0)
                                state.transitions = state.transitions.take(needed)
                            },
                            onRefineInEdit = { onRefineInEdit(cand) }
                        )
                    }
                    if (idx < state.displayedCandidates.size - 1) {
                        item(key = "tr-$idx") {
                            TransitionPillRow(
                                current = state.transitions.getOrElse(idx) { state.settings.defaultTransition },
                                onCycle = { newT ->
                                    val list = state.transitions.toMutableList()
                                    while (list.size <= idx) list.add(state.settings.defaultTransition)
                                    list[idx] = newT
                                    state.transitions = list
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action buttons
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onExport(analysis, state.displayedCandidates, state.transitions)
                },
                enabled = state.displayedCandidates.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Export highlight reel")
            }

            // "Refine in Edit" — only enabled when all highlights are from the same clip
            val singleClipUri = state.displayedCandidates.map { it.sourceUri }.distinct().singleOrNull()
            if (singleClipUri != null) {
                OutlinedButton(
                    onClick = { onRefineInEdit(state.displayedCandidates.first()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refine in Edit (single clip)")
                }
            }

            TextButton(
                onClick = { state.reset() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Discard and start over",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun HighlightRow(
    cand: HighlightCandidate,
    onDelete: () -> Unit,
    onRefineInEdit: () -> Unit,
) {
    val signalColor = when (cand.signal) {
        HighlightSignal.SCENE_CHANGE -> Color(0xFF5DCAA5)
        HighlightSignal.MOTION_PEAK -> Color(0xFFE89F4A)
        HighlightSignal.COMBINED -> Color(0xFFC57BD8)
        HighlightSignal.AUDIO_PEAK -> Color(0xFF7BB3D8)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(signalColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = signalColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(cand.sourceClipName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${formatHms(cand.startMs)} – ${formatHms(cand.endMs)} (${(cand.durationMs / 1000)}s)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cand.signal.shortLabel, fontSize = 11.sp,
                        color = signalColor, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("score ${"%.2f".format(cand.score)}", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, "Remove",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun TransitionPillRow(
    current: Transition,
    onCycle: (Transition) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.clickable { onCycle(current.next()) },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.SwapHoriz, null, modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(4.dp))
                Text(current.shortLabel, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** Format milliseconds as h:mm:ss or m:ss. */
private fun formatHms(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
