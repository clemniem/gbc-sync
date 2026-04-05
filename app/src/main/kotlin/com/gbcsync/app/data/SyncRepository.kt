package com.gbcsync.app.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gbc_sync")

/**
 * Represents a camera that can be selected during sync.
 * @param displayName shown in UI
 * @param filePrefix prepended to synced file names
 */
data class CameraType(
    val displayName: String,
    val filePrefix: String,
) {
    companion object {
        // --- Game Boy Camera colors ---
        val GB_CAMERA_GREEN = CameraType("Game Boy Camera (Green)", "grn")
        val GB_CAMERA_YELLOW = CameraType("Game Boy Camera (Yellow)", "ylw")
        val GB_CAMERA_RED = CameraType("Game Boy Camera (Red)", "red")
        val GB_CAMERA_BLUE = CameraType("Game Boy Camera (Blue)", "blu")
        val GB_CAMERA_ATOMIC_PURPLE = CameraType("Game Boy Camera (Atomic Purple)", "pur")

        /** All standard GB Camera color variants */
        val gbCameraColors = listOf(GB_CAMERA_GREEN, GB_CAMERA_YELLOW, GB_CAMERA_RED, GB_CAMERA_BLUE, GB_CAMERA_ATOMIC_PURPLE)

        // --- MiniCam ---
        val MINI_CAM_PHOTO_ROM = CameraType("MiniCam (PhotoRom)", "mip")
        val MINI_CAM_GBC_ROM = CameraType("MiniCam (GBCRom)", "mis")

        // --- PicNRec ---
        val PIC_N_REC = CameraType("PicNRec", "pic")

        /** Cameras that are auto-detected and should not appear in the picker */
        val autoDetected = setOf(PIC_N_REC, MINI_CAM_PHOTO_ROM)

        /** All built-in camera types (for Settings display) */
        val builtIn = gbCameraColors + listOf(MINI_CAM_GBC_ROM, MINI_CAM_PHOTO_ROM, PIC_N_REC)

        /** Create a custom camera entry */
        fun custom(name: String): CameraType = CameraType(name, name.replace(" ", ""))
    }
}

data class DeviceConfig(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val fileFilter: String = "*",
    val destFolder: String = "",
    val recursive: Boolean = false,
)

data class SyncLogEntry(
    val deviceName: String,
    val timestamp: Long,
    val filesCopied: Int,
    val errors: Int,
    val totalBytes: Long,
    val durationMs: Long,
    val targetFolder: String,
)

