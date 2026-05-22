package com.camcompiler.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * State for the Find Moment feature. Lives on the ViewModel so it survives
 * configuration changes and tab navigation.
 */
class FindMomentState {
    enum class Mode { PICKING, ANALYZING, REVIEWING }

    var mode by mutableStateOf(Mode.PICKING)

    // PICKING state
    var selectedClipUri by mutableStateOf<Uri?>(null)
    var query by mutableStateOf("")
    var searchMode by mutableStateOf(FindMomentSettings.SearchMode.FIND_ONE_MOMENT)
    var targetLengthSec by mutableStateOf(45)
    var defaultTransition by mutableStateOf(Transition.FADE_BLACK)

    // ANALYZING state
    var progress by mutableStateOf(
        FindMomentProgress(FindMomentPhase.EMBEDDING_QUERIES, 0f)
    )
    var detectorJob: Job? = null
    var expandedQueriesPreview by mutableStateOf<List<String>>(emptyList())

    // REVIEWING state
    var analysis by mutableStateOf<FindMomentAnalysis?>(null)
    var modeAChosenIdx by mutableStateOf<Int?>(null)  // which candidate user picked in Mode A
    var modeBCandidates by mutableStateOf<List<MatchCandidate>>(emptyList())  // mutable for delete
    var modeBTransitions by mutableStateOf<List<Transition>>(emptyList())

    fun reset() {
        mode = Mode.PICKING
        selectedClipUri = null
        query = ""
        progress = FindMomentProgress(FindMomentPhase.EMBEDDING_QUERIES, 0f)
        analysis = null
        modeAChosenIdx = null
        modeBCandidates = emptyList()
        modeBTransitions = emptyList()
        detectorJob = null
        expandedQueriesPreview = emptyList()
    }

    fun setAnalysis(a: FindMomentAnalysis) {
        analysis = a
        when (a.settings.mode) {
            FindMomentSettings.SearchMode.FIND_ONE_MOMENT -> {
                modeAChosenIdx = if (a.candidates.isNotEmpty()) 0 else null
            }
            FindMomentSettings.SearchMode.BUILD_REEL -> {
                modeBCandidates = a.candidates
                modeBTransitions = List((a.candidates.size - 1).coerceAtLeast(0)) {
                    a.settings.defaultTransition
                }
            }
        }
        mode = Mode.REVIEWING
    }
}

@Composable
fun FindMomentScreen(
    vm: MainViewModel,
    state: FindMomentState,
    onExportModeA: (MatchCandidate, Transition) -> Unit,
    onExportModeB: (List<MatchCandidate>, List<Transition>) -> Unit,
) {
    // If we navigated away during analysis and came back, reset to picker
    LaunchedEffect(Unit) {
        if (state.mode == FindMomentState.Mode.ANALYZING) {
            val job = state.detectorJob
            if (job == null || !job.isActive) state.reset()
        }
    }

    when (state.mode) {
        FindMomentState.Mode.PICKING -> FindMomentPicker(vm, state)
        FindMomentState.Mode.ANALYZING -> FindMomentAnalyzing(state)
        FindMomentState.Mode.REVIEWING -> FindMomentReview(
            state = state,
            onExportModeA = onExportModeA,
            onExportModeB = onExportModeB,
        )
    }
}

// ============================================================================
// PICKER SCREEN (Screen 1)
// ============================================================================

