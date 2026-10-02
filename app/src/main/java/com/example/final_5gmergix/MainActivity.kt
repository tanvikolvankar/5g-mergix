package com.example.final_5gmergix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.WindowManager
import com.example.final_5gmergix.ui.MergixDashboard
import com.example.final_5gmergix.ui.theme.Final_5GMERGIXTheme

class MainActivity : ComponentActivity() {

    private lateinit var configManager: MergixConfigManager
    private lateinit var cameraManager: CameraStreamManager
    private lateinit var telemetryEngine: TelemetryBridgeEngine
    private lateinit var pythonEngine: PythonRunnerEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Wake screen and pop up even if device is locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        configManager = MergixConfigManager(this)
        cameraManager = CameraStreamManager(this)
        telemetryEngine = TelemetryBridgeEngine(this)
        pythonEngine = PythonRunnerEngine(this)

        // Setup auto-start trigger when USB FC connects
        telemetryEngine.onUsbConnectedAutoTrigger = {
            runOnUiThread {
                startFullAutoMission()
            }
        }

        // Setup auto-stop trigger when USB FC disconnects
        telemetryEngine.onUsbDisconnectedAutoTrigger = {
            runOnUiThread {
                stopFullAutoMission()
            }
        }

        // Request runtime permissions
        requestRequiredPermissions()

        // Handle USB connection if launched via USB_DEVICE_ATTACHED
        handleUsbIntent(intent)

        setContent {
            Final_5GMERGIXTheme {
                MergixDashboard(
                    configManager = configManager,
                    cameraManager = cameraManager,
                    telemetryEngine = telemetryEngine,
                    pythonEngine = pythonEngine,
                    onStartService = { startBackgroundService() },
                    onStopService = { stopBackgroundService() }
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(this, MergixForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopBackgroundService() {
        try {
            val serviceIntent = Intent(this, MergixForegroundService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Completely hands-free auto mission start:
     * 1. Starts persistent foreground service
     * 2. Starts cloud telemetry bridge
     * 3. Starts hardware H.264 5G video stream
     */
    fun startFullAutoMission() {
        startBackgroundService()
        val config = configManager.loadConfig()

        // 1. Start Telemetry Bridge to Cloud Port 6666
        if (!telemetryEngine.isBridgeRunning) {
            telemetryEngine.startBridge(config)
        }

        // 2. Start Video Stream to Cloud RTSP Port 8554
        cameraManager.queueAutoStartStream(config.videoOutputLink)
    }

    /**
     * Completely hands-free auto mission stop when FC disconnects:
     * 1. Stops video streaming
     * 2. Stops telemetry bridge
     * 3. Stops foreground service and clears notification
     */
    fun stopFullAutoMission() {
        if (::cameraManager.isInitialized) {
            cameraManager.stop5GVideoStream { _, _ -> }
        }
        if (::telemetryEngine.isInitialized) {
            telemetryEngine.stopBridge()
        }
        stopBackgroundService()
    }

    override fun onResume() {
        super.onResume()
        if (::telemetryEngine.isInitialized) {
            telemetryEngine.scanAndConnectUsb()
            if (telemetryEngine.isUsbConnected) {
                startFullAutoMission()
            } else if (!telemetryEngine.isBridgeRunning) {
                stopBackgroundService()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null && ::telemetryEngine.isInitialized) {
                telemetryEngine.connectUsbSerial(device)
            } else if (::telemetryEngine.isInitialized) {
                telemetryEngine.scanAndConnectUsb()
            }
            startFullAutoMission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFullAutoMission()
        cameraManager.shutdown()
        telemetryEngine.destroy()
    }
}