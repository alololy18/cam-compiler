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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var folderUri by mutableStateOf<Uri?>(null); private set
    var folderName by mutableStateOf<String?>(null); private set
    var clips by mutableStateOf<List<VideoClip>>(emptyList()); private set
    var selectionOrder by mutableStateOf<List<Uri>>(emptyList()); private set
    var status by mutableStateOf("Pick a folder to begin.")
    var isProcessing by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var lastSortInfo by mutableStateOf<ChronologicalSorter.SortResult?>(null)

    init {
        loadLastFolder()
    }

    private fun loadLastFolder() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val saved = Prefs.getLastFolder(ctx) ?: return@launch
            try {
                val uri = Uri.parse(saved)
                // Verify we still have permission
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

        val (name, found) = withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(ctx, uri)
                ?: return@withContext "Unknown" to emptyList<VideoClip>()
            val videoFiles = tree.listFiles().filter {
                it.isFile && (it.type?.startsWith("video/") == true ||
                    it.name?.lowercase()?.endsWith(".mp4") == true ||
                    it.name?.lowercase()?.endsWith(".mov") == true ||
                    it.name?.lowercase()?.endsWith(".mkv") == true ||
                    it.name?.lowercase()?.endsWith(".avi") == true)
            }
            val list = videoFiles.map { doc ->
                val durationMs = readDurationMs(ctx, doc.uri)
                VideoClip(
                    uri = doc.uri,
                    name = doc.name ?: "unknown",
                    sizeMb = doc.length() / (1024.0 * 1024.0),
                    durationSec = durationMs / 1000,
                    lastModified = doc.lastModified()
                )
            }
            (tree.name ?: "Folder") to list
        }

        folderName = name
        if (found.isEmpty()) {
            status = "No video files found in '$name'."
            return
        }

        // Auto-sort chronologically on load
        val sortResult = ChronologicalSorter.sort(found)
        clips = sortResult.ordered
        lastSortInfo = sortResult
        status = "Found ${found.size} clips in '$name'. ${sortResult.strategy}."
    }

    private fun readDurationMs(ctx: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, uri)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            retriever.release()
            d
        } catch (_: Exception) { 0L }
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

    fun totalSelectedDuration(): Long = clips
        .filter { selectionOrder.contains(it.uri) }
        .sumOf { it.durationSec }

    fun totalAllDuration(): Long = clips.sumOf { it.durationSec }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var mergeService: MergeService? = null

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
                        Toast.makeText(this@MainActivity, "Saved: ${result.outputName}", Toast.LENGTH_LONG).show()
                    } else if (result is MergeEngine.Result.Failure) {
                        Toast.makeText(this@MainActivity, "Failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Sync current state if a merge is already running
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

        // Request notification permission on Android 13+
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
                        onStartMerge = { uris -> startMerge(uris) },
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

    private fun startMerge(uris: List<Uri>) {
        vm.isProcessing = true
        vm.progress = 0f
        vm.status = "Starting..."
        MergeService.start(this, uris)
        // Re-bind so we get listener callbacks for the new service
        bindService(Intent(this, MergeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun cancelMerge() {
        val intent = Intent(this, MergeService::class.java).apply { action = MergeService.ACTION_CANCEL }
        startService(intent)
    }
}

@Composable
fun AppScreen(
    vm: MainViewModel,
    onStartMerge: (List<Uri>) -> Unit,
    onCancelMerge: () -> Unit
) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            vm.setFolder(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
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
        Spacer(Modifier.height(6.dp))
        Text(vm.status, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))

        // Sort warning banner
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

        // Pick folder + clear
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.weight(1f),
                enabled = !vm.isProcessing
            ) { Text(if (vm.folderUri == null) "Pick Folder" else "Change Folder") }
            if (vm.selectionOrder.isNotEmpty()) {
                OutlinedButton(onClick = { vm.clearSelection() }, enabled = !vm.isProcessing) {
                    Text("Clear")
                }
            }
        }

        // Auto-merge all button
        if (vm.clips.isNotEmpty() && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    val uris = vm.clips.map { it.uri }
                    onStartMerge(uris)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Merge ALL ${vm.clips.size} clips chronologically  •  ${formatDuration(vm.totalAllDuration())}")
            }
        }

        // Progress
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

        // Clip list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(vm.clips) { clip ->
                ClipRow(
                    clip = clip,
                    selectionIndex = vm.selectionOrder.indexOf(clip.uri).takeIf { it >= 0 },
                    onClick = { if (!vm.isProcessing) vm.toggleSelection(clip.uri) }
                )
            }
        }

        // Manual merge bar
        if (vm.selectionOrder.isNotEmpty() && !vm.isProcessing) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onStartMerge(vm.selectionOrder) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Merge ${vm.selectionOrder.size} selected  •  ${formatDuration(vm.totalSelectedDuration())}")
            }
        }
    }
}

@Composable
fun ClipRow(clip: VideoClip, selectionIndex: Int?, onClick: () -> Unit) {
    val isSelected = selectionIndex != null
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(clip.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            Text(
                "${formatDuration(clip.durationSec)}  •  ${"%.1f".format(clip.sizeMb)} MB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selectionIndex != null) {
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
