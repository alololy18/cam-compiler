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
                        vm.refresh()
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
            MaterialTheme(colorScheme = darkColorScheme()) {
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

    // Music picker — choose audio file to mix into the merge
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

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Cam Compiler",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                vm.folderName?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    }
                }
            }
            if (vm.folderUri != null) {
                IconButton(
                    onClick = { vm.refresh() },
                    enabled = !vm.isProcessing,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Refresh, "Refresh folder",
                        modifier = Modifier.size(18.dp))
                }
            }
            if (vm.clips.isNotEmpty()) {
                FilterChip(
                    selected = vm.manageMode,
                    onClick = { vm.toggleManageMode() },
                    label = { Text(if (vm.manageMode) "Done" else "Manage", fontSize = 12.sp) },
                    leadingIcon = if (vm.manageMode) null else {
                        { Icon(Icons.Filled.Tune, null, modifier = Modifier.size(14.dp)) }
                    },
                    enabled = !vm.isProcessing
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(vm.status, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))

        vm.lastSortInfo?.warning?.let { warning ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(warning, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.weight(1f),
                enabled = !vm.isProcessing
            ) { Text(if (vm.folderUri == null) "Pick Folder" else "Change Folder") }

            if (vm.manageMode && vm.selectionOrder.isNotEmpty()) {
                Button(
                    onClick = {
                        pendingDeleteUris = vm.selectionOrder
                        showDeleteConfirm = true
                    },
                    enabled = !vm.isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete (${vm.selectionOrder.size})")
                }
            } else if (vm.selectionOrder.isNotEmpty() && !vm.manageMode) {
                OutlinedButton(
                    onClick = { vm.clearSelection() },
                    enabled = !vm.isProcessing
                ) { Text("Clear") }
            }
        }

        if (!vm.manageMode && vm.clips.isNotEmpty() && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { onStartSaveAs(vm.clips.map { it.uri }) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Merge ALL ${vm.clips.size} clips  •  ${formatDuration(vm.totalAllDuration())}  •  ${"%.0f".format(vm.totalAllSizeMb())} MB")
            }

            // Music picker row
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { musicPicker.launch(arrayOf("audio/*")) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = if (vm.musicUri != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (vm.musicUri != null) {
                        Text(
                            "Music: ${vm.musicName ?: "audio"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        Text(
                            "Tap to change  •  will force re-encoding",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Add background music",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

        if (vm.isProcessing) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { vm.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
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

        // Show a "play last merged" banner if there's a recent output and not currently processing
        if (!vm.isProcessing && vm.lastMergedOutputUri != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    .clickable { vm.lastMergedOutputUri?.let { playVideo(context, it) } }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PlayCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Tap to play the merged video",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TextButton(
                    onClick = { vm.lastMergedOutputUri = null },
                    contentPadding = PaddingValues(8.dp, 0.dp)
                ) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (vm.manageMode && !vm.isProcessing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Tune, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(6.dp))
                Text("Manage: tap to mark for deletion",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.selectAllInOrder() }, contentPadding = PaddingValues(8.dp, 0.dp)) {
                    Text("Select all", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
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
                    onClick = { if (!vm.isProcessing) vm.toggleSelection(clip.uri) },
                    onPlayClick = { playVideo(context, clip.uri) },
                    onTrimClick = { vm.trimmingClipUri = clip.uri }
                )
            }
        }

        if (!vm.manageMode && vm.selectionOrder.isNotEmpty() && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onStartSaveAs(vm.selectionOrder) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Merge ${vm.selectionOrder.size} selected  •  ${formatDuration(vm.totalSelectedDuration())}  •  ${"%.0f".format(vm.totalSelectedSizeMb())} MB")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${pendingDeleteUris.size} clips?") },
            text = { Text("This will permanently delete the selected clips from the folder. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = pendingDeleteUris
                        showDeleteConfirm = false
                        vm.deleteClips(toDelete) {
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (vm.showDeletePromptAfterMerge) {
        AlertDialog(
            onDismissRequest = {
                vm.showDeletePromptAfterMerge = false
                vm.postMergeSourceUris = emptyList()
            },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Delete source clips?") },
            text = {
                Text("The merged video has been saved. Delete the ${vm.postMergeSourceUris.size} source clips from the folder?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = vm.postMergeSourceUris
                        vm.showDeletePromptAfterMerge = false
                        vm.postMergeSourceUris = emptyList()
                        vm.deleteClips(toDelete) {
                            Toast.makeText(context, "Source clips deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Delete sources", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.showDeletePromptAfterMerge = false
                    vm.postMergeSourceUris = emptyList()
                }) { Text("Keep them") }
            }
        )
    }

    if (vm.showExclusionPrompt && vm.pendingExclusions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.ignoreExcludedFiles() },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("${vm.pendingExclusions.size} file(s) need review") },
            text = {
                Column {
                    Text(
                        "These files have video extensions but couldn't be validated (no readable video track). What would you like to do?",
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
                                Text(
                                    ex.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Text(
                                    "${"%.1f".format(ex.sizeMb)} MB  •  ${ex.reason}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Including unverified files may cause the merge to fail when it reaches them.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.ignoreExcludedFiles() }) {
                    Text("Ignore these")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.includeExcludedFiles() }) {
                    Text("Include anyway")
                }
            }
        )
    }

    if (vm.showMismatchDialog && vm.pendingMismatches.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { onCancelMismatch() },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("Clips don't match") },
            text = {
                Column {
                    Text(
                        "${vm.pendingMismatches.size} clip(s) differ from the first clip's format. " +
                        "These need to be re-encoded to merge.",
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
                        items(vm.pendingMismatches) { mismatch ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    mismatch.clipName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                mismatch.differences.forEach { diff ->
                                    Text(
                                        "  • $diff",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Re-encoding takes longer and slightly reduces quality. " +
                        "Stream copy (fast mode) isn't possible for these clips.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmReencode() }) {
                    Text("Re-encode and merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { onCancelMismatch() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Trim editor — opens when a clip's trim button is tapped
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
            // Clip disappeared (deleted/refreshed) — close the dialog
            vm.trimmingClipUri = null
        }
    }
}

@Composable
fun ClipRow(
    clip: VideoClip,
    clipEdit: ClipEdit,
    selectionIndex: Int?,
    manageMode: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onTrimClick: () -> Unit
) {
    val isSelected = selectionIndex != null
    val borderColor = when {
        manageMode && isSelected -> MaterialTheme.colorScheme.error
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val bgColor = when {
        manageMode && isSelected -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
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
        // Play icon — always shown, always clickable (regardless of mode).
        // Tapping it opens the clip in the system video player rather than selecting.
        IconButton(
            onClick = onPlayClick,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Play in system video player",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(clip.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
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
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Trim button - shown when not in manage mode
        if (!manageMode) {
            IconButton(
                onClick = onTrimClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.ContentCut,
                    contentDescription = "Trim clip",
                    tint = if (clipEdit.hasEdits()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        // Right-side indicator: depends on mode + selection
        when {
            manageMode && isSelected -> {
                Icon(
                    Icons.Filled.Delete,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
            !manageMode && isSelected && selectionIndex != null -> {
                Box(
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${selectionIndex + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
            else -> {
                // Empty space placeholder to keep layout consistent
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

/**
 * Open the given video URI in the system's default video player.
 * Uses ACTION_VIEW; Android shows a chooser if multiple players are installed.
 */
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
        Toast.makeText(
            context,
            "No video player found. Install a video player from Play Store.",
            Toast.LENGTH_LONG
        ).show()
    }
}
