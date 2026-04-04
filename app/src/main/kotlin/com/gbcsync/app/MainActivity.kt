package com.gbcsync.app

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
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
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.gbcsync.app.gifmaker.GifMakerScreen
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
                    val ownedCameras by repository.ownedCameras.collectAsState(initial = emptySet())
                    val baseFolder by repository.baseFolder.collectAsState(initial = "gbc-sync")

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                syncState = syncState,
                                connectedDevice = connectedDevice,
                                syncLog = syncLog,
                                logLines = logLines,
                                debugLogEnabled = debugLogEnabled,
                                onRetrySync = { usbManager.retrySync() },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onOpenGbPrinterWeb = {
                                    startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://herrzatacke.github.io/gb-printer-web/#/import")),
                                    )
                                },
                                onOpenFolder = { path ->
                                    try {
                                        val intent =
                                            Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(Uri.parse(path), "resource/folder")
                                            }
                                        startActivity(intent)
                                    } catch (_: Exception) {
                                        // Fallback: open generic file browser
                                        try {
                                            val intent =
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(Uri.parse("content://$path"), "vnd.android.document/directory")
                                                }
                                            startActivity(intent)
                                        } catch (_: Exception) {
                                            AppLog.w("No file manager found to open folder")
                                        }
                                    }
                                },
                                onMakeGif = { path ->
                                    if (path.isNotEmpty()) {
                                        navController.navigate("gifmaker?path=${Uri.encode(path)}")
                                    } else {
                                        navController.navigate("gifmaker")
                                    }
                                },
                                onContinueImport = { usbManager.onContinueImport() },
                                onNewImport = { usbManager.onNewImport() },
                                onCancelImport = { usbManager.onCancelImport() },
                                onCameraChosen = { usbManager.onCameraChosen(it) },
                            )
                        }
                        composable(
                            "gifmaker?path={folderPath}",
                            arguments = listOf(navArgument("folderPath") { type = NavType.StringType; defaultValue = "" }),
                        ) { backStackEntry ->
                            val folderPath = backStackEntry.arguments?.getString("folderPath") ?: ""
                            GifMakerScreen(
                                syncFolderPath = Uri.decode(folderPath),
                                onNavigateBack = { navController.popBackStack() },
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
                                ownedCameras = ownedCameras,
                                onOwnedCamerasChanged = { updated ->
                                    lifecycleScope.launch {
                                        repository.saveOwnedCameras(updated)
                                    }
                                },
                                baseFolder = baseFolder,
                                onBaseFolderChanged = { updated ->
                                    lifecycleScope.launch {
                                        repository.setBaseFolder(updated)
                                    }
                                },
                                debugLogEnabled = debugLogEnabled,
                                onDebugLogEnabledChanged = { enabled ->
                                    lifecycleScope.launch {
                                        repository.setDebugLogEnabled(enabled)
                                    }
                                },
                                onNavigateBack = { navController.popBackStack() },
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
