package com.gbcsync.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.CameraType
import com.gbcsync.app.data.DeviceConfig
import com.gbcsync.app.data.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import me.jahnen.libaums.core.UsbMassStorageDevice

class UsbDeviceManager(
    private val context: Context,
    private val repository: SyncRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.gbcsync.app.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val syncLock = Mutex()

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice

    private val fileCopier = FileCopier(repository)
    private val blockDeviceFactory = BlockDeviceFactory(usbManager)
    private val bridgeSync = BridgeSync(context, usbManager, repository, fileCopier, _syncState, ::requestPermission)
    private val joeyJrSync = JoeyJrSync(repository, fileCopier, blockDeviceFactory, _syncState)

    // --- UI callbacks ---

    fun onContinueImport() {
        _syncState.value = _syncState.value.copy(importChoice = null)
        bridgeSync.importChoiceDeferred?.complete(true)
    }

    fun onNewImport() {
        _syncState.value = _syncState.value.copy(importChoice = null)
        bridgeSync.importChoiceDeferred?.complete(false)
    }

    fun onCancelImport() {
        _syncState.value = _syncState.value.copy(importChoice = null)
        bridgeSync.importChoiceDeferred?.complete(null)
    }

    fun onCameraChosen(camera: CameraType) {
        _syncState.value = _syncState.value.copy(cameraChoice = null)
        joeyJrSync.cameraChoiceDeferred?.complete(camera)
    }

    // --- USB lifecycle ---

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        AppLog.d("USB device detached")
                        _connectedDevice.value = null
                        // Cancel any pending import choice so the sync coroutine can finish
                        bridgeSync.importChoiceDeferred?.complete(true)
                        if (_syncState.value.status != SyncState.Status.DONE) {
                            _syncState.value = SyncState()
                        }
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            AppLog.d("USB permission granted")
                            scope.launch(Dispatchers.IO) { startSync() }
                        } else {
                            AppLog.w("USB permission denied")
                            _syncState.value =
                                SyncState(
                                    status = SyncState.Status.ERROR,
                                    error = "USB permission denied",
                                )
                        }
                    }
                }
            }
        }

    fun register() {
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun onUsbDeviceAttached() {
        AppLog.i("USB device attached event received")
        scope.launch(Dispatchers.IO) { detectAndSync() }
    }

    fun retrySync() {
        AppLog.i("Manual sync retry requested")
        scope.launch(Dispatchers.IO) { detectAndSync() }
    }

    // --- Detection & routing ---

    private fun requestPermission(device: UsbDevice) {
        val intent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE,
            )
        usbManager.requestPermission(device, intent)
    }

    private suspend fun detectAndSync() {
        AppLog.d("Scanning for mass storage devices...")
        val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (massStorageDevices.isEmpty()) {
            AppLog.w("No mass storage devices found")
            return
        }
        AppLog.d("Found ${massStorageDevices.size} mass storage device(s)")

        val configs = repository.deviceConfigs.first()
        AppLog.d("Loaded ${configs.size} device config(s)")

        for (device in massStorageDevices) {
            val usbDevice = device.usbDevice
            AppLog.d("Device: vendorId=${usbDevice.vendorId} productId=${usbDevice.productId} name=${usbDevice.deviceName}")
            val matchedConfig = findMatchingConfig(usbDevice, configs)

            _connectedDevice.value = matchedConfig?.name ?: "Unknown USB (${usbDevice.vendorId}:${usbDevice.productId})"
            AppLog.i("Matched config: ${matchedConfig?.name ?: "none"}")
            _syncState.value = SyncState(status = SyncState.Status.CONNECTING, deviceName = _connectedDevice.value ?: "")

            if (!usbManager.hasPermission(usbDevice)) {
                AppLog.d("Requesting USB permission...")
                requestPermission(usbDevice)
                return
            }

            startSync()
            return
        }
    }

    private suspend fun startSync() {
        if (!syncLock.tryLock()) {
            AppLog.d("Sync already in progress, skipping")
            return
        }
        try {
            doSync()
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun doSync() {
        val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (massStorageDevices.isEmpty()) return

        val configs = repository.deviceConfigs.first()
        val baseFolder = repository.baseFolder.first()

        for (storageDevice in massStorageDevices) {
            val config = findMatchingConfig(storageDevice.usbDevice, configs) ?: configs.firstOrNull() ?: continue
            val destDir = repository.getDestDir(config, baseFolder)
            val deviceName = config.name
            val usbDev = storageDevice.usbDevice

            if (!usbManager.hasPermission(usbDev)) {
                AppLog.w("USB permission lost, re-requesting...")
                requestPermission(usbDev)
                return
            }

            AppLog.i("=== SYNC START: $deviceName (vendor=${usbDev.vendorId}, product=${usbDev.productId}) ===")

            when {
                config.vendorId == BridgeSync.VENDOR_ID && config.productId == BridgeSync.PRODUCT_ID ->
                    bridgeSync.sync(storageDevice, config, destDir, deviceName)
                else ->
                    joeyJrSync.sync(storageDevice, config, destDir, deviceName)
            }
        }
    }

    private fun findMatchingConfig(
        usbDevice: UsbDevice,
        configs: List<DeviceConfig>,
    ): DeviceConfig? {
        for (config in configs) {
            if (config.vendorId != 0 &&
                config.productId != 0 &&
                config.vendorId == usbDevice.vendorId &&
                config.productId == usbDevice.productId
            ) {
                return config
            }
        }
        return configs.firstOrNull { it.vendorId == 0 && it.productId == 0 }
    }
}
