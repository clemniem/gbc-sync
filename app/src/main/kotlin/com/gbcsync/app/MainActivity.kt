package com.gbcsync.app

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.SyncRepository
import com.gbcsync.app.ui.HomeScreen
import com.gbcsync.app.ui.SettingsScreen
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: SyncRepository
    private lateinit var usbManager: UsbDeviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = SyncRepository(applicationContext)
        usbManager = UsbDeviceManager(applicationContext, repository, lifecycleScope)
        usbManager.register()

        // Sync AppLog.enabled with persisted preference
        repository.debugLogEnabled
            .onEach { AppLog.enabled = it }
            .launchIn(lifecycleScope)

        AppLog.i("App started")

        // Handle USB intent that launched the activity
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbManager.onUsbDeviceAttached()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val syncState by usbManager.syncState.collectAsState()
                    val connectedDevice by usbManager.connectedDevice.collectAsState()
                    val syncLog by repository.syncLog.collectAsState(initial = emptyList())
                    val devices by repository.deviceConfigs.collectAsState(initial = emptyList())
                    val logLines by AppLog.lines.collectAsState()
                    val debugLogEnabled by repository.debugLogEnabled.collectAsState(initial = true)

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                syncState = syncState,
                                connectedDevice = connectedDevice,
                                syncLog = syncLog,
                                logLines = logLines,
                                debugLogEnabled = debugLogEnabled,
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                devices = devices,
                                onDevicesChanged = { updated ->
                                    lifecycleScope.launch {
                                        repository.saveDeviceConfigs(updated)
                                    }
                                },
                                debugLogEnabled = debugLogEnabled,
                                onDebugLogEnabledChanged = { enabled ->
                                    lifecycleScope.launch {
                                        repository.setDebugLogEnabled(enabled)
                                    }
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbManager.onUsbDeviceAttached()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.unregister()
    }
}
