package com.gbcsync.app.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gbcsync.app.data.CameraType
import com.gbcsync.app.data.DeviceConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    devices: List<DeviceConfig>,
    onDevicesChanged: (List<DeviceConfig>) -> Unit,
    ownedCameras: Set<CameraType>,
    onOwnedCamerasChanged: (Set<CameraType>) -> Unit,
    baseFolder: String,
    onBaseFolderChanged: (String) -> Unit,
    debugLogEnabled: Boolean,
    onDebugLogEnabledChanged: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onDevicesChanged(
                        devices +
                            DeviceConfig(
                                name = "New Device",
                                vendorId = 0,
                                productId = 0,
                                fileFilter = "*",
                            ),
                    )
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Device")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Debug Log", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Show live log on home screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = debugLogEnabled,
                        onCheckedChange = onDebugLogEnabledChanged,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseFolder,
                    onValueChange = onBaseFolderChanged,
                    label = { Text("Save Folder") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Under Downloads/") },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("My Gear", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select the cameras you own",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Helper to find owned version of a camera (with custom prefix) or use default
                fun ownedOrDefault(default: CameraType): CameraType = ownedCameras.find { it.displayName == default.displayName } ?: default

                fun toggleCamera(
                    default: CameraType,
                    checked: Boolean,
                ) {
                    val cam = ownedOrDefault(default)
                    val updated = if (checked) ownedCameras + cam else ownedCameras.filter { it.displayName != default.displayName }.toSet()
                    onOwnedCamerasChanged(updated)
                }

                fun updatePrefix(
                    default: CameraType,
                    newPrefix: String,
                ) {
                    val old = ownedCameras.find { it.displayName == default.displayName } ?: return
                    val updated = ownedCameras - old + old.copy(filePrefix = newPrefix)
                    onOwnedCamerasChanged(updated)
                }

                // Game Boy Camera colors
                Text(
                    "Game Boy Camera",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                CameraType.gbCameraColors.forEach { default ->
                    val isOwned = ownedCameras.any { it.displayName == default.displayName }
                    CameraCheckboxRow(
                        camera = ownedOrDefault(default),
                        isChecked = isOwned,
                        onCheckedChange = { toggleCamera(default, it) },
                        onPrefixChanged =
                            if (isOwned) {
                                { updatePrefix(default, it) }
                            } else {
                                null
                            },
                    )
                }

                // Custom cameras
                val customCameras =
                    ownedCameras.filter {
                        it !in CameraType.builtIn &&
                            it.displayName !in
                            CameraType.builtIn.map { b ->
                                b.displayName
                            }
                    }
                customCameras.forEach { camera ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = true, onCheckedChange = {
                            onOwnedCamerasChanged(ownedCameras - camera)
                        })
                        Text(camera.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = camera.filePrefix,
                            onValueChange = { newPrefix ->
                                onOwnedCamerasChanged(ownedCameras - camera + camera.copy(filePrefix = newPrefix))
                            },
                            label = { Text("Prefix") },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                        )
                        IconButton(onClick = { onOwnedCamerasChanged(ownedCameras - camera) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Add custom camera button
                var showAddDialog by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Custom Camera")
                }
                if (showAddDialog) {
                    var customName by remember { mutableStateOf("") }
                    var customPrefix by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddDialog = false },
                        title = { Text("Add Custom Camera") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = customName,
                                    onValueChange = { customName = it },
                                    label = { Text("Camera name") },
                                    singleLine = true,
                                    placeholder = { Text("e.g. Cam+") },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customPrefix,
                                    onValueChange = { customPrefix = it },
                                    label = { Text("File prefix") },
                                    singleLine = true,
                                    placeholder = { Text("e.g. cam") },
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (customName.isNotBlank() && customPrefix.isNotBlank()) {
                                        onOwnedCamerasChanged(ownedCameras + CameraType(customName.trim(), customPrefix.trim()))
                                        showAddDialog = false
                                    }
                                },
                                enabled = customName.isNotBlank() && customPrefix.isNotBlank(),
                            ) { Text("Add") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // MiniCam
                Text(
                    "MiniCam",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                val miniGbcOwned = ownedCameras.any { it.displayName == CameraType.MINI_CAM_GBC_ROM.displayName }
                CameraCheckboxRow(
                    camera = ownedOrDefault(CameraType.MINI_CAM_GBC_ROM),
                    isChecked = miniGbcOwned,
                    onCheckedChange = { toggleCamera(CameraType.MINI_CAM_GBC_ROM, it) },
                    onPrefixChanged =
                        if (miniGbcOwned) {
                            { updatePrefix(CameraType.MINI_CAM_GBC_ROM, it) }
                        } else {
                            null
                        },
                )
                CameraCheckboxRow(
                    camera = ownedOrDefault(CameraType.MINI_CAM_PHOTO_ROM),
                    isChecked = true,
                    autoDetected = true,
                    onPrefixChanged = { newPrefix ->
                        val old = ownedCameras.find { it.displayName == CameraType.MINI_CAM_PHOTO_ROM.displayName }
                        if (old != null) {
                            onOwnedCamerasChanged(ownedCameras - old + old.copy(filePrefix = newPrefix))
                        } else {
                            onOwnedCamerasChanged(ownedCameras + CameraType.MINI_CAM_PHOTO_ROM.copy(filePrefix = newPrefix))
                        }
                    },
                )

                // PicNRec
                Text(
                    "PicNRec",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                CameraCheckboxRow(
                    camera = ownedOrDefault(CameraType.PIC_N_REC),
                    isChecked = true,
                    autoDetected = true,
                    onPrefixChanged = { newPrefix ->
                        val old = ownedCameras.find { it.displayName == CameraType.PIC_N_REC.displayName }
                        if (old != null) {
                            onOwnedCamerasChanged(ownedCameras - old + old.copy(filePrefix = newPrefix))
                        } else {
                            onOwnedCamerasChanged(ownedCameras + CameraType.PIC_N_REC.copy(filePrefix = newPrefix))
                        }
                    },
                )

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
                    },
                )
            }
        }
    }
}

