package com.gbcsync.app.gifmaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifMakerScreen(
    syncFolderPath: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Request READ_MEDIA_IMAGES permission
    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    // Find all sync folders that contain images
    val allSyncFolders = remember(syncFolderPath, hasPermission) {
        if (!hasPermission) return@remember emptyList<File>()
        if (syncFolderPath.isNotEmpty()) {
            listOf(File(syncFolderPath))
        } else {
            // Scan Downloads for gbc-sync folders with images
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val base = File(downloads, "gbc-sync")
            if (!base.isDirectory) return@remember emptyList<File>()
            base.walkTopDown()
                .maxDepth(3)
                .filter { it.isDirectory && (File(it, "DCIM").isDirectory || it.listFiles { f -> f.extension.equals("png", ignoreCase = true) }?.isNotEmpty() == true) }
                .sortedByDescending { it.name }
                .toList()
        }
    }

    var syncFolder by remember(allSyncFolders) { mutableStateOf(allSyncFolders.firstOrNull()) }

    // Discover DCIM subfolders within selected sync folder
    val subfolders = remember(syncFolder) {
        val folder = syncFolder ?: return@remember emptyList<File>()
        val dcim = File(folder, "DCIM")
        if (dcim.isDirectory) {
            dcim.listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            folder.listFiles { f -> f.isDirectory }
                ?.filter { dir -> dir.listFiles { f -> f.extension.equals("png", ignoreCase = true) }?.isNotEmpty() == true }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    var selectedFolder by remember { mutableStateOf(subfolders.firstOrNull()) }
    var sequences by remember { mutableStateOf<List<ImageSequence>>(emptyList()) }
    var detecting by remember { mutableStateOf(false) }
    var selectedSequence by remember { mutableStateOf<ImageSequence?>(null) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var fps by remember { mutableFloatStateOf(3f) }
    var scale by remember { mutableIntStateOf(2) }
    var selectedPalette by remember { mutableStateOf(GbPalette.ORIGINAL) }
    var excludedFrames by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loopResult by remember { mutableStateOf<LoopResult?>(null) }
    var findingLoop by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<List<File>>(emptyList()) }
    var exporting by remember { mutableStateOf(false) }
    var exportedFile by remember { mutableStateOf<File?>(null) }

    // Handle Android back button
    BackHandler(enabled = selectedSequence != null || selectionMode) {
        when {
            selectedSequence != null -> {
                selectedSequence = null
                excludedFrames = emptySet()
                loopResult = null
                exportedFile = null
            }
            selectionMode -> {
                selectionMode = false
                selectedIndices = emptySet()
            }
        }
    }

    // Detect sequences when folder changes
    LaunchedEffect(selectedFolder) {
        val folder = selectedFolder ?: return@LaunchedEffect
        detecting = true
        selectedSequence = null
        selectionMode = false
        selectedIndices = emptySet()
        exportedFile = null
        sequences = SequenceDetector().detectSequences(folder)
        detecting = false
        if (sequences.size == 1) selectedSequence = sequences.first()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIndices.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIndices = emptySet()
                        }) {
                            Icon(Icons.Default.Close, "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            deleteTarget = selectedIndices.sorted().flatMap { sequences[it].files }
                            showDeleteConfirm = true
                        }) {
                            Icon(Icons.Default.Delete, "Delete selected")
                        }
                        if (selectedIndices.size >= 2) {
                            IconButton(onClick = {
                                val combined = selectedIndices.sorted().flatMap { sequences[it].files }
                                val combinedSequence = ImageSequence(
                                    files = combined,
                                    firstFrameName = combined.first().nameWithoutExtension,
                                    lastFrameName = combined.last().nameWithoutExtension,
                                )
                                selectionMode = false
                                selectedIndices = emptySet()
                                selectedSequence = combinedSequence
                                exportedFile = null
                            }) {
                                Icon(Icons.Default.Merge, "Combine sequences")
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Make GIF") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Sync folder picker (when browsing all folders)
            if (allSyncFolders.size > 1 && selectedSequence == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allSyncFolders.forEach { folder ->
                        FilterChip(
                            selected = folder == syncFolder,
                            onClick = { syncFolder = folder },
                            label = { Text(folder.name) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (syncFolder == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No sync folders with images found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // Subfolder picker
            if (subfolders.size > 1 && selectedSequence == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    subfolders.forEach { folder ->
                        FilterChip(
                            selected = folder == selectedFolder,
                            onClick = { selectedFolder = folder },
                            label = { Text(folder.name) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (detecting) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Detecting sequences...")
                    }
                }
            } else if (selectedSequence != null) {
                val activeFrames = remember(selectedSequence, excludedFrames) {
                    selectedSequence!!.files.filterIndexed { i, _ -> i !in excludedFrames }
                }
                SequenceDetail(
                    sequence = selectedSequence!!,
                    excludedFrames = excludedFrames,
                    activeFrameCount = activeFrames.size,
                    fps = fps,
                    scale = scale,
                    palette = selectedPalette,
                    exporting = exporting,
                    exportedFile = exportedFile,
                    onFpsChanged = { fps = it },
                    onScaleChanged = { scale = it },
                    onPaletteChanged = { selectedPalette = it },
                    onToggleFrame = { index ->
                        excludedFrames = if (index in excludedFrames) {
                            excludedFrames - index
                        } else {
                            excludedFrames + index
                        }
                        loopResult = null
                        exportedFile = null
                    },
                    onFindLoop = {
                        scope.launch {
                            findingLoop = true
                            val result = SequenceDetector().findLoopPoint(
                                selectedSequence!!.files,
                                excludedFrames,
                            )
                            if (result != null) {
                                loopResult = result
                                // Exclude all frames after the loop point
                                val newExcluded = excludedFrames.toMutableSet()
                                for (i in (result.endIndex + 1) until selectedSequence!!.files.size) {
                                    newExcluded.add(i)
                                }
                                excludedFrames = newExcluded
                                exportedFile = null
                            }
                            findingLoop = false
                        }
                    },
                    loopResult = loopResult,
                    findingLoop = findingLoop,
                    onExport = {
                        scope.launch {
                            exporting = true
                            val seq = ImageSequence(
                                files = activeFrames,
                                firstFrameName = activeFrames.first().nameWithoutExtension,
                                lastFrameName = activeFrames.last().nameWithoutExtension,
                            )
                            exportedFile = exportGif(syncFolder!!, seq, fps, scale, selectedPalette)
                            exporting = false
                        }
                    },
                    onShare = { file ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/gif"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share GIF"))
                    },
                    onBack = {
                        selectedSequence = null
                        excludedFrames = emptySet()
                        loopResult = null
                        exportedFile = null
                    },
                    showBackButton = sequences.size > 1,
                )
            } else if (sequences.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No video sequences detected in this folder.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Sequence list
                Text(
                    if (selectionMode) {
                        "Long-press to select sequences to combine"
                    } else {
                        "${sequences.size} sequence${if (sequences.size != 1) "s" else ""} detected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(sequences) { index, seq ->
                        SequenceCard(
                            sequence = seq,
                            selected = index in selectedIndices,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    selectedIndices = if (index in selectedIndices) {
                                        val updated = selectedIndices - index
                                        if (updated.isEmpty()) {
                                            selectionMode = false
                                        }
                                        updated
                                    } else {
                                        selectedIndices + index
                                    }
                                } else {
                                    selectedSequence = seq
                                    exportedFile = null
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIndices = setOf(index)
                                }
                            },
                        )
                    }
                }

                // Action buttons when in selection mode
                if (selectionMode && selectedIndices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val totalFrames = selectedIndices.sorted().sumOf { sequences[it].files.size }
                    Button(
                        onClick = {
                            deleteTarget = selectedIndices.sorted().flatMap { sequences[it].files }
                            showDeleteConfirm = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete ${selectedIndices.size} sequence${if (selectedIndices.size != 1) "s" else ""} ($totalFrames frames)")
                    }
                    if (selectedIndices.size >= 2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val combined = selectedIndices.sorted().flatMap { sequences[it].files }
                                val combinedSequence = ImageSequence(
                                    files = combined,
                                    firstFrameName = combined.first().nameWithoutExtension,
                                    lastFrameName = combined.last().nameWithoutExtension,
                                )
                                selectionMode = false
                                selectedIndices = emptySet()
                                selectedSequence = combinedSequence
                                exportedFile = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Combine ${selectedIndices.size} sequences ($totalFrames frames)")
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm && deleteTarget.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                deleteTarget = emptyList()
            },
            title = { Text("Delete Files?") },
            text = {
                Text("Permanently delete ${deleteTarget.size} file${if (deleteTarget.size != 1) "s" else ""} from the phone? This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            var deleted = 0
                            for (file in deleteTarget) {
                                if (file.delete()) deleted++
                            }
                            withContext(Dispatchers.Main) {
                                showDeleteConfirm = false
                                deleteTarget = emptyList()
                                selectionMode = false
                                selectedIndices = emptySet()
                                selectedSequence = null
                                excludedFrames = emptySet()
                                exportedFile = null
                                // Re-detect sequences
                                val folder = selectedFolder
                                if (folder != null) {
                                    detecting = true
                                    sequences = SequenceDetector().detectSequences(folder)
                                    detecting = false
                                    if (sequences.size == 1) selectedSequence = sequences.first()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        deleteTarget = emptyList()
                    },
                    colors = ButtonDefaults.textButtonColors(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SequenceCard(
    sequence: ImageSequence,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail = remember(sequence) {
        BitmapFactory.decodeFile(sequence.files.first().absolutePath)
    }

    val borderModifier = if (selected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
    } else {
        Modifier
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = GifEncoder.scaleNearest(thumbnail, 2).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${sequence.files.size} frames",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${sequence.firstFrameName} - ${sequence.lastFrameName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "~${String.format("%.1f", sequence.files.size / 3f)}s at 3fps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (!selectionMode) {
                Icon(
                    Icons.Default.Gif,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SequenceDetail(
    sequence: ImageSequence,
    excludedFrames: Set<Int>,
    activeFrameCount: Int,
    fps: Float,
    scale: Int,
    palette: GbPalette,
    exporting: Boolean,
    exportedFile: File?,
    onFpsChanged: (Float) -> Unit,
    onScaleChanged: (Int) -> Unit,
    onPaletteChanged: (GbPalette) -> Unit,
    onToggleFrame: (Int) -> Unit,
    onFindLoop: () -> Unit,
    loopResult: LoopResult?,
    findingLoop: Boolean,
    onExport: () -> Unit,
    onShare: (File) -> Unit,
    onBack: () -> Unit,
    showBackButton: Boolean,
) {
    // Only cycle through active (non-excluded) frames
    val activeIndices = remember(sequence, excludedFrames) {
        sequence.files.indices.filter { it !in excludedFrames }
    }
    var activePos by remember(sequence, excludedFrames) { mutableIntStateOf(0) }
    val frameIndex = activeIndices.getOrElse(activePos % activeIndices.size.coerceAtLeast(1)) { 0 }
    var palettePickerExpanded by remember { mutableStateOf(false) }
    val currentBitmap = remember(sequence, frameIndex, palette) {
        val raw = BitmapFactory.decodeFile(sequence.files[frameIndex].absolutePath) ?: return@remember null
        if (palette.colors.isEmpty()) raw else GifEncoder.remapBitmap(raw, palette.colors)
    }

    LaunchedEffect(sequence, fps, excludedFrames) {
        if (activeIndices.isEmpty()) return@LaunchedEffect
        while (true) {
            delay((1000f / fps).toLong())
            activePos = (activePos + 1) % activeIndices.size
        }
    }

    val filmstripState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (showBackButton) {
            Text(
                "< All sequences",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(bottom = 8.dp),
            )
        }

        // Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            if (currentBitmap != null && activeIndices.isNotEmpty()) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "Frame ${frameIndex + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(128f / 112f),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            } else {
                Text("All frames excluded", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "$activeFrameCount / ${sequence.files.size} frames",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // Filmstrip — tap to exclude/include frames
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            state = filmstripState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(sequence.files.size) { i ->
                val excluded = i in excludedFrames
                val isCurrentFrame = i == frameIndex
                val thumb = remember(sequence, i) {
                    BitmapFactory.decodeFile(sequence.files[i].absolutePath)
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .then(
                            if (isCurrentFrame && !excluded) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onToggleFrame(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "Frame ${i + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.None,
                            alpha = if (excluded) 0.25f else 1f,
                        )
                    }
                    if (excluded) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Excluded",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // Find Loop button
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = loopResult != null,
                onClick = onFindLoop,
                enabled = !findingLoop && activeIndices.size >= 5,
                label = {
                    if (findingLoop) {
                        Text("Finding loop...")
                    } else if (loopResult != null) {
                        Text("Loop: ${loopResult.matchPercent}% match")
                    } else {
                        Text("Find Loop")
                    }
                },
                leadingIcon = {
                    Icon(Icons.Default.Loop, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // FPS slider
        Text("Speed: ${fps.roundToInt()} fps", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = fps,
            onValueChange = onFpsChanged,
            valueRange = 1f..12f,
            steps = 10,
        )

        // Scale selector
        Text("Scale", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4).forEach { s ->
                FilterChip(
                    selected = scale == s,
                    onClick = { onScaleChanged(s) },
                    label = { Text("${s}x (${128 * s}x${112 * s})") },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Palette selector
        Text("Palette", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        FilterChip(
            selected = true,
            onClick = { palettePickerExpanded = !palettePickerExpanded },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (palette.colors.isNotEmpty()) {
                        PaletteSwatches(palette.colors)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(palette.name)
                }
            },
        )
        if (palettePickerExpanded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                items(GbPalette.ALL.size) { i ->
                    val p = GbPalette.ALL[i]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPaletteChanged(p)
                                palettePickerExpanded = false
                            }
                            .background(
                                if (p.shortName == palette.shortName) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (p.colors.isNotEmpty()) {
                            PaletteSwatches(p.colors)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Export / Share
        if (exportedFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onShare(exportedFile) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share GIF")
                }
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Gif, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-export")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                exportedFile.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Exporting...")
                } else {
                    Icon(Icons.Default.Gif, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export GIF")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PaletteSwatches(colors: List<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(androidx.compose.ui.graphics.Color(color)),
            )
        }
    }
}

private suspend fun exportGif(
    syncFolder: File,
    sequence: ImageSequence,
    fps: Float,
    scale: Int,
    palette: GbPalette = GbPalette.ORIGINAL,
): File = withContext(Dispatchers.IO) {
    val gifsDir = File(syncFolder, "gifs")
    gifsDir.mkdirs()

    val paletteSuffix = if (palette.colors.isNotEmpty()) "_${palette.shortName}" else ""
    val fileName = "sequence_${sequence.firstFrameName}-${sequence.lastFrameName}_${fps.roundToInt()}fps${paletteSuffix}.gif"
    val outFile = File(gifsDir, fileName)
    val delayCs = (100f / fps).roundToInt()

    // Determine GIF palette: remap first frame or extract from original
    val firstBitmap = BitmapFactory.decodeFile(sequence.files.first().absolutePath)
    val firstRemapped = if (palette.colors.isNotEmpty()) {
        GifEncoder.remapBitmap(firstBitmap, palette.colors)
    } else {
        firstBitmap
    }
    val gifPalette = GifEncoder.extractPalette(firstRemapped)
    val w = firstBitmap.width * scale
    val h = firstBitmap.height * scale
    if (firstRemapped !== firstBitmap) firstRemapped.recycle()
    firstBitmap.recycle()

    FileOutputStream(outFile).use { fos ->
        val encoder = GifEncoder(fos)
        encoder.start(w, h, gifPalette)

        for (file in sequence.files) {
            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            if (palette.colors.isNotEmpty()) {
                val remapped = GifEncoder.remapBitmap(bitmap, palette.colors)
                bitmap.recycle()
                bitmap = remapped
            }
            val scaled = GifEncoder.scaleNearest(bitmap, scale)
            encoder.addFrame(scaled, delayCs)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }

        encoder.finish()
    }

    outFile
}
