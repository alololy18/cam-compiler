package com.camcompiler.app

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    // Hub-and-spoke navigation state
    enum class Screen { HUB, MERGE, EDIT, AUDIO }
    var currentScreen by mutableStateOf(Screen.HUB)

    var folderUri by mutableStateOf<Uri?>(null); private set
    var folderName by mutableStateOf<String?>(null); private set
    var clips by mutableStateOf<List<VideoClip>>(emptyList()); private set
    var selectionOrder by mutableStateOf<List<Uri>>(emptyList()); private set
    var status by mutableStateOf("Pick a folder to begin.")
    var isProcessing by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var lastSortInfo by mutableStateOf<ChronologicalSorter.SortResult?>(null)
    var manageMode by mutableStateOf(false)

    var postMergeSourceUris by mutableStateOf<List<Uri>>(emptyList())
    var showDeletePromptAfterMerge by mutableStateOf(false)
    var lastMergedOutputUri by mutableStateOf<Uri?>(null)

    // Files that look like videos by extension but failed validation.
    // Held pending user decision to ignore or include them anyway.
    data class ExcludedFile(val uri: Uri, val name: String, val sizeMb: Double, val reason: String)
    var pendingExclusions by mutableStateOf<List<ExcludedFile>>(emptyList())
    var showExclusionPrompt by mutableStateOf(false)

    // Mismatch dialog state — appears when ClipAnalyzer detects clips that differ.
    var pendingMismatches by mutableStateOf<List<ClipAnalyzer.Mismatch>>(emptyList())
    var pendingMergeUrisForDialog by mutableStateOf<List<Uri>>(emptyList())
    var showMismatchDialog by mutableStateOf(false)

    // Per-clip edit state. Keys are clip URIs; missing entries mean "no edits".
    var clipEdits by mutableStateOf<Map<Uri, ClipEdit>>(emptyMap())
        private set

    // Project-level music URI. null = no music.
    var musicUri by mutableStateOf<Uri?>(null)
    var musicName by mutableStateOf<String?>(null)
    var musicVolume by mutableStateOf(0.5f)
    var originalAudioVolume by mutableStateOf(1.0f)

    // Trim dialog state — which clip URI is being trimmed (null = not open)
    var trimmingClipUri by mutableStateOf<Uri?>(null)

    fun setClipEdit(uri: Uri, edit: ClipEdit) {
        clipEdits = clipEdits + (uri to edit)
    }

    fun clearClipEdit(uri: Uri) {
        clipEdits = clipEdits - uri
    }

    fun setMusic(uri: Uri?, name: String?) {
        musicUri = uri
        musicName = name
    }

    fun getEditForClip(uri: Uri): ClipEdit =
        clipEdits[uri] ?: ClipEdit(uri)

    /** Builds the EditProject for the given selected clip URIs. */
    fun buildEditProject(selectedUris: List<Uri>): EditProject {
        val edits = selectedUris.map { getEditForClip(it) }
        return EditProject(
            clipEdits = edits,
            musicUri = musicUri,
            musicVolume = musicVolume,
            originalAudioVolume = originalAudioVolume
        )
    }

    init {
        loadLastFolder()
    }

    private fun loadLastFolder() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val saved = Prefs.getLastFolder(ctx) ?: return@launch
            try {
                val uri = Uri.parse(saved)
                val perms = ctx.contentResolver.persistedUriPermissions
                if (perms.any { it.uri == uri && it.isReadPermission }) {
                    setFolder(uri, savePref = false)
                } else {
                    Prefs.clearLastFolder(ctx)
                }
            } catch (_: Exception) {
                Prefs.clearLastFolder(ctx)
            }
        }
    }

    fun setFolder(uri: Uri, savePref: Boolean = true) {
        folderUri = uri
        viewModelScope.launch {
            if (savePref) Prefs.setLastFolder(getApplication(), uri.toString())
            scanFolder(uri)
        }
    }

    /** Re-scan the currently-loaded folder to pick up new/deleted files. */
    fun refresh() {
        val uri = folderUri ?: return
        viewModelScope.launch { scanFolder(uri) }
    }

    private suspend fun scanFolder(uri: Uri) {
        val ctx = getApplication<Application>()
        status = "Scanning folder..."
        clips = emptyList()
        selectionOrder = emptyList()
        lastSortInfo = null
        pendingExclusions = emptyList()
        showExclusionPrompt = false

        data class ScanResult(
            val folderName: String,
            val validClips: List<VideoClip>,
            val excludedVideoLookalikes: List<ExcludedFile>,
            val nonVideoCount: Int
        )

        val result = withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(ctx, uri)
                ?: return@withContext ScanResult("Unknown", emptyList(), emptyList(), 0)

            // Step 1: separate "looks like video" from "definitely not video"
            val allFiles = tree.listFiles().filter { it.isFile }
            val videoLookalikes = mutableListOf<DocumentFile>()
            var nonVideo = 0
            for (doc in allFiles) {
                val n = doc.name?.lowercase() ?: continue
                val mime = doc.type
                val looksLikeVideo = (mime?.startsWith("video/") == true) ||
                    n.endsWith(".mp4") || n.endsWith(".mov") ||
                    n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".m4v") ||
                    n.endsWith(".webm") || n.endsWith(".3gp") || n.endsWith(".ts")
                if (looksLikeVideo) videoLookalikes.add(doc) else nonVideo++
            }

            // Step 2: validate each lookalike. Successful → clip. Failed → excluded list.
            val valid = mutableListOf<VideoClip>()
            val excluded = mutableListOf<ExcludedFile>()
            for (doc in videoLookalikes) {
                val (durMs, reason) = readDurationMsWithReason(ctx, doc.uri)
                if (durMs > 0) {
                    valid.add(
                        VideoClip(
                            uri = doc.uri,
                            name = doc.name ?: "unknown",
                            sizeMb = doc.length() / (1024.0 * 1024.0),
                            durationSec = durMs / 1000,
                            lastModified = doc.lastModified()
                        )
                    )
                } else {
                    excluded.add(
                        ExcludedFile(
                            uri = doc.uri,
                            name = doc.name ?: "unknown",
                            sizeMb = doc.length() / (1024.0 * 1024.0),
                            reason = reason
                        )
                    )
                }
            }
            ScanResult(tree.name ?: "Folder", valid.toList(), excluded.toList(), nonVideo)
        }

        folderName = result.folderName

        if (result.validClips.isEmpty() && result.excludedVideoLookalikes.isEmpty()) {
            status = "No videos found in '${result.folderName}'." +
                if (result.nonVideoCount > 0) " (${result.nonVideoCount} non-video files in folder)" else ""
            return
        }

        // Show the valid ones first
        if (result.validClips.isNotEmpty()) {
            val sortResult = ChronologicalSorter.sort(result.validClips)
            clips = sortResult.ordered
            lastSortInfo = sortResult
            val suffix = if (result.nonVideoCount > 0) "  •  ${result.nonVideoCount} non-video files ignored" else ""
            status = "Found ${result.validClips.size} clips. ${sortResult.strategy}.$suffix"
        } else {
            status = "Folder has video-like files but none could be validated. ${result.excludedVideoLookalikes.size} files need your review."
        }

        // If there are video-lookalikes that failed validation, raise the prompt
        if (result.excludedVideoLookalikes.isNotEmpty()) {
            pendingExclusions = result.excludedVideoLookalikes
            showExclusionPrompt = true
        }
    }

    /** Accept the exclusion — keep only the validated clips. */
    fun ignoreExcludedFiles() {
        showExclusionPrompt = false
        val ignoredCount = pendingExclusions.size
        pendingExclusions = emptyList()
        if (ignoredCount > 0) {
            status = "$status (Ignored $ignoredCount unrecognized files.)"
        }
    }

    /** Override the exclusion — add the failed-validation files to the list anyway. */
    fun includeExcludedFiles() {
        showExclusionPrompt = false
        val ctx = getApplication<Application>()
        val toAdd = pendingExclusions.map { ex ->
            VideoClip(
                uri = ex.uri,
                name = ex.name,
                sizeMb = ex.sizeMb,
                durationSec = 0L, // unknown — couldn't read metadata
                lastModified = try {
                    DocumentFile.fromSingleUri(ctx, ex.uri)?.lastModified() ?: 0L
                } catch (_: Exception) { 0L }
            )
        }
        val merged = clips + toAdd
        val sortResult = ChronologicalSorter.sort(merged)
        clips = sortResult.ordered
        lastSortInfo = sortResult
        status = "Including ${toAdd.size} unverified file(s). They may fail during merge."
        pendingExclusions = emptyList()
    }

    private fun readDurationMs(ctx: Context, uri: Uri): Long {
        return readDurationMsWithReason(ctx, uri).first
    }

    /** Returns (durationMs, reason). durationMs is 0 if invalid; reason describes why. */
    private fun readDurationMsWithReason(ctx: Context, uri: Uri): Pair<Long, String> {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val d = durStr?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            retriever.release()
            when {
                hasVideo != "yes" -> 0L to "No video track"
                d <= 0 -> 0L to "Zero duration"
                else -> d to "OK"
            }
        } catch (e: Exception) {
            0L to (e.message?.take(50) ?: "Could not read metadata")
        }
    }

    fun toggleSelection(uri: Uri) {
        selectionOrder = if (selectionOrder.contains(uri)) {
            selectionOrder.filter { it != uri }
        } else {
            selectionOrder + uri
        }
    }

    fun selectAllInOrder() {
        selectionOrder = clips.map { it.uri }
    }

    fun clearSelection() {
        selectionOrder = emptyList()
    }

    fun toggleManageMode() {
        manageMode = !manageMode
        if (manageMode) selectionOrder = emptyList()
    }

    fun deleteClips(uris: List<Uri>, onDone: () -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (uri in uris) {
                    try {
                        val doc = DocumentFile.fromSingleUri(ctx, uri)
                        if (doc?.delete() == true) count++
                    } catch (_: Exception) {}
                }
                count
            }
            clips = clips.filterNot { it.uri in uris }
            selectionOrder = selectionOrder.filterNot { it in uris }
            status = "Deleted $deletedCount of ${uris.size} clips."
            onDone()
        }
    }

    /** Convenience overload — for callers who don't need a completion callback. */
    fun deleteClips(uris: List<Uri>) {
        deleteClips(uris) { }
    }

    /** Rescan the current folder. No-op if no folder is set. */
    fun refreshFolder() {
        folderUri?.let { setFolder(it, savePref = false) }
    }

    /** Dismiss the post-merge "delete sources?" prompt. */
    fun dismissDeletePrompt() {
        showDeletePromptAfterMerge = false
        postMergeSourceUris = emptyList()
    }

    fun totalSelectedDuration(): Long = clips
        .filter { selectionOrder.contains(it.uri) }
        .sumOf { it.durationSec }

    fun totalAllDuration(): Long = clips.sumOf { it.durationSec }
    fun totalSelectedSizeMb(): Double = clips.filter { selectionOrder.contains(it.uri) }.sumOf { it.sizeMb }
    fun totalAllSizeMb(): Double = clips.sumOf { it.sizeMb }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var mergeService: MergeService? = null
    private var pendingMergeUris: List<Uri> = emptyList()
    private var pendingMode: MergeEngine.Mode = MergeEngine.Mode.FAST

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destUri ->
        if (destUri != null && pendingMergeUris.isNotEmpty()) {
            startMerge(pendingMergeUris, destUri, pendingMode)
        }
        pendingMergeUris = emptyList()
        pendingMode = MergeEngine.Mode.FAST
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MergeService.LocalBinder ?: return
            mergeService = binder.getService()
            mergeService?.listener = { p, s, result ->
                runOnUiThread {
                    vm.progress = p
                    vm.status = s
                    vm.isProcessing = mergeService?.isRunning == true
                    if (result is MergeEngine.Result.Success) {
                        Toast.makeText(this@MainActivity,
                            "Saved! ${"%.1f".format(result.outputBytes / 1024.0 / 1024.0)} MB",
                            Toast.LENGTH_LONG).show()
                        // Refresh folder in case the merged file was saved into it
                        vm.refreshFolder()
                        val srcUris = mergeService?.lastSourceUris ?: emptyList()
                        if (srcUris.isNotEmpty()) {
                            vm.postMergeSourceUris = srcUris
                            vm.showDeletePromptAfterMerge = true
                        }
                    } else if (result is MergeEngine.Result.Failure) {
                        Toast.makeText(this@MainActivity, "Failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            mergeService?.let {
                vm.progress = it.progress
                vm.status = it.status
                vm.isProcessing = it.isRunning
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { mergeService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        setContent {
            com.camcompiler.app.ui.theme.CamCompilerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(
                        vm = vm,
                        onStartSaveAs = { uris -> beginSaveAs(uris) },
                        onCancelMerge = { cancelMerge() },
                        onConfirmReencode = { proceedWithCompatibleMode() },
                        onCancelMismatch = { cancelMismatchDialog() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MergeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(connection) } catch (_: Exception) {}
    }

    private fun beginSaveAs(uris: List<Uri>) {
        vm.status = "Analyzing clips..."
        vm.isProcessing = false
        lifecycleScope.launch {
            val names = uris.map { uri ->
                vm.clips.firstOrNull { it.uri == uri }?.name ?: "clip"
            }
            val analysis = withContext(Dispatchers.IO) {
                ClipAnalyzer.analyze(this@MainActivity, uris, names)
            }
            val project = vm.buildEditProject(uris)

            // Decide mode based on what's in the project + analysis
            val musicPresent = project.hasMusic()
            val codecMixed = analysis is ClipAnalyzer.AnalysisResult.Mixed

            // Check if any trim is non-keyframe-aligned (forces re-encode).
            // For simplicity, treat any clip with non-default trim as potentially non-aligned
            // unless we verify by loading its keyframe index. To keep this responsive,
            // we trust that the trim dialog snapped to keyframes when user chose "snap" mode.
            // The reliable check is: if a clip has edits AND musicPresent is false AND
            // codecs match, try FAST; if MediaMuxer fails (due to non-alignment), it returns an error
            // and we surface to the user.

            when {
                musicPresent -> {
                    // Music always requires re-encode — show informational confirm
                    vm.status = "Music will require re-encoding (slower)."
                    vm.pendingMergeUrisForDialog = uris
                    vm.pendingMismatches = listOf(
                        ClipAnalyzer.Mismatch(
                            clipName = "Music track added",
                            differences = listOf(
                                "Background music: ${vm.musicName ?: "audio"}",
                                "Re-encoding required to mix audio"
                            )
                        )
                    )
                    vm.showMismatchDialog = true
                }
                codecMixed -> {
                    val mixed = analysis as ClipAnalyzer.AnalysisResult.Mixed
                    vm.status = "${mixed.mismatches.size} of ${uris.size} clips differ — re-encoding needed."
                    vm.pendingMismatches = mixed.mismatches
                    vm.pendingMergeUrisForDialog = uris
                    vm.showMismatchDialog = true
                }
                else -> {
                    // Uniform codecs, no music — fast path
                    vm.status = if (project.hasClipEdits()) "Clips trimmed — fast merge."
                        else "Clips are uniform — fast merge."
                    pendingMode = MergeEngine.Mode.FAST
                    launchSaveAsDialog(uris)
                }
            }
        }
    }

    /** Called when the user accepts re-encoding from the mismatch dialog. */
    fun proceedWithCompatibleMode() {
        val uris = vm.pendingMergeUrisForDialog
        vm.showMismatchDialog = false
        vm.pendingMismatches = emptyList()
        vm.pendingMergeUrisForDialog = emptyList()
        if (uris.isEmpty()) return
        pendingMode = MergeEngine.Mode.COMPATIBLE
        launchSaveAsDialog(uris)
    }

    /** Called when the user cancels the mismatch dialog. */
    fun cancelMismatchDialog() {
        vm.showMismatchDialog = false
        vm.pendingMismatches = emptyList()
        vm.pendingMergeUrisForDialog = emptyList()
        vm.status = "Merge cancelled."
    }

    private fun launchSaveAsDialog(uris: List<Uri>) {
        pendingMergeUris = uris
        val defaultName = "vlog_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        saveAsLauncher.launch(defaultName)
    }

    private fun startMerge(uris: List<Uri>, outputUri: Uri, mode: MergeEngine.Mode) {
        vm.isProcessing = true
        vm.progress = 0f
        vm.status = "Starting (${if (mode == MergeEngine.Mode.FAST) "Fast" else "Compatible"} mode)..."
        vm.lastMergedOutputUri = outputUri
        val project = vm.buildEditProject(uris)
        MergeService.start(this, project, outputUri, mode)
        bindService(Intent(this, MergeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun cancelMerge() {
        val intent = Intent(this, MergeService::class.java).apply { action = MergeService.ACTION_CANCEL }
        startService(intent)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    vm: MainViewModel,
    onStartSaveAs: (List<Uri>) -> Unit,
    onCancelMerge: () -> Unit,
    onConfirmReencode: () -> Unit,
    onCancelMismatch: () -> Unit
) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            vm.setFolder(uri)
        }
    }

    val musicPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "audio"
            vm.setMusic(uri, name)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top app bar — shows screen title + back button on task screens
        TopBar(
            screen = vm.currentScreen,
            folderName = vm.folderName,
            onBack = { vm.currentScreen = MainViewModel.Screen.HUB }
        )

        // Screen content — dispatched by currentScreen
        when (vm.currentScreen) {
            MainViewModel.Screen.HUB -> HubScreen(
                vm = vm,
                onPickFolder = { folderPicker.launch(null) },
                onSelectTask = { task -> vm.currentScreen = task }
            )
            MainViewModel.Screen.MERGE -> MergeScreen(
                vm = vm,
                musicPicker = musicPicker,
                onStartSaveAs = onStartSaveAs,
                onCancelMerge = onCancelMerge
            )
            MainViewModel.Screen.EDIT -> EditScreen(vm = vm)
            MainViewModel.Screen.AUDIO -> AudioScreen(
                vm = vm,
                musicPicker = musicPicker,
                onStartSaveAs = onStartSaveAs,
                onCancelMerge = onCancelMerge
            )
        }
    }

    // ============================ Dialogs (global) ============================

    if (vm.showExclusionPrompt && vm.pendingExclusions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.ignoreExcludedFiles() },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("${vm.pendingExclusions.size} file(s) need review") },
            text = {
                Column {
                    Text(
                        "These files have video extensions but couldn't be validated. What would you like to do?",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        items(vm.pendingExclusions) { ex ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(ex.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${"%.1f".format(ex.sizeMb)} MB  •  ${ex.reason}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.ignoreExcludedFiles() }) { Text("Ignore these") } },
            dismissButton = { TextButton(onClick = { vm.includeExcludedFiles() }) { Text("Include anyway") } }
        )
    }

    if (vm.showMismatchDialog && vm.pendingMismatches.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { onCancelMismatch() },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("Re-encoding required") },
            text = {
                Column {
                    Text(
                        "${vm.pendingMismatches.size} item(s) require re-encoding for this merge.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        items(vm.pendingMismatches) { m ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(m.clipName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                m.differences.forEach { diff ->
                                    Text("  • $diff", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Re-encoding takes longer and slightly reduces quality.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { onConfirmReencode() }) { Text("Re-encode and merge") } },
            dismissButton = { TextButton(onClick = { onCancelMismatch() }) { Text("Cancel") } }
        )
    }

    // Trim editor — opens for whichever clip is being trimmed
    val trimmingUri = vm.trimmingClipUri
    if (trimmingUri != null) {
        val clipToTrim = vm.clips.firstOrNull { it.uri == trimmingUri }
        if (clipToTrim != null) {
            TrimEditorDialog(
                clip = clipToTrim,
                initialEdit = vm.getEditForClip(trimmingUri),
                onSave = { newEdit ->
                    if (newEdit.hasEdits()) vm.setClipEdit(trimmingUri, newEdit)
                    else vm.clearClipEdit(trimmingUri)
                    vm.trimmingClipUri = null
                },
                onCancel = { vm.trimmingClipUri = null }
            )
        } else {
            vm.trimmingClipUri = null
        }
    }
}

// ============================ Top bar ============================

@Composable
private fun TopBar(
    screen: MainViewModel.Screen,
    folderName: String?,
    onBack: () -> Unit
) {
    val title = when (screen) {
        MainViewModel.Screen.HUB -> "Cam Compiler"
        MainViewModel.Screen.MERGE -> "Merge Clips"
        MainViewModel.Screen.EDIT -> "Edit Clip"
        MainViewModel.Screen.AUDIO -> "Replace Audio"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (screen != MainViewModel.Screen.HUB) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            if (screen != MainViewModel.Screen.HUB && folderName != null) {
                Text(folderName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1)
            }
        }
    }
}

// ============================ Manage bar (reused by task screens) ============================

/**
 * Shared manage UI used by Merge, Edit, and Audio screens.
 *
 * Layout:
 *  - Always shows: "Manage" chip + count summary
 *  - When manage mode is on AND items are selected: shows "X selected" + Clear/Delete
 *  - Handles the delete confirmation dialog internally
 *
 * Entering manage mode clears any existing non-manage selections.
 * Exiting manage mode clears the manage selection.
 */
@Composable
private fun ManageBar(vm: MainViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top row: count + refresh + manage chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${vm.clips.size} clip(s)  •  ${formatDuration(vm.totalAllDuration())}  •  ${"%.0f".format(vm.totalAllSizeMb())} MB",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.refreshFolder() }) {
                Icon(Icons.Filled.Refresh, "Rescan folder")
            }
            FilterChip(
                selected = vm.manageMode,
                onClick = {
                    vm.manageMode = !vm.manageMode
                    vm.clearSelection()
                },
                label = { Text(if (vm.manageMode) "Done" else "Manage", fontSize = 12.sp) },
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // Manage delete bar — only visible in manage mode with items selected
        if (vm.manageMode && vm.selectionOrder.isNotEmpty() && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${vm.selectionOrder.size} selected for deletion",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        pendingDeleteUris = vm.selectionOrder.toList()
                        showDeleteConfirm = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm && pendingDeleteUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${pendingDeleteUris.size} clip(s)?") },
            text = { Text("This permanently removes the files. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val urisToDel = pendingDeleteUris
                    showDeleteConfirm = false
                    pendingDeleteUris = emptyList()
                    vm.deleteClips(urisToDel)
                    // Exit manage mode after delete
                    vm.manageMode = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ============================ Hub screen ============================

@Composable
private fun HubScreen(
    vm: MainViewModel,
    onPickFolder: () -> Unit,
    onSelectTask: (MainViewModel.Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Folder selection card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickFolder),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Folder,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (vm.folderName != null) "Working folder" else "Pick a folder",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        vm.folderName ?: "Tap to select a folder",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (vm.clips.isNotEmpty()) {
                        Text(
                            "${vm.clips.size} clip(s)  •  ${formatDuration(vm.totalAllDuration())}  •  ${"%.0f".format(vm.totalAllSizeMb())} MB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (vm.folderName != null) {
                    TextButton(onClick = onPickFolder) { Text("Change") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "What would you like to do?",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(12.dp))

        // 3-column grid of task tiles
        val hasFolder = vm.folderUri != null && vm.clips.isNotEmpty()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TaskTile(
                    icon = Icons.Filled.AutoAwesome,
                    label = "Merge",
                    sublabel = "Combine clips",
                    enabled = hasFolder,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTask(MainViewModel.Screen.MERGE) }
                )
                TaskTile(
                    icon = Icons.Filled.ContentCut,
                    label = "Edit",
                    sublabel = "Trim a clip",
                    enabled = hasFolder,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTask(MainViewModel.Screen.EDIT) }
                )
                TaskTile(
                    icon = Icons.Filled.MusicNote,
                    label = "Audio",
                    sublabel = "Replace sound",
                    enabled = hasFolder,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTask(MainViewModel.Screen.AUDIO) }
                )
            }
            // Empty placeholder row for future features
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TaskTile(
                    icon = Icons.Filled.Tune,
                    label = "Filters",
                    sublabel = "Coming soon",
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    onClick = { }
                )
                TaskTile(
                    icon = Icons.Filled.AutoAwesome,
                    label = "Highlights",
                    sublabel = "Coming soon",
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    onClick = { }
                )
                TaskTile(
                    icon = Icons.Filled.PlayCircle,
                    label = "Beat sync",
                    sublabel = "Coming soon",
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    onClick = { }
                )
            }
        }

        if (!hasFolder) {
            Spacer(Modifier.height(20.dp))
            Text(
                if (vm.folderUri == null) "Pick a folder above to enable tasks."
                else "No clips found in this folder.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Optional status text
        if (vm.status.isNotBlank() && vm.folderUri == null) {
            Spacer(Modifier.height(16.dp))
            Text(vm.status, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TaskTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val containerColor = if (enabled)
        MaterialTheme.colorScheme.surface
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    val contentColor = if (enabled)
        MaterialTheme.colorScheme.onSurface
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val iconTint = if (enabled)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(6.dp))
            Text(label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(sublabel,
                fontSize = 10.sp,
                color = contentColor.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1)
        }
    }
}

// ============================ Merge screen ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeScreen(
    vm: MainViewModel,
    musicPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>,
    onStartSaveAs: (List<Uri>) -> Unit,
    onCancelMerge: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Shared manage bar (clip count + refresh + Manage chip + delete bar when active)
        ManageBar(vm = vm)

        // Sort info
        vm.lastSortInfo?.let { si ->
            Spacer(Modifier.height(4.dp))
            Text(
                si.warning ?: si.strategy,
                fontSize = 11.sp,
                color = if (si.warning != null) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        // Status / warnings
        if (vm.status.isNotBlank() && vm.clips.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(vm.status, fontSize = 11.sp,
                color = if (vm.isProcessing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }

        Spacer(Modifier.height(8.dp))

        // Selection/action bar (when items are selected in non-manage mode)
        if (!vm.manageMode && vm.selectionOrder.isNotEmpty() && !vm.isProcessing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Merge ${vm.selectionOrder.size} selected  •  ${formatDuration(vm.totalSelectedDuration())}  •  ${"%.0f".format(vm.totalSelectedSizeMb())} MB",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { vm.clearSelection() }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onStartSaveAs(vm.selectionOrder) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Merge selected clips")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Clip list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(vm.clips) { clip ->
                ClipRow(
                    clip = clip,
                    clipEdit = vm.getEditForClip(clip.uri),
                    selectionIndex = vm.selectionOrder.indexOf(clip.uri).takeIf { it >= 0 },
                    manageMode = vm.manageMode,
                    onClick = { if (!vm.isProcessing) vm.toggleSelection(clip.uri) },
                    onPlayClick = { playVideo(context, clip.uri) },
                    onTrimClick = { vm.trimmingClipUri = clip.uri }
                )
            }
        }

        // Merge-all button when no selection
        if (!vm.manageMode && vm.selectionOrder.isEmpty() && vm.clips.isNotEmpty() && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onStartSaveAs(vm.clips.map { it.uri }) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Merge ALL ${vm.clips.size} clips")
            }

            // Music chip
            Spacer(Modifier.height(6.dp))
            MusicChip(vm = vm, musicPicker = musicPicker)
        }

        // Progress
        if (vm.isProcessing) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { vm.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${(vm.progress * 100).toInt()}%", fontSize = 12.sp)
                TextButton(onClick = onCancelMerge) { Text("Cancel") }
            }
        }
    }

    // Post-merge prompt to delete source clips
    if (vm.showDeletePromptAfterMerge && vm.postMergeSourceUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.dismissDeletePrompt() },
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Merge complete") },
            text = {
                Text("Delete the ${vm.postMergeSourceUris.size} source clip(s) used for this merge? " +
                     "The merged output is preserved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDel = vm.postMergeSourceUris.toList()
                    vm.dismissDeletePrompt()
                    vm.deleteClips(toDel)
                }) {
                    Text("Delete sources", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDeletePrompt() }) { Text("Keep") }
            }
        )
    }
}

// ============================ Edit screen ============================

@Composable
private fun EditScreen(vm: MainViewModel) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Shared manage bar
        ManageBar(vm = vm)

        Spacer(Modifier.height(8.dp))

        Text(
            if (vm.manageMode) "Select clips to delete, or tap Done to exit manage mode"
            else "Tap a clip to trim (or use Manage to delete clips)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.clips) { clip ->
                ClipRow(
                    clip = clip,
                    clipEdit = vm.getEditForClip(clip.uri),
                    selectionIndex = if (vm.manageMode)
                        vm.selectionOrder.indexOf(clip.uri).takeIf { it >= 0 }
                    else null,
                    manageMode = vm.manageMode,
                    showTrimIcon = !vm.manageMode,
                    onClick = {
                        if (vm.manageMode) vm.toggleSelection(clip.uri)
                        else vm.trimmingClipUri = clip.uri
                    },
                    onPlayClick = { playVideo(context, clip.uri) },
                    onTrimClick = { vm.trimmingClipUri = clip.uri }
                )
            }
        }
    }
}

