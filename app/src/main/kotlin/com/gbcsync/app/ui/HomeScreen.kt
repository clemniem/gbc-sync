package com.gbcsync.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbcsync.app.SyncState
import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.CameraType
import com.gbcsync.app.data.SyncLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    syncState: SyncState,
    connectedDevice: String?,
    syncLog: List<SyncLogEntry>,
    logLines: List<String>,
    debugLogEnabled: Boolean,
    onRetrySync: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenGbPrinterWeb: () -> Unit,
    onOpenFolder: (String) -> Unit = {},
    onContinueImport: () -> Unit = {},
    onNewImport: () -> Unit = {},
    onCameraChosen: (CameraType) -> Unit = {},
) {
    var selectedTab by remember { mutableIntStateOf(if (debugLogEnabled) 1 else 0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GBC Sync") },
                actions = {
                    if (selectedTab == 1) {
                        IconButton(onClick = { AppLog.clear() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear log")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            val isSyncActive =
                syncState.status == SyncState.Status.SYNCING ||
                    syncState.status == SyncState.Status.CONNECTING

            if (isSyncActive) {
                // Expanded sync animation view
                Spacer(modifier = Modifier.weight(1f))
                SyncAnimation(
                    progress = syncState.progress,
                    isConnecting = syncState.status == SyncState.Status.CONNECTING,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text =
                        when (syncState.status) {
                            SyncState.Status.CONNECTING -> "Connecting to ${syncState.deviceName}..."
                            SyncState.Status.SYNCING -> "Syncing from ${syncState.deviceName}..."
                            else -> ""
                        },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (syncState.status == SyncState.Status.SYNCING && syncState.totalFiles > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${syncState.filesCopied} / ${syncState.totalFiles}",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (syncState.currentFile.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = syncState.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            } else {
                // Normal view: status card + tabs
                ConnectionStatusCard(
                    connectedDevice,
                    syncState,
                    onRetry = onRetrySync,
                    onOpenGbPrinterWeb = onOpenGbPrinterWeb,
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (debugLogEnabled) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                            Text("History", modifier = Modifier.padding(12.dp))
                        }
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                            Text("Live Log", modifier = Modifier.padding(12.dp))
                        }
                    }
                }

                when (selectedTab) {
                    0 -> SyncHistoryTab(syncLog)
                    1 -> if (debugLogEnabled) LiveLogTab(logLines)
                }
            }
        }
    }

    // Import continuation dialog
    syncState.importChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = onNewImport,
            title = { Text("Continue Import?") },
            text = { Text(choice.message) },
            confirmButton = {
                Button(onClick = onContinueImport) {
                    Text(choice.appendLabel)
                }
            },
            dismissButton = {
                Button(
                    onClick = onNewImport,
                    colors = ButtonDefaults.textButtonColors(),
                ) {
                    Text(choice.newLabel)
                }
            },
        )
    }

    // Camera picker dialog
    syncState.cameraChoice?.let { cameras ->
        AlertDialog(
            onDismissRequest = { onCameraChosen(cameras.first()) },
            title = { Text("Select Camera") },
            text = {
                Column {
                    cameras.forEach { camera ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onCameraChosen(camera) }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = false,
                                onClick = { onCameraChosen(camera) },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(camera.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun SyncHistoryTab(syncLog: List<SyncLogEntry>) {
    if (syncLog.isEmpty()) {
        Text(
            text = "No files synced yet. Connect a USB device to start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    } else {
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            items(syncLog) { entry ->
                SyncLogItem(entry)
            }
        }
    }
}

@Composable
private fun LiveLogTab(logLines: List<String>) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new lines arrive
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(8.dp),
    ) {
        items(logLines) { line ->
            val color =
                when {
                    " E " in line -> MaterialTheme.colorScheme.error
                    " W " in line -> MaterialTheme.colorScheme.tertiary
                    " I " in line -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
                lineHeight = 14.sp,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    connectedDevice: String?,
    syncState: SyncState,
    onRetry: () -> Unit,
    onOpenGbPrinterWeb: () -> Unit,
    onOpenFolder: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (syncState.status) {
                        SyncState.Status.IDLE ->
                            if (connectedDevice != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        SyncState.Status.CONNECTING -> MaterialTheme.colorScheme.primaryContainer
                        SyncState.Status.SYNCING -> MaterialTheme.colorScheme.primaryContainer
                        SyncState.Status.DONE -> MaterialTheme.colorScheme.secondaryContainer
                        SyncState.Status.ERROR -> MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector =
                        when {
                            syncState.status == SyncState.Status.ERROR -> Icons.Default.Error
                            syncState.status == SyncState.Status.DONE -> Icons.Default.CheckCircle
                            connectedDevice != null -> Icons.Default.Usb
                            else -> Icons.Default.UsbOff
                        },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (syncState.status) {
                            SyncState.Status.IDLE -> connectedDevice ?: "No device connected"
                            SyncState.Status.CONNECTING -> "Connecting to ${syncState.deviceName}..."
                            SyncState.Status.SYNCING -> "Syncing from ${syncState.deviceName}..."
                            SyncState.Status.DONE ->
                                when {
                                    syncState.filesCopied == 0 && syncState.errors == 0 -> "All files up to date"
                                    syncState.errors > 0 -> "Copied ${syncState.filesCopied}, ${syncState.errors} failed"
                                    else -> "Done! Copied ${syncState.filesCopied} file(s)"
                                }
                            SyncState.Status.ERROR -> "Error: ${syncState.error}"
                        },
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            if (syncState.status == SyncState.Status.CONNECTING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (syncState.currentFile.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = syncState.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (syncState.status == SyncState.Status.SYNCING) {
                Spacer(modifier = Modifier.height(8.dp))
                if (syncState.totalFiles > 0) {
                    LinearProgressIndicator(
                        progress = { syncState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${syncState.filesCopied}/${syncState.totalFiles}: ${syncState.currentFile}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = syncState.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (syncState.status == SyncState.Status.DONE && syncState.safeToDisconnect && connectedDevice != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.UsbOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Safe to disconnect USB cable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }

            if (syncState.status == SyncState.Status.ERROR ||
                (syncState.status == SyncState.Status.DONE && syncState.errors > 0)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry) {
                    Text("Retry Sync")
                }
            }

            if (syncState.status == SyncState.Status.DONE && syncState.filesCopied > 0) {
                if (syncState.targetFolder.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val folderName = syncState.targetFolder.substringAfterLast('/')
                    val statsText =
                        buildString {
                            append("${syncState.filesCopied} file${if (syncState.filesCopied != 1) "s" else ""}")
                            if (syncState.durationMs > 0) append(" in ${formatDuration(syncState.durationMs)}")
                            append(" \u2022 $folderName")
                        }
                    Text(
                        text = statsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (syncState.targetFolder.isNotEmpty()) {
                        Button(
                            onClick = { onOpenFolder(syncState.targetFolder) },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Folder")
                        }
                    }
                    Button(
                        onClick = onOpenGbPrinterWeb,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                            ),
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open GB Printer Web")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncLogItem(entry: SyncLogEntry) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    buildString {
                        append("${entry.filesCopied} file${if (entry.filesCopied != 1) "s" else ""} synced")
                        if (entry.errors > 0) append(", ${entry.errors} failed")
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    buildString {
                        append(entry.deviceName)
                        if (entry.durationMs > 0) append(" \u2022 ${formatDuration(entry.durationMs)}")
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = dateFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(
            syncState =
                SyncState(
                    status = SyncState.Status.SYNCING,
                    deviceName = "Joey Jr",
                    currentFile = "GBC_001.bmp",
                    filesCopied = 3,
                    totalFiles = 12,
                ),
            connectedDevice = "Joey Jr",
            syncLog =
                listOf(
                    SyncLogEntry("Joey Jr", System.currentTimeMillis() - 3600_000, 24, 0, 0, 45_000, "/storage/gbc-sync"),
                    SyncLogEntry("2bitBridge", System.currentTimeMillis() - 86400_000, 8, 1, 0, 12_000, "/storage/gbc-sync"),
                ),
            logLines = listOf("[I] Syncing Joey Jr", "[D] Copying GBC_001.bmp"),
            debugLogEnabled = false,
            onRetrySync = {},
            onNavigateToSettings = {},
            onOpenGbPrinterWeb = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusCardIdlePreview() {
    MaterialTheme {
        ConnectionStatusCard(
            connectedDevice = null,
            syncState = SyncState(),
            onRetry = {},
            onOpenGbPrinterWeb = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusCardDonePreview() {
    MaterialTheme {
        ConnectionStatusCard(
            connectedDevice = "Joey Jr",
            syncState =
                SyncState(
                    status = SyncState.Status.DONE,
                    deviceName = "Joey Jr",
                    filesCopied = 24,
                    totalFiles = 24,
                    targetFolder = "/storage/gbc-sync/joey-jr",
                    durationMs = 45_000,
                    safeToDisconnect = true,
                ),
            onRetry = {},
            onOpenGbPrinterWeb = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusCardErrorPreview() {
    MaterialTheme {
        ConnectionStatusCard(
            connectedDevice = "Joey Jr",
            syncState =
                SyncState(
                    status = SyncState.Status.ERROR,
                    error = "USB connection lost",
                ),
            onRetry = {},
            onOpenGbPrinterWeb = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