@Composable
private fun FindMomentPicker(vm: MainViewModel, state: FindMomentState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Clean up stale selection if folder changed
    LaunchedEffect(vm.clips) {
        val uris = vm.clips.map { it.uri }.toSet()
        if (state.selectedClipUri != null && state.selectedClipUri !in uris) {
            state.selectedClipUri = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Describe what you want to find",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // Query input
        OutlinedTextField(
            value = state.query,
            onValueChange = { if (it.length <= 200) state.query = it },
            label = { Text("What are you looking for?") },
            placeholder = { Text("e.g. going through a tunnel, sunset, another cyclist") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences
            ),
            supportingText = { Text("${state.query.length}/200") },
        )

        // Mode toggle (segmented control)
        Column {
            Text("Mode", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(
                    label = "Find one moment",
                    selected = state.searchMode == FindMomentSettings.SearchMode.FIND_ONE_MOMENT,
                    onClick = { state.searchMode = FindMomentSettings.SearchMode.FIND_ONE_MOMENT },
                    modifier = Modifier.weight(1f),
                )
                ModeChip(
                    label = "Build a reel",
                    selected = state.searchMode == FindMomentSettings.SearchMode.BUILD_REEL,
                    onClick = { state.searchMode = FindMomentSettings.SearchMode.BUILD_REEL },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (state.searchMode) {
                    FindMomentSettings.SearchMode.FIND_ONE_MOMENT ->
                        "Returns 3-5 candidate continuous segments. Pick the best one."
                    FindMomentSettings.SearchMode.BUILD_REEL ->
                        "Compiles multiple short moments into a single reel."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Length selector
        Column {
            Text("Output length", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (len in listOf(30, 45, 60, 90)) {
                    LengthChip(
                        seconds = len,
                        selected = state.targetLengthSec == len,
                        onClick = { state.targetLengthSec = len },
                    )
                }
            }
        }

        // Default transition (Mode B only — Mode A doesn't need it)
        if (state.searchMode == FindMomentSettings.SearchMode.BUILD_REEL) {
            Column {
                Text("Transition between scenes", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                TransitionPillRow(
                    current = state.defaultTransition,
                    onCycle = { state.defaultTransition = state.defaultTransition.next() },
                )
            }
        }

        Divider(Modifier.padding(vertical = 4.dp))

        // Clip picker (radio-style — single select)
        Text("Pick the source clip", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (vm.clips.isEmpty()) {
            Text(
                "No clips loaded. Pick a folder first.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            for (clip in vm.clips) {
                ClipRadioRow(
                    clip = clip,
                    selected = clip.uri == state.selectedClipUri,
                    onSelect = { state.selectedClipUri = clip.uri },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Analyze button
        val canAnalyze = state.selectedClipUri != null && state.query.trim().length >= 3
        Button(
            onClick = {
                val clip = vm.clips.firstOrNull { it.uri == state.selectedClipUri } ?: return@Button
                val settings = FindMomentSettings(
                    query = state.query.trim(),
                    mode = state.searchMode,
                    targetLengthMs = state.targetLengthSec * 1000L,
                    defaultTransition = state.defaultTransition,
                )
                state.mode = FindMomentState.Mode.ANALYZING
                state.expandedQueriesPreview = QueryExpander.expand(settings.query)
                state.progress = FindMomentProgress(FindMomentPhase.EMBEDDING_QUERIES, 0f)

                state.detectorJob = scope.launch {
                    val detector = FindMomentDetector(ctx.applicationContext, clip, settings)
                    detector.analyze().collect { evt ->
                        when (evt) {
                            is FindMomentEvent.Progress -> {
                                state.progress = evt.progress
                                if (evt.progress.phase == FindMomentPhase.CANCELLED ||
                                    evt.progress.phase == FindMomentPhase.FAILED) {
                                    state.mode = FindMomentState.Mode.PICKING
                                }
                            }
                            is FindMomentEvent.Done -> {
                                state.setAnalysis(evt.analysis)
                            }
                        }
                    }
                }
            },
            enabled = canAnalyze,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Analyze")
        }
    }
}

@Composable
private fun ClipRadioRow(clip: VideoClip, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                clip.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatDuration(clip.durationSec)} · ${"%.1f".format(clip.sizeMb)} MB",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun LengthChip(seconds: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("${seconds}s", color = fg, fontSize = 13.sp,
             fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun TransitionPillRow(current: Transition, onCycle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .clickable { onCycle() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(current.displayName, fontSize = 13.sp)
    }
}

// ============================================================================
// ANALYZING SCREEN (Screen 2)
// ============================================================================

@Composable
private fun FindMomentAnalyzing(state: FindMomentState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                state.detectorJob?.cancel()
                state.reset()
            }) {
                Icon(Icons.Filled.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Looking for: \"${state.query.trim()}\"",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            state.progress.phase.displayName,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )

        LinearProgressIndicator(
            progress = state.progress.percent.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.progress.framesTotal > 0) {
            Text(
                "Frames: ${state.progress.framesProcessed} / ${state.progress.framesTotal}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.progress.estimatedRemainingMs > 1000L) {
            val secs = (state.progress.estimatedRemainingMs / 1000L).toInt()
            Text(
                "~ ${secs}s remaining",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.progress.errorMessage != null) {
            Text(
                "Error: ${state.progress.errorMessage}",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(24.dp))

        if (state.expandedQueriesPreview.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Searching for any of these phrasings:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    for (q in state.expandedQueriesPreview) {
                        Text("• $q", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ============================================================================
// REVIEW SCREEN (Screen 3) — different layouts for Mode A vs Mode B
// ============================================================================

@Composable
private fun FindMomentReview(
    state: FindMomentState,
    onExportModeA: (MatchCandidate, Transition) -> Unit,
    onExportModeB: (List<MatchCandidate>, List<Transition>) -> Unit,
) {
    val analysis = state.analysis ?: return
    when (analysis.settings.mode) {
        FindMomentSettings.SearchMode.FIND_ONE_MOMENT -> ReviewModeA(state, analysis, onExportModeA)
        FindMomentSettings.SearchMode.BUILD_REEL -> ReviewModeB(state, analysis, onExportModeB)
    }
}

@Composable
private fun ReviewModeA(
    state: FindMomentState,
    analysis: FindMomentAnalysis,
    onExport: (MatchCandidate, Transition) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "\"${analysis.settings.query}\"",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "${analysis.candidates.size} moment(s) found",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (analysis.candidates.isEmpty()) {
            Text(
                "No matching moments found. Try a different description or check the clip duration.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(analysis.candidates) { idx, cand ->
                    CandidateCard(
                        candidate = cand,
                        index = idx,
                        totalCount = analysis.candidates.size,
                        selected = state.modeAChosenIdx == idx,
                        onSelect = { state.modeAChosenIdx = idx },
                    )
                }
            }
        }

        // Bottom actions
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { state.reset() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Different query")
            }
            Button(
                onClick = {
                    val idx = state.modeAChosenIdx ?: return@Button
                    val cand = analysis.candidates.getOrNull(idx) ?: return@Button
                    onExport(cand, state.defaultTransition)
                },
                enabled = state.modeAChosenIdx != null,
                modifier = Modifier.weight(1f),
            ) {
                Text("Export")
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: MatchCandidate,
    index: Int,
    totalCount: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        border = if (selected) androidx.compose.foundation.BorderStroke(
            2.dp, MaterialTheme.colorScheme.primary
        ) else androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Moment ${index + 1} of $totalCount",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    scoreLabel(candidate.score),
                    color = scoreColor(candidate.score),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatMs(candidate.startMs)} — ${formatMs(candidate.endMs)} (${(candidate.durationMs / 1000).toInt()}s)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Peak at ${formatMs(candidate.peakMs)} · score ${"%.2f".format(candidate.score)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReviewModeB(
    state: FindMomentState,
    analysis: FindMomentAnalysis,
    onExport: (List<MatchCandidate>, List<Transition>) -> Unit,
) {
    val totalMs = state.modeBCandidates.sumOf { it.durationMs }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "\"${analysis.settings.query}\"",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Reel: ${formatMs(totalMs)} · ${state.modeBCandidates.size} scenes",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (state.modeBCandidates.isEmpty()) {
            Text(
                "No matching scenes found.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(state.modeBCandidates) { idx, cand ->
                    Column {
                        ReelSceneRow(
                            cand = cand,
                            index = idx,
                            onDelete = {
                                val newList = state.modeBCandidates.toMutableList().apply { removeAt(idx) }
                                val newTrans = state.modeBTransitions.toMutableList()
                                if (idx < newTrans.size) newTrans.removeAt(idx)
                                else if (newTrans.isNotEmpty() && idx == newList.size) newTrans.removeAt(newTrans.size - 1)
                                state.modeBCandidates = newList
                                state.modeBTransitions = newTrans
                            },
                        )
                        if (idx < state.modeBCandidates.size - 1) {
                            TransitionRow(
                                current = state.modeBTransitions.getOrElse(idx) { analysis.settings.defaultTransition },
                                onCycle = {
                                    val updated = state.modeBTransitions.toMutableList()
                                    while (updated.size <= idx) updated.add(analysis.settings.defaultTransition)
                                    updated[idx] = updated[idx].next()
                                    state.modeBTransitions = updated
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { state.reset() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Adjust query")
            }
            Button(
                onClick = { onExport(state.modeBCandidates, state.modeBTransitions) },
                enabled = state.modeBCandidates.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Export reel")
            }
        }
    }
}

@Composable
private fun ReelSceneRow(cand: MatchCandidate, index: Int, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1}",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${formatMs(cand.startMs)} — ${formatMs(cand.endMs)} (${(cand.durationMs / 1000).toInt()}s)",
                    fontSize = 13.sp,
                )
                Text(
                    "score ${"%.2f".format(cand.score)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TransitionRow(current: Transition, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onCycle() }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                current.displayName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================================
// Helpers
// ============================================================================

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
           else "%d:%02d".format(m, s)
}

private fun scoreLabel(score: Float): String = when {
    score >= 0.6f -> "Strong match"
    score >= 0.4f -> "Good match"
    score >= 0.25f -> "Weak match"
    else -> "Low confidence"
}

private fun scoreColor(score: Float): Color = when {
    score >= 0.6f -> Color(0xFF2E7D32)
    score >= 0.4f -> Color(0xFFFB8C00)
    score >= 0.25f -> Color(0xFFE65100)
    else -> Color(0xFFB71C1C)
}
