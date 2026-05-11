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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
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

    // Files that look like videos by extension but failed validation.
    // Held pending user decision to ignore or include them anyway.
    data class ExcludedFile(val uri: Uri, val name: String, val sizeMb: Double, val reason: String)
    var pendingExclusions by mutableStateOf<List<ExcludedFile>>(emptyList())
    var showExclusionPrompt by mutableStateOf(false)

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

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destUri ->
        if (destUri != null && pendingMergeUris.isNotEmpty()) {
            startMerge(pendingMergeUris, destUri)
        }
        pendingMergeUris = emptyList()
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
                        onCancelMerge = { cancelMerge() }
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
        pendingMergeUris = uris
        val defaultName = "vlog_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        saveAsLauncher.launch(defaultName)
    }

    private fun startMerge(uris: List<Uri>, outputUri: Uri) {
        vm.isProcessing = true
        vm.progress = 0f
        vm.status = "Starting..."
        MergeService.start(this, uris, outputUri)
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
    onCancelMerge: () -> Unit
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
                    selectionIndex = vm.selectionOrder.indexOf(clip.uri).takeIf { it >= 0 },
                    manageMode = vm.manageMode,
                    onClick = { if (!vm.isProcessing) vm.toggleSelection(clip.uri) }
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
}

@Composable
fun ClipRow(clip: VideoClip, selectionIndex: Int?, manageMode: Boolean, onClick: () -> Unit) {
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when {
            manageMode && isSelected -> Icons.Filled.Delete
            isSelected -> Icons.Filled.CheckCircle
            else -> Icons.Filled.PlayCircle
        }
        val iconTint = when {
            manageMode && isSelected -> MaterialTheme.colorScheme.error
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(clip.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            Text(
                "${formatDuration(clip.durationSec)}  •  ${"%.1f".format(clip.sizeMb)} MB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selectionIndex != null && !manageMode) {
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
    }
}