// ============================ Audio screen ============================

@Composable
private fun AudioScreen(
    vm: MainViewModel,
    musicPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>,
    onStartSaveAs: (List<Uri>) -> Unit,
    onCancelMerge: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Shared manage bar
        ManageBar(vm = vm)

        Spacer(Modifier.height(8.dp))

        Text(
            if (vm.manageMode) "Select clips to delete, or tap Done to exit manage mode"
            else "Pick clip(s), then add background music. Output is a single re-encoded video.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(12.dp))

        if (!vm.manageMode) {
            MusicChip(vm = vm, musicPicker = musicPicker)
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(vm.clips) { clip ->
                ClipRow(
                    clip = clip,
                    clipEdit = vm.getEditForClip(clip.uri),
                    selectionIndex = vm.selectionOrder.indexOf(clip.uri).takeIf { it >= 0 },
                    manageMode = vm.manageMode,
                    showTrimIcon = false,
                    onClick = { vm.toggleSelection(clip.uri) },
                    onPlayClick = { playVideo(context, clip.uri) },
                    onTrimClick = { vm.trimmingClipUri = clip.uri }
                )
            }
        }

        if (!vm.manageMode && vm.selectionOrder.isNotEmpty() && vm.musicUri != null && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onStartSaveAs(vm.selectionOrder) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Produce with music (${vm.selectionOrder.size} clip(s))")
            }
        }

        if (vm.isProcessing) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { vm.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${(vm.progress * 100).toInt()}%", fontSize = 12.sp)
                TextButton(onClick = onCancelMerge) { Text("Cancel") }
            }
        }
    }
}

