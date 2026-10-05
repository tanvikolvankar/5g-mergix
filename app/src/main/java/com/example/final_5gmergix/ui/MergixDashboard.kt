package com.example.final_5gmergix.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.final_5gmergix.BridgeState
import com.example.final_5gmergix.CameraStreamManager
import com.example.final_5gmergix.VideoSource
import com.example.final_5gmergix.MergixConfig
import com.example.final_5gmergix.MergixConfigManager
import com.example.final_5gmergix.PythonRunnerEngine
import com.example.final_5gmergix.TelemetryBridgeEngine
import com.example.final_5gmergix.TelemetryStats
import com.pedro.library.view.OpenGlView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// High-tech Tactical Dark Color Palette
val DarkBg = Color(0xFF090D16)
val PanelBg = Color(0xFF131D2E)
val CardBorderColor = Color(0xFF22344D)
val NeonCyan = Color(0xFF00E5FF)
val ElectricBlue = Color(0xFF2979FF)
val ActiveGreen = Color(0xFF00E676)
val WarningAmber = Color(0xFFFFB300)
val CriticalRed = Color(0xFFFF1744)
val MutedText = Color(0xFF90A4AE)
val LightText = Color(0xFFECEFF1)
val TerminalBg = Color(0xFF06090F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergixDashboard(
    configManager: MergixConfigManager,
    cameraManager: CameraStreamManager,
    telemetryEngine: TelemetryBridgeEngine,
    pythonEngine: PythonRunnerEngine,
    onStartService: () -> Unit,
    onStopService: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    // Configuration
    var currentConfig by remember { mutableStateOf(configManager.loadConfig()) }

    // Video Streaming State
    var isStreamingVideo by remember { mutableStateOf(cameraManager.isStreaming) }
    var activeVideoSource by remember { mutableStateOf(cameraManager.activeSource) }
    var videoStatusText by remember { mutableStateOf("Camera Ready") }
    var liveFps by remember { mutableStateOf(0) }

    // Telemetry Bridge State
    var telemetryStats by remember {
        mutableStateOf(
            TelemetryStats(
                state = telemetryEngine.currentState,
                stateDescription = telemetryEngine.currentStatusMessage,
                isUsbConnected = telemetryEngine.isUsbConnected,
                usbDeviceName = telemetryEngine.usbDeviceName,
                isCloudConnected = telemetryEngine.isCloudConnected,
                bytesReadFromUsb = telemetryEngine.bytesReadFromUsb,
                packetsReadFromUsb = telemetryEngine.packetsReadFromUsb,
                bytesSentToCloud = telemetryEngine.bytesSentToCloud,
                bytesReceivedFromCloud = telemetryEngine.bytesReceivedFromCloud,
                lastHeartbeatMs = telemetryEngine.lastHeartbeatTime,
                isRelayActive = telemetryEngine.isRelayActive
            )
        )
    }

    // Terminal Logs State
    val terminalLogs = remember { mutableStateListOf<String>() }

    // Wire listeners
    DisposableEffect(Unit) {
        cameraManager.onFpsUpdate = { fps -> liveFps = fps }
        cameraManager.onStreamStateChanged = { active, msg ->
            isStreamingVideo = active
            videoStatusText = msg
        }
        cameraManager.onVideoSourceChanged = { src, label ->
            activeVideoSource = src
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (terminalLogs.size > 200) terminalLogs.removeAt(0)
            terminalLogs.add("[$timestamp] [VIDEO-SOURCE] Active: $label")
        }
        cameraManager.onLogMessage = { msg ->
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (terminalLogs.size > 200) terminalLogs.removeAt(0)
            terminalLogs.add("[$timestamp] $msg")
        }
        telemetryEngine.onStatusUpdated = { stats -> telemetryStats = stats }
        telemetryEngine.onLogMessage = { msg ->
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (terminalLogs.size > 200) terminalLogs.removeAt(0)
            terminalLogs.add("[$timestamp] $msg")
        }
        onDispose {
            cameraManager.onFpsUpdate = null
            cameraManager.onStreamStateChanged = null
            cameraManager.onVideoSourceChanged = null
            cameraManager.onLogMessage = null
            telemetryEngine.onStatusUpdated = null
            telemetryEngine.onLogMessage = null
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "5G MERGIX",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = NeonCyan,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        // Drone ID badge
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ElectricBlue.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.6f))
                        ) {
                            Text(
                                currentConfig.droneId,
                                color = LightText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    // Quick Status Indicators
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        // FC Status
                        StatusPill(
                            label = "FC",
                            active = telemetryStats.isUsbConnected,
                            activeColor = ActiveGreen,
                            inactiveColor = MutedText
                        )
                        Spacer(Modifier.width(6.dp))
                        // Cloud Relay Status
                        StatusPill(
                            label = "RELAY",
                            active = telemetryStats.isRelayActive,
                            activeColor = NeonCyan,
                            inactiveColor = if (telemetryStats.isCloudConnected) WarningAmber else MutedText
                        )
                        Spacer(Modifier.width(6.dp))
                        // Video / Camera Source Status Pill
                        StatusPill(
                            label = if (activeVideoSource == VideoSource.ETHERNET_CAMERA) "CAM: ETH" else "CAM: PHONE",
                            active = isStreamingVideo,
                            activeColor = if (activeVideoSource == VideoSource.ETHERNET_CAMERA) ActiveGreen else CriticalRed,
                            inactiveColor = MutedText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PanelBg)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = PanelBg) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "Mission Control") },
                    label = { Text("Mission") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = MutedText,
                        indicatorColor = NeonCyan.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal Logs") },
                    label = { Text("Terminal") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ActiveGreen,
                        selectedTextColor = ActiveGreen,
                        unselectedIconColor = MutedText,
                        indicatorColor = ActiveGreen.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = WarningAmber,
                        selectedTextColor = WarningAmber,
                        unselectedIconColor = MutedText,
                        indicatorColor = WarningAmber.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Code, contentDescription = "Scripts") },
                    label = { Text("Scripts") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricBlue,
                        selectedTextColor = ElectricBlue,
                        unselectedIconColor = MutedText,
                        indicatorColor = ElectricBlue.copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> MissionControlTab(
                    config = currentConfig,
                    cameraManager = cameraManager,
                    telemetryEngine = telemetryEngine,
                    isStreamingVideo = isStreamingVideo,
                    onVideoStreamingChange = { streaming, status ->
                        isStreamingVideo = streaming
                        videoStatusText = status
                    },
                    telemetryStats = telemetryStats,
                    liveFps = liveFps,
                    activeVideoSource = activeVideoSource,
                    onStartService = onStartService,
                    onStopService = onStopService
                )
                1 -> TerminalTab(terminalLogs)
                2 -> SettingsTab(
                    currentConfig = currentConfig,
                    onSave = { updated ->
                        if (configManager.saveConfig(updated)) {
                            currentConfig = updated
                            cameraManager.defaultCloudRtspUrl = updated.videoOutputLink
                            Toast.makeText(context, "Settings Saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                3 -> ScriptsTab(pythonEngine)
            }
        }
    }
}

@Composable
fun StatusPill(label: String, active: Boolean, activeColor: Color, inactiveColor: Color) {
    val color by animateColorAsState(if (active) activeColor else inactiveColor, label = "pill")
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Text(
            label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

// =========================================================================
// TAB 0: Mission Control (Video Preview + Real-time Telemetry Status)
// =========================================================================

@Composable
fun MissionControlTab(
    config: MergixConfig,
    cameraManager: CameraStreamManager,
    telemetryEngine: TelemetryBridgeEngine,
    isStreamingVideo: Boolean,
    onVideoStreamingChange: (Boolean, String) -> Unit,
    telemetryStats: TelemetryStats,
    liveFps: Int,
    activeVideoSource: VideoSource,
    onStartService: () -> Unit,
    onStopService: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp)
    ) {
        // --- 1. LIVE CAMERA VIEWFINDER CARD ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = PanelBg),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (isStreamingVideo) CriticalRed else CardBorderColor
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // OpenGlView Surface
                AndroidView(
                    factory = { ctx ->
                        val openGlView = OpenGlView(ctx)
                        cameraManager.attachOpenGlView(openGlView)
                        openGlView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top Floating Badge: FPS & Resolution
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(DarkBg.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isStreamingVideo) CriticalRed else MutedText)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isStreamingVideo) "LIVE $liveFps FPS" else "PREVIEW $liveFps FPS",
                        color = LightText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${cameraManager.streamWidth}x${cameraManager.streamHeight}",
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Top Floating Source Badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(DarkBg.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            if (activeVideoSource == VideoSource.ETHERNET_CAMERA) ActiveGreen.copy(alpha = 0.7f) else ElectricBlue.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (activeVideoSource == VideoSource.ETHERNET_CAMERA) ActiveGreen else NeonCyan)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (activeVideoSource == VideoSource.ETHERNET_CAMERA) "ETHERNET (SIYI)" else "PHONE CAM",
                        color = if (activeVideoSource == VideoSource.ETHERNET_CAMERA) ActiveGreen else LightText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Overlay when Ethernet Camera is Active & Relaying
                if (activeVideoSource == VideoSource.ETHERNET_CAMERA && isStreamingVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DarkBg.copy(alpha = 0.90f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Router,
                                contentDescription = null,
                                tint = ActiveGreen,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "ETHERNET CAMERA RELAY ACTIVE",
                                color = ActiveGreen,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "IN: ${config.videoInputLink}",
                                color = MutedText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "OUT: ${config.videoOutputLink}",
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = ActiveGreen.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ActiveGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    "ZERO-CPU 5G RELAY",
                                    color = ActiveGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom Overlay: RTSP Endpoint
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(DarkBg.copy(alpha = 0.85f))
                        .padding(vertical = 4.dp, horizontal = 10.dp)
                ) {
                    Text(
                        config.videoOutputLink,
                        color = MutedText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- 2. DUAL ACTION CONTROLS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Video Toggle Button
            Button(
                onClick = {
                    if (isStreamingVideo) {
                        cameraManager.stop5GVideoStream { active, msg ->
                            onVideoStreamingChange(active, msg)
                        }
                        if (telemetryStats.state == BridgeState.IDLE) {
                            onStopService()
                        }
                    } else {
                        onStartService()
                        cameraManager.start5GVideoStream(config.videoOutputLink, config.videoInputLink) { active, msg ->
                            onVideoStreamingChange(active, msg)
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreamingVideo) CriticalRed else ElectricBlue
                )
            ) {
                Icon(
                    if (isStreamingVideo) Icons.Default.Stop else Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isStreamingVideo) "STOP VIDEO" else "START VIDEO",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // Telemetry Toggle Button
            val isTelemActive = telemetryStats.state != BridgeState.IDLE
            Button(
                onClick = {
                    if (isTelemActive) {
                        telemetryEngine.stopBridge()
                        if (!isStreamingVideo) {
                            onStopService()
                        }
                    } else {
                        onStartService()
                        telemetryEngine.startBridge(config)
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTelemActive) WarningAmber else ActiveGreen
                )
            ) {
                Icon(
                    if (isTelemActive) Icons.Default.Stop else Icons.Default.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = DarkBg
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isTelemActive) "STOP TELEM" else "START TELEM",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = DarkBg
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- 2b. ETHERNET CAMERA AUTO-DETECT ROW ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBg, RoundedCornerShape(10.dp))
                .border(1.dp, CardBorderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Default.Router,
                    contentDescription = null,
                    tint = if (activeVideoSource == VideoSource.ETHERNET_CAMERA) ActiveGreen else NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        if (activeVideoSource == VideoSource.ETHERNET_CAMERA) "Active: Ethernet Camera (SIYI)" else "Active: Phone Internal Camera",
                        color = if (activeVideoSource == VideoSource.ETHERNET_CAMERA) ActiveGreen else LightText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Auto-detects ${config.videoInputLink} -> Fallback to Phone",
                        color = MutedText,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
            val localCtx = LocalContext.current
            OutlinedButton(
                onClick = {
                    java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                        val ok = cameraManager.probeEthernetCamera(config.videoInputLink)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(
                                localCtx,
                                if (ok) "Ethernet Camera Online at ${config.videoInputLink}!" else "Ethernet Camera Offline. Will use Phone Camera.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
            ) {
                Text("TEST LINK", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- 3. TELEMETRY STATUS CARD ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = PanelBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderColor)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "5G TELEMETRY RELAY",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = NeonCyan,
                        letterSpacing = 0.5.sp
                    )

                    // State Chip
                    val (chipColor, chipText) = when (telemetryStats.state) {
                        BridgeState.IDLE -> MutedText to "OFFLINE"
                        BridgeState.SEARCHING_USB -> WarningAmber to "SEARCHING USB"
                        BridgeState.CONNECTING_CLOUD -> WarningAmber to "CONNECTING"
                        BridgeState.AUTHENTICATING -> ElectricBlue to "AUTH (DRONE_ID)"
                        BridgeState.HEARTBEAT_PULSE -> ActiveGreen to "HEARTBEAT OK"
                        BridgeState.RELAY_STREAMING -> NeonCyan to "RELAY ACTIVE"
                        BridgeState.ERROR -> CriticalRed to "RECONNECTING"
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = chipColor.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, chipColor)
                    ) {
                        Text(
                            chipText,
                            color = chipColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    telemetryStats.stateDescription,
                    color = LightText,
                    fontSize = 12.sp,
                    maxLines = 2
                )

                if (!telemetryStats.isUsbConnected) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { telemetryEngine.scanAndConnectUsb() },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SCAN / CONNECT FLIGHT CONTROLLER", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(
                    color = CardBorderColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Grid of Telemetry Details
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricBox(
                        label = "USB FLIGHT CONTROLLER",
                        value = if (telemetryStats.isUsbConnected) telemetryStats.usbDeviceName else "Not Detected",
                        isHighlight = telemetryStats.isUsbConnected,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    MetricBox(
                        label = "CLOUD TARGET",
                        value = "${config.telemIp}:${config.telemPort}",
                        isHighlight = telemetryStats.isCloudConnected,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricBox(
                        label = "FC DATA IN (USB)",
                        value = "${formatBytes(telemetryStats.bytesReadFromUsb)} (${telemetryStats.packetsReadFromUsb} pkts)",
                        isHighlight = telemetryStats.bytesReadFromUsb > 0,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    MetricBox(
                        label = "CLOUD RELAY (UP)",
                        value = formatBytes(telemetryStats.bytesSentToCloud),
                        isHighlight = telemetryStats.bytesSentToCloud > 0,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricBox(
                        label = "CLOUD RELAY (DOWN)",
                        value = formatBytes(telemetryStats.bytesReceivedFromCloud),
                        isHighlight = telemetryStats.bytesReceivedFromCloud > 0,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    MetricBox(
                        label = "RELAY ACTIVE",
                        value = if (telemetryStats.isRelayActive) "STREAMING" else if (telemetryStats.isCloudConnected) "WAITING GCS" else "OFFLINE",
                        isHighlight = telemetryStats.isRelayActive,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun MetricBox(label: String, value: String, isHighlight: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = DarkBg.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isHighlight) ElectricBlue.copy(alpha = 0.5f) else CardBorderColor)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, color = MutedText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                color = if (isHighlight) LightText else MutedText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

// =========================================================================
// TAB 1: Real-time Terminal Log Viewer
// =========================================================================

@Composable
fun TerminalTab(logs: List<String>) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LIVE TELEMETRY LOGS", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row {
                IconButton(onClick = {
                    val fullText = logs.joinToString("\n")
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Mergix Logs", fullText))
                    Toast.makeText(context, "Logs Copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = LightText)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(TerminalBg)
                .border(1.dp, CardBorderColor, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                if (logs.isEmpty()) {
                    Text(
                        "No events logged yet. Tap 'START TELEM' to begin.",
                        color = MutedText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    logs.forEach { line ->
                        val color = when {
                            line.contains("[ERROR]") || line.contains("dead") -> CriticalRed
                            line.contains("[HB]") -> WarningAmber
                            line.contains("START RELAY") || line.contains("ACTIVE") -> ActiveGreen
                            else -> LightText
                        }
                        Text(
                            line,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// TAB 2: Settings & Configuration Editor
// =========================================================================

@Composable
fun SettingsTab(
    currentConfig: MergixConfig,
    onSave: (MergixConfig) -> Unit
) {
    var droneId by remember { mutableStateOf(currentConfig.droneId) }
    var telemIp by remember { mutableStateOf(currentConfig.telemIp) }
    var telemPort by remember { mutableStateOf(currentConfig.telemPort.toString()) }
    var baudRate by remember { mutableStateOf(currentConfig.baudRate.toString()) }
    var videoOutput by remember { mutableStateOf(currentConfig.videoOutputLink) }
    var videoRes by remember { mutableStateOf(currentConfig.videoRes) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("SYSTEM CONFIGURATION", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Modify connection parameters (persisted to static_mergix_data.json)", color = MutedText, fontSize = 11.sp)

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = droneId,
            onValueChange = { droneId = it },
            label = { Text("Drone ID (Must match server userdata.db)") },
            modifier = Modifier.fillMaxWidth(),
            colors = customFieldColors()
        )

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = telemIp,
                onValueChange = { telemIp = it },
                label = { Text("Server Telemetry IP") },
                modifier = Modifier.weight(1.5f),
                colors = customFieldColors()
            )
            OutlinedTextField(
                value = telemPort,
                onValueChange = { telemPort = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                colors = customFieldColors()
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = baudRate,
            onValueChange = { baudRate = it },
            label = { Text("Pixhawk Baud Rate (Default 115200)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = customFieldColors()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = videoOutput,
            onValueChange = { videoOutput = it },
            label = { Text("Video Output RTSP URL") },
            modifier = Modifier.fillMaxWidth(),
            colors = customFieldColors()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = videoRes,
            onValueChange = { videoRes = it },
            label = { Text("Video Resolution (e.g. 1280:720)") },
            modifier = Modifier.fillMaxWidth(),
            colors = customFieldColors()
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val updated = MergixConfig(
                    droneId = droneId.trim(),
                    telemIp = telemIp.trim(),
                    telemPort = telemPort.toIntOrNull() ?: 6666,
                    droneConnectionType = currentConfig.droneConnectionType,
                    dronePort = currentConfig.dronePort,
                    baudRate = baudRate.toIntOrNull() ?: 115200,
                    videoInputLink = currentConfig.videoInputLink,
                    videoOutputLink = videoOutput.trim(),
                    videoRes = videoRes.trim()
                )
                onSave(updated)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("SAVE CONFIGURATION", color = DarkBg, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun customFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = CardBorderColor,
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = MutedText,
    focusedTextColor = LightText,
    unfocusedTextColor = LightText
)

// =========================================================================
// TAB 3: Bundled Python Scripts & Tools
// =========================================================================

@Composable
fun ScriptsTab(pythonEngine: PythonRunnerEngine) {
    val scripts = remember { pythonEngine.getAvailableScripts() }
    var selectedFile by remember { mutableStateOf(scripts.firstOrNull()) }
    var fileContent by remember { mutableStateOf(selectedFile?.let { pythonEngine.getScriptContent(it) } ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text("BUNDLED SCRIPTS & ASSETS", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))

        // Script chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            scripts.forEach { file ->
                val isSelected = selectedFile?.name == file.name
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) ElectricBlue else PanelBg,
                    modifier = Modifier.clickable {
                        selectedFile = file
                        fileContent = pythonEngine.getScriptContent(file)
                    }
                ) {
                    Text(
                        file.name,
                        color = if (isSelected) LightText else MutedText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(TerminalBg)
                .border(1.dp, CardBorderColor, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            val scrollState = rememberScrollState()
            Text(
                fileContent,
                color = LightText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(scrollState)
            )
        }
    }
}
