package com.gbcsync.app

import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.CameraType
import com.gbcsync.app.data.SyncLogEntry
import com.gbcsync.app.data.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import me.jahnen.libaums.core.UsbMassStorageDevice

data class ImportChoice(
    val message: String,
    val appendLabel: String = "Append",
    val newLabel: String = "Start New",
    val autoAppendSeconds: Int = 0
)

data class SyncState(
    val status: Status = Status.IDLE,
    val deviceName: String = "",
    val currentFile: String = "",
    val filesCopied: Int = 0,
    val totalFiles: Int = 0,
    val errors: Int = 0,
    val error: String? = null,
    val importChoice: ImportChoice? = null,
    val cameraChoice: List<CameraType>? = null,
    val targetFolder: String = "",
    val durationMs: Long = 0,
    val safeToDisconnect: Boolean = false
) {
    enum class Status { IDLE, CONNECTING, SYNCING, DONE, ERROR }

    val progress: Float
        get() = if (totalFiles > 0) filesCopied.toFloat() / totalFiles else 0f
}

fun UsbMassStorageDevice.closeSafely() {
    try { close() } catch (_: Exception) {}
}

suspend fun finishSync(
    syncState: MutableStateFlow<SyncState>,
    repository: SyncRepository,
    deviceName: String,
    copied: Int,
    total: Int,
    errors: Int,
    targetFolder: String = "",
    durationMs: Long = 0
) {
    val summary = buildString {
        append("Sync complete: $copied copied")
        if (errors > 0) append(", $errors failed")
    }
    AppLog.i(summary)

    syncState.value = SyncState(
        status = if (errors > 0 && copied == 0) SyncState.Status.ERROR else SyncState.Status.DONE,
        deviceName = deviceName,
        filesCopied = copied,
        totalFiles = total,
        errors = errors,
        error = if (errors > 0) "$errors file(s) failed to copy" else null,
        targetFolder = targetFolder,
        durationMs = durationMs,
        safeToDisconnect = true
    )

    try {
        repository.addSyncLogEntry(
            SyncLogEntry(
                deviceName = deviceName,
                timestamp = System.currentTimeMillis(),
                filesCopied = copied,
                errors = errors,
                totalBytes = 0,
                durationMs = durationMs,
                targetFolder = targetFolder
            )
        )
    } catch (e: Exception) {
        AppLog.w("Failed to write sync log entry: ${e.message}")
    }
}