// ============================ Shared components ============================

@Composable
private fun MusicChip(
    vm: MainViewModel,
    musicPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { musicPicker.launch(arrayOf("audio/*")) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.MusicNote,
            null,
            modifier = Modifier.size(20.dp),
            tint = if (vm.musicUri != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (vm.musicUri != null) {
                Text("Music: ${vm.musicName ?: "audio"}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1)
                Text("Tap to change  •  loops if shorter than video",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Add background music", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Will require re-encoding (slower)", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (vm.musicUri != null) {
            TextButton(
                onClick = { vm.setMusic(null, null) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("Remove", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun ClipRow(
    clip: VideoClip,
    clipEdit: ClipEdit,
    selectionIndex: Int?,
    manageMode: Boolean,
    showTrimIcon: Boolean = false,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onTrimClick: () -> Unit = {}
) {
    val isSelected = selectionIndex != null
    val borderColor = when {
        manageMode && isSelected -> MaterialTheme.colorScheme.error
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val bgColor = when {
        manageMode && isSelected -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPlayClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.PlayCircle,
                "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(clip.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface)
            val trimSuffix = if (clipEdit.hasEdits()) {
                val effDuration = clipEdit.effectiveDurationMs(clip.durationSec * 1000L) / 1000
                val effRanges = clipEdit.effectiveRanges(clip.durationSec * 1000L)
                val segCount = if (effRanges.size > 1) " (${effRanges.size} segments)" else ""
                "  •  trimmed: ${formatDuration(effDuration)}$segCount"
            } else ""
            Text(
                "${formatDuration(clip.durationSec)}  •  ${"%.1f".format(clip.sizeMb)} MB$trimSuffix",
                fontSize = 11.sp,
                color = if (clipEdit.hasEdits()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (showTrimIcon && !manageMode) {
            IconButton(onClick = onTrimClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ContentCut,
                    "Trim",
                    tint = if (clipEdit.hasEdits()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        when {
            manageMode && isSelected -> Icon(
                Icons.Filled.Delete, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            !manageMode && isSelected && selectionIndex != null -> Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("${selectionIndex + 1}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun playVideo(context: android.content.Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Play video with...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context,
            "No video player found. Install a video player from Play Store.",
            Toast.LENGTH_LONG).show()
    }
}
