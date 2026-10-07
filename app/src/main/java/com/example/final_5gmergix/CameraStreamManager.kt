package com.example.final_5gmergix

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pedro.common.ConnectChecker
import com.pedro.library.rtsp.RtspCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class VideoSource {
    INTERNAL_PHONE,
    ETHERNET_CAMERA
}

class CameraStreamManager(
    private val context: Context
) : ConnectChecker {

    private val TAG = "CameraStreamManager"

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var rtspCamera2: RtspCamera2? = null
    private var openGlViewRef: OpenGlView? = null

    val userWantsStreaming = AtomicBoolean(false)
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()

    // Ethernet RTSP Relay Engine
    val ethernetRelay: EthernetRtspRelayEngine = EthernetRtspRelayEngine(
        context = context,
        onLog = { msg -> onLogMessage?.invoke(msg) },
        onStateChange = { active, msg ->
            if (userWantsStreaming.get()) {
                isStreaming = true
                onStreamStateChanged?.invoke(true, msg)
            } else {
                isStreaming = active
                onStreamStateChanged?.invoke(active, msg)
            }
        },
        onRelayFailed = { reason ->
            Log.w(TAG, "Ethernet Camera stream dropped: $reason")
            if (userWantsStreaming.get()) {
                onLogMessage?.invoke("[VIDEO-AUTO-REFRESH] Ethernet Camera stream dropped ($reason). Auto-refreshing...")
                statusListener?.invoke(true, "Ethernet Stream Break. Auto-refreshing...")
                onStreamStateChanged?.invoke(true, "Ethernet Stream Break. Auto-refreshing...")
                Executors.newSingleThreadExecutor().execute {
                    val inputUrl = pendingInputRtspUrl ?: defaultInputRtspUrl
                    val (reachable, _) = EthernetRtspRelayEngine.probeCameraDetailed(context, inputUrl, timeoutMs = 1200)
                    if (reachable && userWantsStreaming.get()) {
                        onLogMessage?.invoke("[VIDEO-RECONNECT] Ethernet Camera responsive. Reconnecting in 500ms...")
                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                        if (userWantsStreaming.get()) {
                            ethernetRelay.startRelay(inputUrl, defaultCloudRtspUrl)
                            onStreamStateChanged?.invoke(true, "LIVE: Ethernet Camera -> Cloud RTSP ($defaultCloudRtspUrl)")
                        }
                    } else if (userWantsStreaming.get()) {
                        onLogMessage?.invoke("[VIDEO-FAILOVER] Ethernet Camera disconnected. Seamlessly switching to Phone Camera...")
                        fallbackToInternalCamera()
                    }
                }
            }
        }
    )

    var defaultCloudRtspUrl = "rtsp://64.227.133.143:8554/mystream1"
    var defaultInputRtspUrl = "rtsp://192.168.144.25:8554/main.264"
    var streamWidth = 1280
    var streamHeight = 720
    var streamFps = 30
    var streamBitrate = 2000 * 1024 // 2.0 Mbps 720p HD

    var isStreaming = false
        private set

    var activeSource: VideoSource = VideoSource.INTERNAL_PHONE
        private set

    var isEthernetAvailable: Boolean = false
        private set

    var currentLensFacing = CameraSelector.LENS_FACING_BACK
        private set

    private var statusListener: ((Boolean, String) -> Unit)? = null
    var onFpsUpdate: ((Int) -> Unit)? = null
    var onStreamStateChanged: ((Boolean, String) -> Unit)? = null
    var onVideoSourceChanged: ((VideoSource, String) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    private var pendingAutoStreamUrl: String? = null
    private var pendingInputRtspUrl: String? = null

    private var ethernetWatcherThread: Thread? = null
    private val isWatchingForEthernet = AtomicBoolean(false)
    private val isStartingStream = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerEthernetNetworkCallback()
    }

    private fun registerEthernetNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Ethernet interface available, triggering fast camera probe")
                    if (isStreaming && activeSource == VideoSource.INTERNAL_PHONE) {
                        Executors.newSingleThreadExecutor().execute {
                            try { Thread.sleep(600) } catch (_: InterruptedException) {}
                            if (isStreaming && activeSource == VideoSource.INTERNAL_PHONE) {
                                val inUrl = pendingInputRtspUrl ?: defaultInputRtspUrl
                                val (ok, resolved) = probeEthernetCameraWithAutoDetect(inUrl)
                                if (ok && isStreaming && activeSource == VideoSource.INTERNAL_PHONE) {
                                    val camLabel = if (resolved.contains(".25")) "ETHERNET CAMERA (SIYI)" else "ETHERNET CAMERA (SKYDROID)"
                                    onLogMessage?.invoke("[VIDEO-AUTO] Ethernet Link online ($camLabel)! Auto-switching from Phone Camera...")
                                    stopEthernetAutoRecoveryWatcher()
                                    switchVideoSource(VideoSource.ETHERNET_CAMERA, defaultCloudRtspUrl, resolved) { active, msg ->
                                        onStreamStateChanged?.invoke(active, msg)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "registerEthernetNetworkCallback: ${e.message}")
        }
    }

    fun startEthernetAutoRecoveryWatcher(outputRtspUrl: String, inputRtspUrl: String) {
        if (isWatchingForEthernet.getAndSet(true)) return
        ethernetWatcherThread?.interrupt()
        ethernetWatcherThread = Thread({
            Log.d(TAG, "Ethernet Auto-Recovery Watcher active for $inputRtspUrl")
            while (isWatchingForEthernet.get() && isStreaming && activeSource == VideoSource.INTERNAL_PHONE) {
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                }
                if (!isWatchingForEthernet.get() || !isStreaming || activeSource != VideoSource.INTERNAL_PHONE) {
                    break
                }

                val (reachable, resolvedInput) = probeEthernetCameraWithAutoDetect(inputRtspUrl)
                if (reachable && isStreaming && activeSource == VideoSource.INTERNAL_PHONE) {
                    val camLabel = if (resolvedInput.contains(".25")) "ETHERNET CAMERA (SIYI)" else "ETHERNET CAMERA (SKYDROID)"
                    onLogMessage?.invoke("[VIDEO-AUTO] Ethernet Camera came online ($camLabel)! Auto-switching from Phone Camera...")
                    isWatchingForEthernet.set(false)
                    switchVideoSource(
                        newSource = VideoSource.ETHERNET_CAMERA,
                        outputRtspUrl = outputRtspUrl,
                        inputRtspUrl = resolvedInput
                    ) { active, msg ->
                        onStreamStateChanged?.invoke(active, msg)
                    }
                    break
                }
            }
            isWatchingForEthernet.set(false)
        }, "EthernetAutoWatcher").apply {
            isDaemon = true
            start()
        }
    }

    fun stopEthernetAutoRecoveryWatcher() {
        isWatchingForEthernet.set(false)
        ethernetWatcherThread?.interrupt()
        ethernetWatcherThread = null
    }

    fun updateStreamSettings(width: Int, height: Int, fps: Int, bitrateKbps: Int) {
        streamWidth = width
        streamHeight = height
        streamFps = fps
        streamBitrate = bitrateKbps * 1024
        Log.d(TAG, "Stream settings updated: ${streamWidth}x${streamHeight} @ ${streamFps} FPS (${bitrateKbps} Kbps)")
    }

    companion object {
        const val SKYDROID_URL = "rtsp://192.168.144.108:554/stream=0"
        const val SIYI_URL = "rtsp://192.168.144.25:8554/main.264"
    }

    /**
     * Concurrent, non-blocking parallel probe of both SIYI and Skydroid cameras.
     * Returns Pair(isReachable, resolvedWorkingUrl) in under 100ms.
     */
    fun probeEthernetCameraWithAutoDetect(inputUrl: String = defaultInputRtspUrl): Pair<Boolean, String> {
        if (isStreaming && activeSource == VideoSource.ETHERNET_CAMERA && ethernetRelay.isRunning.get()) {
            return Pair(true, inputUrl)
        }

        val primary = if (inputUrl.isBlank()) SIYI_URL else inputUrl
        val alternate = if (primary.contains(".25")) SKYDROID_URL else SIYI_URL

        var primaryResult: Pair<Boolean, String>? = null
        var alternateResult: Pair<Boolean, String>? = null

        val t1 = Thread({
            primaryResult = EthernetRtspRelayEngine.probeCameraDetailed(context, primary, timeoutMs = 1200)
        }, "ProbePrimary")

        val t2 = Thread({
            alternateResult = EthernetRtspRelayEngine.probeCameraDetailed(context, alternate, timeoutMs = 1200)
        }, "ProbeAlternate")

        t1.start()
        t2.start()

        try {
            t1.join(1500)
            t2.join(1500)
        } catch (_: InterruptedException) {}

        if (primaryResult?.first == true) {
            isEthernetAvailable = true
            Log.d(TAG, "Parallel probe: Found primary camera $primary (${primaryResult?.second})")
            return Pair(true, primary)
        }

        if (alternateResult?.first == true) {
            isEthernetAvailable = true
            Log.d(TAG, "Parallel probe: Found alternate camera $alternate (${alternateResult?.second})")
            return Pair(true, alternate)
        }

        isEthernetAvailable = false
        Log.d(TAG, "Parallel probe: Neither camera reachable")
        return Pair(false, primary)
    }

    fun probeEthernetCamera(inputUrl: String = defaultInputRtspUrl): Boolean {
        return probeEthernetCameraWithAutoDetect(inputUrl).first
    }

    fun autoDetectSourceOnStartup(inputUrl: String = defaultInputRtspUrl) {
        Executors.newSingleThreadExecutor().execute {
            val (reachable, resolved) = probeEthernetCameraWithAutoDetect(inputUrl)
            if (reachable) {
                activeSource = VideoSource.ETHERNET_CAMERA
                val label = if (resolved.contains(".25")) "ETHERNET CAMERA (SIYI)" else "ETHERNET CAMERA (SKYDROID)"
                onVideoSourceChanged?.invoke(VideoSource.ETHERNET_CAMERA, label)
                onLogMessage?.invoke("[VIDEO-DETECT] Ethernet Camera detected on LAN: $label ($resolved)")
            } else {
                activeSource = VideoSource.INTERNAL_PHONE
                onVideoSourceChanged?.invoke(VideoSource.INTERNAL_PHONE, "INTERNAL PHONE CAMERA")
            }
        }
    }

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameProcessed: (fps: Int) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                var frameCount = 0
                var lastTime = System.currentTimeMillis()

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(cameraExecutor) { imageProxy ->
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 1000) {
                                onFrameProcessed(frameCount)
                                onFpsUpdate?.invoke(frameCount)
                                frameCount = 0
                                lastTime = now
                            }
                            imageProxy.close()
                        }
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(currentLensFacing)
                    .build()

                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                Log.d(TAG, "Camera preview started (${streamWidth}x${streamHeight})")
            } catch (e: Exception) {
                Log.e(TAG, "Camera preview error: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera(lifecycleOwner, previewView)
    }

    fun attachOpenGlView(openGlView: OpenGlView) {
        openGlViewRef = openGlView
        if (rtspCamera2 == null) {
            rtspCamera2 = RtspCamera2(openGlView, this)
            openGlView.post {
                try {
                    if (rtspCamera2?.isOnPreview == false) {
                        rtspCamera2?.startPreview()
                    }
                    val autoUrl = pendingAutoStreamUrl
                    val autoInUrl = pendingInputRtspUrl ?: defaultInputRtspUrl
                    if (autoUrl != null) {
                        pendingAutoStreamUrl = null
                        pendingInputRtspUrl = null
                        Log.d(TAG, "Auto-starting queued video stream to $autoUrl (Input: $autoInUrl)")
                        start5GVideoStream(autoUrl, autoInUrl) { active, msg ->
                            onStreamStateChanged?.invoke(active, msg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting OpenGlView preview: ${e.localizedMessage}")
                }
            }
        }
    }

    fun triggerAutoStreamRefresh(delayMs: Long = 1500) {
        if (!userWantsStreaming.get()) return
        reconnectExecutor.schedule({
            if (!userWantsStreaming.get()) return@schedule
            Log.d(TAG, "Triggering automatic stream refresh...")
            val output = defaultCloudRtspUrl
            val input = pendingInputRtspUrl ?: defaultInputRtspUrl

            val (ok, resolved) = probeEthernetCameraWithAutoDetect(input)
            if (ok && userWantsStreaming.get()) {
                switchVideoSource(VideoSource.ETHERNET_CAMERA, output, resolved) { active, msg ->
                    if (userWantsStreaming.get()) onStreamStateChanged?.invoke(true, msg)
                }
            } else if (userWantsStreaming.get()) {
                switchVideoSource(VideoSource.INTERNAL_PHONE, output, input) { active, msg ->
                    if (userWantsStreaming.get()) onStreamStateChanged?.invoke(true, msg)
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun queueAutoStartStream(outputRtspUrl: String = defaultCloudRtspUrl, inputRtspUrl: String = defaultInputRtspUrl) {
        userWantsStreaming.set(true)
        val targetUrl = if (outputRtspUrl.isBlank()) defaultCloudRtspUrl else outputRtspUrl
        val inputUrl = if (inputRtspUrl.isBlank()) defaultInputRtspUrl else inputRtspUrl
        if (isStreaming) {
            Log.d(TAG, "Video stream already active, skipping auto-start")
            return
        }

        // Fast probe check in background thread
        Executors.newSingleThreadExecutor().execute {
            val ethOnline = probeEthernetCamera(inputUrl)
            if (ethOnline) {
                Log.d(TAG, "Ethernet Camera detected online, auto-starting relay immediately to $targetUrl")
                start5GVideoStream(targetUrl, inputUrl) { active, msg ->
                    onStreamStateChanged?.invoke(active, msg)
                }
            } else {
                val rtsp = rtspCamera2
                if (rtsp != null && rtsp.isOnPreview) {
                    Log.d(TAG, "Phone camera ready, starting auto-stream to $targetUrl immediately")
                    start5GVideoStream(targetUrl, inputUrl) { active, msg ->
                        onStreamStateChanged?.invoke(active, msg)
                    }
                } else {
                    Log.d(TAG, "Queueing auto-stream to $targetUrl pending OpenGlView preview ready")
                    pendingAutoStreamUrl = targetUrl
                    pendingInputRtspUrl = inputUrl
                }
            }
        }
    }

    /**
     * Smart Auto-Detection Streaming Entry Point:
     * - Probes if Ethernet Camera is responding on the local network.
     * - If YES: Uses Ethernet Camera (Relay).
     * - If NO: Automatically uses Phone Internal Camera (RtspCamera2).
     */
    fun start5GVideoStream(
        outputRtspUrl: String = defaultCloudRtspUrl,
        inputRtspUrl: String = defaultInputRtspUrl,
        onStatusChange: (Boolean, String) -> Unit
    ) {
        userWantsStreaming.set(true)
        isStreaming = true
        statusListener = onStatusChange
        val targetOutput = if (outputRtspUrl.isBlank()) defaultCloudRtspUrl else outputRtspUrl
        val targetInput = if (inputRtspUrl.isBlank()) defaultInputRtspUrl else inputRtspUrl

        // If already streaming via Ethernet relay, don't interrupt or re-probe
        if (isStreaming && activeSource == VideoSource.ETHERNET_CAMERA && ethernetRelay.isRunning.get()) {
            Log.d(TAG, "Ethernet camera stream already running, ignoring duplicate start request.")
            onStatusChange(true, "LIVE: Ethernet Camera -> Cloud RTSP ($targetOutput)")
            return
        }

        if (!isStartingStream.compareAndSet(false, true)) {
            Log.d(TAG, "Stream start already in progress, ignoring duplicate concurrent call.")
            return
        }

        Executors.newSingleThreadExecutor().execute {
            try {
                if (isStreaming && activeSource == VideoSource.ETHERNET_CAMERA && ethernetRelay.isRunning.get()) {
                    return@execute
                }

                onLogMessage?.invoke("[VIDEO] Auto-detecting Ethernet Camera ($targetInput)...")
                val (ethReachable, resolvedInput) = probeEthernetCameraWithAutoDetect(targetInput)

                if (ethReachable) {
                    // --- 1. ETHERNET CAMERA DETECTED ---
                    stopEthernetAutoRecoveryWatcher()
                    activeSource = VideoSource.ETHERNET_CAMERA
                    val camLabel = if (resolvedInput.contains(".25")) "ETHERNET CAMERA (SIYI)" else "ETHERNET CAMERA (SKYDROID)"
                    onVideoSourceChanged?.invoke(VideoSource.ETHERNET_CAMERA, camLabel)
                    onLogMessage?.invoke("[VIDEO] $camLabel online ($resolvedInput)! Starting relay.")

                    // Stop local phone encoder if running and wait for cloud session to close cleanly
                    if (rtspCamera2?.isStreaming == true) {
                        rtspCamera2?.stopStream()
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                    }

                    // Start relay pipeline
                    isStreaming = true
                    ethernetRelay.startRelay(resolvedInput, targetOutput)
                    onStatusChange(true, "LIVE: Ethernet Camera -> Cloud RTSP ($targetOutput)")
                } else {
                    // --- 2. FALLBACK TO INTERNAL PHONE CAMERA ---
                    activeSource = VideoSource.INTERNAL_PHONE
                    onVideoSourceChanged?.invoke(VideoSource.INTERNAL_PHONE, "INTERNAL PHONE CAMERA")
                    onLogMessage?.invoke("[VIDEO] Ethernet Camera offline. Auto-selecting Phone Internal Camera.")

                    // Stop ethernet relay if active
                    if (ethernetRelay.isRunning.get()) ethernetRelay.stopRelay()

                    startInternalCameraStream(targetOutput, onStatusChange)

                    // Start background watcher to auto-switch the moment Ethernet camera appears
                    startEthernetAutoRecoveryWatcher(targetOutput, targetInput)
                }
            } finally {
                isStartingStream.set(false)
            }
        }
    }

    /**
     * Seamlessly hot-switches the active video source between Phone Camera and Ethernet Camera.
     * If currently live streaming, cleanly migrates the RTSP feed to Cloud without stopping session.
     */
    fun switchVideoSource(
        newSource: VideoSource,
        outputRtspUrl: String = defaultCloudRtspUrl,
        inputRtspUrl: String = defaultInputRtspUrl,
        onStatusChange: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val targetOutput = if (outputRtspUrl.isBlank()) defaultCloudRtspUrl else outputRtspUrl
        val targetInput = if (inputRtspUrl.isBlank()) defaultInputRtspUrl else inputRtspUrl

        Executors.newSingleThreadExecutor().execute {
            if (newSource == VideoSource.ETHERNET_CAMERA) {
                stopEthernetAutoRecoveryWatcher()
                val (reachable, resolvedInput) = probeEthernetCameraWithAutoDetect(targetInput)
                if (reachable) {
                    activeSource = VideoSource.ETHERNET_CAMERA
                    val camLabel = if (resolvedInput.contains(".25")) "ETHERNET CAMERA (SIYI)" else "ETHERNET CAMERA (SKYDROID)"
                    onVideoSourceChanged?.invoke(VideoSource.ETHERNET_CAMERA, camLabel)
                    onLogMessage?.invoke("[VIDEO-SWITCH] Switched to $camLabel ($resolvedInput)")

                    if (isStreaming) {
                        if (rtspCamera2?.isStreaming == true) {
                            rtspCamera2?.stopStream()
                            try { Thread.sleep(400) } catch (_: InterruptedException) {}
                        }
                        ethernetRelay.startRelay(resolvedInput, targetOutput)
                        onStatusChange(true, "LIVE: Ethernet Camera -> Cloud RTSP ($targetOutput)")
                    }
                } else {
                    onLogMessage?.invoke("[VIDEO-SWITCH] Cannot switch: Ethernet Camera offline on LAN.")
                }
            } else {
                activeSource = VideoSource.INTERNAL_PHONE
                onVideoSourceChanged?.invoke(VideoSource.INTERNAL_PHONE, "INTERNAL PHONE CAMERA")
                onLogMessage?.invoke("[VIDEO-SWITCH] Switched to Internal Phone Camera")

                if (isStreaming) {
                    if (ethernetRelay.isRunning.get()) {
                        ethernetRelay.stopRelay()
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                    }
                    startInternalCameraStream(targetOutput, onStatusChange)
                    startEthernetAutoRecoveryWatcher(targetOutput, targetInput)
                }
            }
        }
    }

    private fun startInternalCameraStream(targetUrl: String, onStatusChange: (Boolean, String) -> Unit) {
        val rtsp = rtspCamera2
        if (rtsp == null) {
            isStreaming = false
            onStatusChange(false, "Phone Camera Surface Not Ready (OpenGlView missing)")
            return
        }

        if (!rtsp.isStreaming) {
            if (!rtsp.isOnPreview) {
                try {
                    rtsp.startPreview()
                } catch (e: Exception) {
                    Log.e(TAG, "Preview error before stream: ${e.localizedMessage}")
                }
            }

            var videoPrepared = try {
                rtsp.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, 1, 0)
            } catch (e: Exception) {
                false
            }

            if (!videoPrepared) {
                Log.w(TAG, "Primary config (${streamWidth}x${streamHeight}@${streamFps}fps) failed, trying 30 FPS target")
                videoPrepared = try {
                    rtsp.prepareVideo(streamWidth, streamHeight, 30, (streamBitrate * 0.8).toInt(), 1, 0)
                } catch (e: Exception) {
                    false
                }
            }

            if (!videoPrepared) {
                Log.w(TAG, "Falling back to 1280x720 @ 30 FPS (2.0 Mbps)")
                videoPrepared = try {
                    rtsp.prepareVideo(1280, 720, 30, 2000 * 1024, 1, 0)
                } catch (e: Exception) {
                    false
                }
            }

            if (!videoPrepared) {
                Log.w(TAG, "Falling back to 640x480 @ 30 FPS (1.0 Mbps)")
                videoPrepared = try {
                    rtsp.prepareVideo(640, 480, 30, 1000 * 1024, 1, 0)
                } catch (e: Exception) {
                    false
                }
            }

            val audioPrepared = try { rtsp.prepareAudio() } catch (e: Exception) { false }
            Log.d(TAG, "prepareVideo: $videoPrepared, prepareAudio: $audioPrepared")

            if (videoPrepared) {
                try {
                    rtsp.startStream(targetUrl)
                    isStreaming = true
                    onStatusChange(true, "Connecting Phone Camera to $targetUrl...")
                } catch (e: Exception) {
                    isStreaming = false
                    onStatusChange(false, "RTSP Socket Error: ${e.localizedMessage}")
                }
            } else {
                isStreaming = false
                onStatusChange(false, "Hardware MediaCodec Encoder Preparation Failed")
            }
        } else {
            onStatusChange(true, "Stream already active to $targetUrl")
        }
    }

    private fun fallbackToInternalCamera() {
        if (!isStreaming) return
        activeSource = VideoSource.INTERNAL_PHONE
        onVideoSourceChanged?.invoke(VideoSource.INTERNAL_PHONE, "INTERNAL PHONE CAMERA (FAILOVER)")
        startInternalCameraStream(defaultCloudRtspUrl) { active, msg ->
            isStreaming = active
            onStreamStateChanged?.invoke(active, msg)
        }
        startEthernetAutoRecoveryWatcher(defaultCloudRtspUrl, pendingInputRtspUrl ?: defaultInputRtspUrl)
    }

    fun stop5GVideoStream(onStatusChange: (Boolean, String) -> Unit) {
        userWantsStreaming.set(false)
        stopEthernetAutoRecoveryWatcher()
        if (ethernetRelay.isRunning.get()) {
            ethernetRelay.stopRelay()
        }
        rtspCamera2?.let { rtsp ->
            if (rtsp.isStreaming) {
                rtsp.stopStream()
            }
        }
        isStreaming = false
        onStatusChange(false, "5G camera stream stopped")
        onStreamStateChanged?.invoke(false, "5G camera stream stopped")
    }

    fun stopCamera() {
        try {
            stopEthernetAutoRecoveryWatcher()
            if (ethernetRelay.isRunning.get()) ethernetRelay.stopRelay()
            cameraProvider?.unbindAll()
            rtspCamera2?.stopStream()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shutdown() {
        userWantsStreaming.set(false)
        stopCamera()
        try {
            networkCallback?.let { cb ->
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
        reconnectExecutor.shutdown()
    }

    // --- ConnectChecker Callbacks ---

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Phone Camera RTSP Connection Started: $url")
    }

    override fun onConnectionSuccess() {
        isStreaming = true
        Log.d(TAG, "Phone Camera RTSP Stream Successfully Published: $defaultCloudRtspUrl")
        val msg = "LIVE PHONE CAMERA: $defaultCloudRtspUrl"
        statusListener?.invoke(true, msg)
        onStreamStateChanged?.invoke(true, msg)
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Phone Camera RTSP Connection Failed: $reason")
        if (userWantsStreaming.get()) {
            isStreaming = true
            val msg = "RTSP Break ($reason). Auto-refreshing in 1.5s..."
            onLogMessage?.invoke("[VIDEO-AUTO-REFRESH] RTSP break: $reason. Auto-reconnecting in 1.5s...")
            statusListener?.invoke(true, msg)
            onStreamStateChanged?.invoke(true, msg)
            triggerAutoStreamRefresh(1500)
        } else {
            isStreaming = false
            val msg = "RTSP Connect Failed: $reason (Check 5G connection / Cloud Server port 8554)"
            statusListener?.invoke(false, msg)
            onStreamStateChanged?.invoke(false, msg)
        }
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        Log.d(TAG, "Phone Camera RTSP Stream Disconnected")
        if (userWantsStreaming.get()) {
            isStreaming = true
            val msg = "RTSP Stream Break. Auto-refreshing in 1.5s..."
            onLogMessage?.invoke("[VIDEO-AUTO-REFRESH] RTSP stream disconnected. Auto-refreshing in 1.5s...")
            statusListener?.invoke(true, msg)
            onStreamStateChanged?.invoke(true, msg)
            triggerAutoStreamRefresh(1500)
        } else {
            isStreaming = false
            val msg = "RTSP Stream Disconnected"
            statusListener?.invoke(false, msg)
            onStreamStateChanged?.invoke(false, msg)
        }
    }

    override fun onAuthError() {
        Log.e(TAG, "Phone Camera RTSP Auth Error")
        if (userWantsStreaming.get()) {
            isStreaming = true
            val msg = "RTSP Auth Reset. Auto-refreshing in 2s..."
            onLogMessage?.invoke("[VIDEO-AUTO-REFRESH] RTSP auth reset. Auto-refreshing in 2s...")
            statusListener?.invoke(true, msg)
            onStreamStateChanged?.invoke(true, msg)
            triggerAutoStreamRefresh(2000)
        } else {
            isStreaming = false
            statusListener?.invoke(false, "RTSP Server Authentication Error")
        }
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "Phone Camera RTSP Auth Success")
    }
}
