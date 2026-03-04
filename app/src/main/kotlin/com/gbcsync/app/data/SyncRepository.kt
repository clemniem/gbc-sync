package com.gbcsync.app.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gbc_sync")

data class DeviceConfig(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val fileFilter: String = "*",
    val destFolder: String = "",
    val recursive: Boolean = false
)

data class SyncLogEntry(
    val fileName: String,
    val deviceName: String,
    val timestamp: Long,
    val fileSize: Long
)

class SyncRepository(private val context: Context) {

    private val gson = Gson()

    private companion object {
        val DEVICES_KEY = stringPreferencesKey("devices")
        val SYNC_LOG_KEY = stringPreferencesKey("sync_log")
    }

    // --- Device Configs ---

    val deviceConfigs: Flow<List<DeviceConfig>> = context.dataStore.data.map { prefs ->
        val json = prefs[DEVICES_KEY] ?: return@map defaultDevices()
        try {
            gson.fromJson(json, object : TypeToken<List<DeviceConfig>>() {}.type)
        } catch (_: Exception) {
            defaultDevices()
        }
    }

    suspend fun saveDeviceConfigs(devices: List<DeviceConfig>) {
        context.dataStore.edit { prefs ->
            prefs[DEVICES_KEY] = gson.toJson(devices)
        }
    }

    private fun defaultDevices(): List<DeviceConfig> = listOf(
        DeviceConfig(
            name = "JoeyJr",
            vendorId = 49745,
            productId = 8224,
            fileFilter = "*.sav",
            recursive = false
        ),
        DeviceConfig(
            name = "2bitBridge",
            vendorId = 9114,
            productId = 51966,
            fileFilter = "*.png",
            recursive = true
        )
    )

    // --- Sync Log ---

    val syncLog: Flow<List<SyncLogEntry>> = context.dataStore.data.map { prefs ->
        val json = prefs[SYNC_LOG_KEY] ?: return@map emptyList()
        try {
            gson.fromJson(json, object : TypeToken<List<SyncLogEntry>>() {}.type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addSyncLogEntry(entry: SyncLogEntry) {
        context.dataStore.edit { prefs ->
            val existing: List<SyncLogEntry> = try {
                val json = prefs[SYNC_LOG_KEY] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<SyncLogEntry>>() {}.type)
            } catch (_: Exception) {
                emptyList()
            }
            // Keep last 500 entries
            val updated = (listOf(entry) + existing).take(500)
            prefs[SYNC_LOG_KEY] = gson.toJson(updated)
        }
    }

    // --- File Operations ---

    fun getDestDir(deviceConfig: DeviceConfig): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val folder = if (deviceConfig.destFolder.isNotBlank()) {
            deviceConfig.destFolder
        } else {
            "GBCSync/${deviceConfig.name}"
        }
        return File(base, folder).also { it.mkdirs() }
    }

    fun shouldCopyFile(fileName: String, fileSize: Long, destDir: File): Boolean {
        val destFile = File(destDir, fileName)
        if (!destFile.exists()) return true
        // Skip if same name and size (already synced)
        return destFile.length() != fileSize
    }

    fun matchesFilter(fileName: String, filter: String): Boolean {
        if (filter == "*") return true
        // Support simple glob patterns like *.sav, *.gb*
        val regex = filter
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .let { Regex(it, RegexOption.IGNORE_CASE) }
        return regex.matches(fileName)
    }
}
