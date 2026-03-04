package com.gbcsync.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gbcsync.app.data.DeviceConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    devices: List<DeviceConfig>,
    onDevicesChanged: (List<DeviceConfig>) -> Unit,
    debugLogEnabled: Boolean,
    onDebugLogEnabledChanged: (Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onDevicesChanged(
                        devices + DeviceConfig(
                            name = "New Device",
                            vendorId = 0,
                            productId = 0,
                            fileFilter = "*"
                        )
                    )
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Device")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Debug Log", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Show live log on home screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = debugLogEnabled,
                        onCheckedChange = onDebugLogEnabledChanged
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(devices) { index, device ->
                DeviceConfigCard(
                    device = device,
                    onUpdate = { updated ->
                        onDevicesChanged(devices.toMutableList().apply { set(index, updated) })
                    },
                    onDelete = {
                        onDevicesChanged(devices.toMutableList().apply { removeAt(index) })
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceConfigCard(
    device: DeviceConfig,
    onUpdate: (DeviceConfig) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            OutlinedTextField(
                value = device.name,
                onValueChange = { onUpdate(device.copy(name = it)) },
                label = { Text("Device Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = if (device.vendorId != 0) device.vendorId.toString() else "",
                    onValueChange = { onUpdate(device.copy(vendorId = it.toIntOrNull() ?: 0)) },
                    label = { Text("Vendor ID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("0 = any") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = if (device.productId != 0) device.productId.toString() else "",
                    onValueChange = { onUpdate(device.copy(productId = it.toIntOrNull() ?: 0)) },
                    label = { Text("Product ID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("0 = any") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = device.fileFilter,
                onValueChange = { onUpdate(device.copy(fileFilter = it)) },
                label = { Text("File Filter (glob)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. *.sav or *.gb*") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = device.destFolder,
                onValueChange = { onUpdate(device.copy(destFolder = it)) },
                label = { Text("Destination Folder") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Default: Documents/GBCSync/${device.name}") }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = device.recursive,
                    onCheckedChange = { onUpdate(device.copy(recursive = it)) }
                )
                Text("Include subfolders")
            }
        }
    }
}