class SyncRepository(
    private val context: Context,
) {
    private val gson = Gson()

    private companion object {
        val DEVICES_KEY = stringPreferencesKey("devices")
        val SYNC_LOG_KEY = stringPreferencesKey("sync_log")
        val DEBUG_LOG_KEY = booleanPreferencesKey("debug_log_enabled")
        val OWNED_CAMERAS_KEY = stringPreferencesKey("owned_cameras")
        val BASE_FOLDER_KEY = stringPreferencesKey("base_folder")
        val SYNCED_FILES_KEY = stringPreferencesKey("synced_files")
        val NEXT_SYNC_NUMBER_KEY = stringPreferencesKey("next_sync_number")
        val CUSTOM_PALETTES_KEY = stringPreferencesKey("custom_palettes")
        const val DEFAULT_BASE_FOLDER = "gbc-sync"
    }

    // --- Device Configs ---

    val deviceConfigs: Flow<List<DeviceConfig>> =
        context.dataStore.data.map { prefs ->
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

    private fun defaultDevices(): List<DeviceConfig> =
        listOf(
            DeviceConfig(
                name = "JoeyJr",
                vendorId = 49745,
                productId = 8224,
                fileFilter = "*.sav",
                recursive = false,
            ),
            DeviceConfig(
                name = "PicNRec",
                vendorId = 9114,
                productId = 51966,
                fileFilter = "*.png",
                recursive = true,
            ),
        )

    // --- Owned Cameras ---

    val ownedCameras: Flow<Set<CameraType>> =
        context.dataStore.data.map { prefs ->
            val json = prefs[OWNED_CAMERAS_KEY] ?: return@map emptySet()
            try {
                gson.fromJson<Set<CameraType>>(json, object : TypeToken<Set<CameraType>>() {}.type)
            } catch (_: Exception) {
                emptySet()
            }
        }

    suspend fun saveOwnedCameras(cameras: Set<CameraType>) {
        context.dataStore.edit { prefs ->
            prefs[OWNED_CAMERAS_KEY] = gson.toJson(cameras)
        }
    }

    /** Returns cameras eligible for the JoeyJr picker (owned minus auto-detected) */
    fun pickerCameras(owned: Set<CameraType>): List<CameraType> =
        owned.filter { it !in CameraType.autoDetected }.sortedBy {
            val builtInIndex = CameraType.builtIn.indexOf(it)
            if (builtInIndex >= 0) builtInIndex else Int.MAX_VALUE
        }

    // --- Base Folder ---

    val baseFolder: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[BASE_FOLDER_KEY] ?: DEFAULT_BASE_FOLDER
        }

    suspend fun setBaseFolder(folder: String) {
        context.dataStore.edit { prefs ->
            prefs[BASE_FOLDER_KEY] = folder
        }
    }

    // --- Debug Logging ---

    val debugLogEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[DEBUG_LOG_KEY] ?: true
        }

    suspend fun setDebugLogEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DEBUG_LOG_KEY] = enabled
        }
    }

    // --- Sync Log ---

    val syncLog: Flow<List<SyncLogEntry>> =
        context.dataStore.data.map { prefs ->
            val json = prefs[SYNC_LOG_KEY] ?: return@map emptyList()
            try {
                gson.fromJson(json, object : TypeToken<List<SyncLogEntry>>() {}.type)
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun addSyncLogEntry(entry: SyncLogEntry) {
        context.dataStore.edit { prefs ->
            val existing: List<SyncLogEntry> =
                try {
                    val json = prefs[SYNC_LOG_KEY] ?: "[]"
                    gson.fromJson(json, object : TypeToken<List<SyncLogEntry>>() {}.type)
                } catch (_: Exception) {
                    emptyList()
                }
            val updated = (listOf(entry) + existing).take(100)
            prefs[SYNC_LOG_KEY] = gson.toJson(updated)
        }
    }

    // --- Synced Files History ---
    // Persists device file paths that have been successfully synced, keyed by device name.
    // Format: JSON map of { "deviceName": ["path1", "path2", ...] }

    suspend fun getSyncedFiles(deviceName: String): Set<String> {
        val prefs = context.dataStore.data.first()
        val json = prefs[SYNCED_FILES_KEY] ?: return emptySet()
        return try {
            val map: Map<String, List<String>> = gson.fromJson(json, object : TypeToken<Map<String, List<String>>>() {}.type)
            map[deviceName]?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun syncedFilesFlow(deviceName: String): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val json = prefs[SYNCED_FILES_KEY] ?: return@map emptySet()
            try {
                val map: Map<String, List<String>> = gson.fromJson(json, object : TypeToken<Map<String, List<String>>>() {}.type)
                map[deviceName]?.toSet() ?: emptySet()
            } catch (_: Exception) {
                emptySet()
            }
        }

    suspend fun addSyncedFiles(deviceName: String, paths: Set<String>) {
        context.dataStore.edit { prefs ->
            val existing: MutableMap<String, List<String>> =
                try {
                    val json = prefs[SYNCED_FILES_KEY] ?: "{}"
                    gson.fromJson(json, object : TypeToken<MutableMap<String, List<String>>>() {}.type)
                } catch (_: Exception) {
                    mutableMapOf()
                }
            val current = (existing[deviceName] ?: emptyList()).toMutableSet()
            current.addAll(paths)
            existing[deviceName] = current.toList()
            prefs[SYNCED_FILES_KEY] = gson.toJson(existing)
        }
    }

    suspend fun clearSyncedFiles(deviceName: String) {
        context.dataStore.edit { prefs ->
            val existing: MutableMap<String, List<String>> =
                try {
                    val json = prefs[SYNCED_FILES_KEY] ?: "{}"
                    gson.fromJson(json, object : TypeToken<MutableMap<String, List<String>>>() {}.type)
                } catch (_: Exception) {
                    mutableMapOf()
                }
            existing.remove(deviceName)
            prefs[SYNCED_FILES_KEY] = gson.toJson(existing)
        }
    }

    // --- Next Sync Number ---
    // Per-device configurable next sync folder number.

    fun nextSyncNumber(deviceName: String): Flow<Int> =
        context.dataStore.data.map { prefs ->
            val json = prefs[NEXT_SYNC_NUMBER_KEY] ?: return@map 0
            try {
                val map: Map<String, Int> = gson.fromJson(json, object : TypeToken<Map<String, Int>>() {}.type)
                map[deviceName] ?: 0
            } catch (_: Exception) {
                0
            }
        }

    suspend fun setNextSyncNumber(deviceName: String, number: Int) {
        context.dataStore.edit { prefs ->
            val existing: MutableMap<String, Int> =
                try {
                    val json = prefs[NEXT_SYNC_NUMBER_KEY] ?: "{}"
                    gson.fromJson(json, object : TypeToken<MutableMap<String, Int>>() {}.type)
                } catch (_: Exception) {
                    mutableMapOf()
                }
            if (number <= 0) {
                existing.remove(deviceName)
            } else {
                existing[deviceName] = number
            }
            prefs[NEXT_SYNC_NUMBER_KEY] = gson.toJson(existing)
        }
    }

    // --- Custom Palettes ---

    data class StoredPalette(val shortName: String, val name: String, val colors: List<String>)

    val customPalettes: Flow<List<StoredPalette>> =
        context.dataStore.data.map { prefs ->
            val json = prefs[CUSTOM_PALETTES_KEY] ?: return@map emptyList()
            try {
                gson.fromJson(json, object : TypeToken<List<StoredPalette>>() {}.type)
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun saveCustomPalette(palette: StoredPalette) {
        context.dataStore.edit { prefs ->
            val existing: List<StoredPalette> =
                try {
                    val json = prefs[CUSTOM_PALETTES_KEY] ?: "[]"
                    gson.fromJson(json, object : TypeToken<List<StoredPalette>>() {}.type)
                } catch (_: Exception) {
                    emptyList()
                }
            val updated = existing.filter { it.shortName != palette.shortName } + palette
            prefs[CUSTOM_PALETTES_KEY] = gson.toJson(updated)
        }
    }

    suspend fun deleteCustomPalette(shortName: String) {
        context.dataStore.edit { prefs ->
            val existing: List<StoredPalette> =
                try {
                    val json = prefs[CUSTOM_PALETTES_KEY] ?: "[]"
                    gson.fromJson(json, object : TypeToken<List<StoredPalette>>() {}.type)
                } catch (_: Exception) {
                    emptyList()
                }
            prefs[CUSTOM_PALETTES_KEY] = gson.toJson(existing.filter { it.shortName != shortName })
        }
    }

    // --- File Operations ---

    fun getDestDir(
        deviceConfig: DeviceConfig,
        baseFolder: String = DEFAULT_BASE_FOLDER,
    ): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder =
            if (deviceConfig.destFolder.isNotBlank()) {
                deviceConfig.destFolder
            } else {
                "$baseFolder/${deviceConfig.name}"
            }
        return File(base, folder).also { it.mkdirs() }
    }

    fun shouldCopyFile(
        fileName: String,
        fileSize: Long,
        destDir: File,
    ): Boolean {
        val destFile = File(destDir, fileName)
        if (!destFile.exists()) return true
        // Skip if same name and size (already synced)
        return destFile.length() != fileSize
    }

    /**
     * Check if a .sav file needs copying by comparing MD5 of local file against device content.
     * Returns true if the file should be copied (doesn't exist locally or content differs).
     */
    fun shouldCopySavFile(
        fileName: String,
        deviceContent: ByteArray,
        destDir: File,
    ): Boolean {
        val destFile = File(destDir, fileName)
        if (!destFile.exists()) return true
        if (destFile.length() != deviceContent.size.toLong()) return true
        val localHash = md5(destFile.readBytes())
        val deviceHash = md5(deviceContent)
        return localHash != deviceHash
    }

    private fun md5(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private val filterRegexCache = ConcurrentHashMap<String, Regex>()

    fun matchesFilter(
        fileName: String,
        filter: String,
    ): Boolean {
        if (filter == "*") return true
        val regex =
            filterRegexCache.getOrPut(filter) {
                filter
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                    .let { Regex(it, RegexOption.IGNORE_CASE) }
            }
        return regex.matches(fileName)
    }
}