@Composable
private fun DeviceConfigCard(
    device: DeviceConfig,
    onUpdate: (DeviceConfig) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
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
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = if (device.vendorId != 0) device.vendorId.toString() else "",
                    onValueChange = { onUpdate(device.copy(vendorId = it.toIntOrNull() ?: 0)) },
                    label = { Text("Vendor ID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("0 = any") },
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = if (device.productId != 0) device.productId.toString() else "",
                    onValueChange = { onUpdate(device.copy(productId = it.toIntOrNull() ?: 0)) },
                    label = { Text("Product ID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("0 = any") },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = device.fileFilter,
                onValueChange = { onUpdate(device.copy(fileFilter = it)) },
                label = { Text("File Filter (glob)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. *.sav or *.gb*") },
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = device.destFolder,
                onValueChange = { onUpdate(device.copy(destFolder = it)) },
                label = { Text("Destination Folder") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Default: Downloads/gbc-sync/${device.name}") },
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = device.recursive,
                    onCheckedChange = { onUpdate(device.copy(recursive = it)) },
                )
                Text("Include subfolders")
            }
        }
    }
}

@Composable
private fun CameraCheckboxRow(
    camera: CameraType,
    isChecked: Boolean,
    autoDetected: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onPrefixChanged: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = if (autoDetected) null else onCheckedChange,
            enabled = !autoDetected,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(camera.displayName, style = MaterialTheme.typography.bodyMedium)
            if (autoDetected) {
                Text(
                    "Auto-detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onPrefixChanged != null) {
            OutlinedTextField(
                value = camera.filePrefix,
                onValueChange = onPrefixChanged,
                label = { Text("Prefix") },
                modifier = Modifier.width(80.dp),
                singleLine = true,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            devices =
                listOf(
                    DeviceConfig(name = "Joey Jr", vendorId = 49745, productId = 8224, fileFilter = "*.bmp"),
                    DeviceConfig(name = "2bitBridge", vendorId = 9114, productId = 51966, fileFilter = "*"),
                ),
            onDevicesChanged = {},
            ownedCameras = setOf(CameraType.GB_CAMERA_GREEN),
            onOwnedCamerasChanged = {},
            baseFolder = "gbc-sync",
            onBaseFolderChanged = {},
            debugLogEnabled = true,
            onDebugLogEnabledChanged = {},
            onNavigateBack = {},
        )
    }
}
