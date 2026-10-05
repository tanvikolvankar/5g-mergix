package com.example.final_5gmergix

import android.content.Context
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

    // Ethernet RTSP Relay Engine
    val ethernetRelay = EthernetRtspRelayEngine(
        onLog = { msg -> onLogMessage?.invoke(msg) },
        onStateChange = { active, msg ->
            isStreaming = active
            onStreamStateChanged?.invoke(active, msg)
        },
        onRelayFailed = { reason ->
            Log.w(TAG, "Ethernet Camera stream dropped: $reason. Auto-falling back to internal phone camera!")
            onLogMessage?.invoke("[VIDEO-FAILOVER] Ethernet Camera disconnected ($reason). Switching to Internal Phone Camera...")
            // Fall back to phone camera
            fallbackToInternalCamera()
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

    fun updateStreamSettings(width: Int, height: Int, fps: Int, bitrateKbps: Int) {
        streamWidth = width
        streamHeight = height
        streamFps = fps
        streamBitrate = bitrateKbps * 1024
        Log.d(TAG, "Stream settings updated: ${streamWidth}x${streamHeight} @ ${streamFps} FPS (${bitrateKbps} Kbps)")
    }

    /**
     * Probes whether the external Ethernet camera (e.g. SIYI) is connected and responding.
     */
    fun probeEthernetCamera(inputUrl: String = defaultInputRtspUrl): Boolean {
        val reachable = EthernetRtspRelayEngine.probeCamera(inputUrl, timeoutMs = 1200)
        isEthernetAvailable = reachable
        Log.d(TAG, "Probe Ethernet Camera ($inputUrl): reachable=$reachable")
        return reachable
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

    fun queueAutoStartStream(outputRtspUrl: String = defaultCloudRtspUrl, inputRtspUrl: String = defaultInputRtspUrl) {
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
        statusListener = onStatusChange
        val targetOutput = if (outputRtspUrl.isBlank()) defaultCloudRtspUrl else outputRtspUrl
        val targetInput = if (inputRtspUrl.isBlank()) defaultInputRtspUrl else inputRtspUrl

        Executors.newSingleThreadExecutor().execute {
            onLogMessage?.invoke("[VIDEO] Probing Ethernet Camera at $targetInput...")
            val ethReachable = probeEthernetCamera(targetInput)

            if (ethReachable) {
                // --- 1. ETHERNET CAMERA DETECTED ---
                activeSource = VideoSource.ETHERNET_CAMERA
                onVideoSourceChanged?.invoke(VideoSource.ETHERNET_CAMERA, "ETHERNET CAMERA (SIYI)")
                onLogMessage?.invoke("[VIDEO] Ethernet Camera detected! Selecting Ethernet Camera as primary stream.")

                // Stop local phone encoder if running
                rtspCamera2?.let { if (it.isStreaming) it.stopStream() }

                // Start relay pipeline
                isStreaming = true
                ethernetRelay.startRelay(targetInput, targetOutput)
                onStatusChange(true, "LIVE: Ethernet Camera -> Cloud RTSP ($targetOutput)")
            } else {
                // --- 2. FALLBACK TO INTERNAL PHONE CAMERA ---
                activeSource = VideoSource.INTERNAL_PHONE
                onVideoSourceChanged?.invoke(VideoSource.INTERNAL_PHONE, "INTERNAL PHONE CAMERA")
                onLogMessage?.invoke("[VIDEO] Ethernet Camera offline. Auto-selecting Phone Internal Camera.")

                // Stop ethernet relay if active
                if (ethernetRelay.isRunning.get()) ethernetRelay.stopRelay()

                startInternalCameraStream(targetOutput, onStatusChange)
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
    }

    fun stop5GVideoStream(onStatusChange: (Boolean, String) -> Unit) {
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
    }

    fun stopCamera() {
        try {
            if (ethernetRelay.isRunning.get()) ethernetRelay.stopRelay()
            cameraProvider?.unbindAll()
            rtspCamera2?.stopStream()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
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
        isStreaming = false
        Log.e(TAG, "Phone Camera RTSP Connection Failed: $reason")
        val msg = "RTSP Connect Failed: $reason (Check 5G connection / Cloud Server port 8554)"
        statusListener?.invoke(false, msg)
        onStreamStateChanged?.invoke(false, msg)
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        isStreaming = false
        Log.d(TAG, "Phone Camera RTSP Stream Disconnected")
        val msg = "RTSP Stream Disconnected"
        statusListener?.invoke(false, msg)
        onStreamStateChanged?.invoke(false, msg)
    }

    override fun onAuthError() {
        isStreaming = false
        Log.e(TAG, "Phone Camera RTSP Auth Error")
        statusListener?.invoke(false, "RTSP Server Authentication Error")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "Phone Camera RTSP Auth Success")
    }
}
