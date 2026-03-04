package com.gbcsync.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbcsync.app.SyncState
import com.gbcsync.app.data.AppLog
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
    onOpenGbPrinterWeb: () -> Unit
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection Status
            ConnectionStatusCard(
                connectedDevice, syncState,
                onRetry = onRetrySync,
                onOpenGbPrinterWeb = onOpenGbPrinterWeb,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Tabs
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

@Composable
private fun SyncHistoryTab(syncLog: List<SyncLogEntry>) {
    if (syncLog.isEmpty()) {
        Text(
            text = "No files synced yet. Connect a USB device to start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
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
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp)
    ) {
        items(logLines) { line ->
            val color = when {
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
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(connectedDevice: String?, syncState: SyncState, onRetry: () -> Unit, onOpenGbPrinterWeb: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (syncState.status) {
                SyncState.Status.IDLE -> if (connectedDevice != null)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
                SyncState.Status.CONNECTING -> MaterialTheme.colorScheme.primaryContainer
                SyncState.Status.SYNCING -> MaterialTheme.colorScheme.primaryContainer
                SyncState.Status.DONE -> MaterialTheme.colorScheme.secondaryContainer
                SyncState.Status.ERROR -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        syncState.status == SyncState.Status.ERROR -> Icons.Default.Error
                        syncState.status == SyncState.Status.DONE -> Icons.Default.CheckCircle
                        connectedDevice != null -> Icons.Default.Usb
                        else -> Icons.Default.UsbOff
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (syncState.status) {
                        SyncState.Status.IDLE -> connectedDevice ?: "No device connected"
                        SyncState.Status.CONNECTING -> "Connecting to ${syncState.deviceName}..."
                        SyncState.Status.SYNCING -> "Syncing from ${syncState.deviceName}..."
                        SyncState.Status.DONE -> when {
                            syncState.filesCopied == 0 && syncState.errors == 0 -> "All files up to date"
                            syncState.errors > 0 -> "Copied ${syncState.filesCopied}, ${syncState.errors} failed"
                            else -> "Done! Copied ${syncState.filesCopied} file(s)"
                        }
                        SyncState.Status.ERROR -> "Error: ${syncState.error}"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (syncState.status == SyncState.Status.CONNECTING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (syncState.status == SyncState.Status.SYNCING) {
                Spacer(modifier = Modifier.height(8.dp))
                if (syncState.totalFiles > 0) {
                    LinearProgressIndicator(
                        progress = { syncState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${syncState.filesCopied}/${syncState.totalFiles}: ${syncState.currentFile}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = syncState.currentFile,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (syncState.status == SyncState.Status.ERROR ||
                (syncState.status == SyncState.Status.DONE && syncState.errors > 0)) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry) {
                    Text("Retry Sync")
                }
            }

            if (syncState.status == SyncState.Status.DONE && syncState.filesCopied > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenGbPrinterWeb,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open GB Printer Web")
                }
            }
        }
    }
}

@Composable
private fun SyncLogItem(entry: SyncLogEntry) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${entry.deviceName} - ${formatFileSize(entry.fileSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = dateFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
