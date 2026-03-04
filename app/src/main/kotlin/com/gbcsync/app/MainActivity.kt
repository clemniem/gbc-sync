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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gbcsync.app.data.DeviceConfig
import com.gbcsync.app.data.SyncRepository
import com.gbcsync.app.ui.HomeScreen
import com.gbcsync.app.ui.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: SyncRepository
    private lateinit var usbManager: UsbDeviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = SyncRepository(applicationContext)
        usbManager = UsbDeviceManager(applicationContext, repository, lifecycleScope)
        usbManager.register()

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

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                syncState = syncState,
                                connectedDevice = connectedDevice,
                                syncLog = syncLog,
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
