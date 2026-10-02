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

class CameraStreamManager(
    private val context: Context
) : ConnectChecker {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var rtspCamera2: RtspCamera2? = null

    var defaultCloudRtspUrl = "rtsp://64.227.133.143:8554/mystream1"
    var streamWidth = 1280
    var streamHeight = 720
    var streamFps = 30
    var streamBitrate = 2000 * 1024 // 2.0 Mbps 720p HD

    var isStreaming = false
        private set

    var currentLensFacing = CameraSelector.LENS_FACING_BACK
        private set

    private var statusListener: ((Boolean, String) -> Unit)? = null
    var onFpsUpdate: ((Int) -> Unit)? = null
    var onStreamStateChanged: ((Boolean, String) -> Unit)? = null
    private var pendingAutoStreamUrl: String? = null

    fun updateStreamSettings(width: Int, height: Int, fps: Int, bitrateKbps: Int) {
        streamWidth = width
        streamHeight = height
        streamFps = fps
        streamBitrate = bitrateKbps * 1024
        Log.d("CameraStreamManager", "Stream settings updated: ${streamWidth}x${streamHeight} @ ${streamFps} FPS (${bitrateKbps} Kbps)")
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

                Log.d("CameraStreamManager", "Camera preview started (${streamWidth}x${streamHeight})")
            } catch (e: Exception) {
                Log.e("CameraStreamManager", "Camera preview error: ${e.localizedMessage}")
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
        if (rtspCamera2 == null) {
            rtspCamera2 = RtspCamera2(openGlView, this)
            openGlView.post {
                try {
                    if (rtspCamera2?.isOnPreview == false) {
                        rtspCamera2?.startPreview()
                    }
                    val autoUrl = pendingAutoStreamUrl
                    if (autoUrl != null) {
                        pendingAutoStreamUrl = null
                        Log.d("CameraStreamManager", "Auto-starting queued video stream to $autoUrl")
                        start5GVideoStream(autoUrl) { active, msg ->
                            onStreamStateChanged?.invoke(active, msg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CameraStreamManager", "Error starting OpenGlView preview: ${e.localizedMessage}")
                }
            }
        }
    }

    fun queueAutoStartStream(outputRtspUrl: String = defaultCloudRtspUrl) {
        val targetUrl = if (outputRtspUrl.isBlank()) defaultCloudRtspUrl else outputRtspUrl
        if (isStreaming) {
            Log.d("CameraStreamManager", "Video stream already active, skipping auto-start")
            return
        }
        val rtsp = rtspCamera2
        if (rtsp != null && rtsp.isOnPreview) {
            Log.d("CameraStreamManager", "Camera ready, starting auto-stream to $targetUrl immediately")
            start5GVideoStream(targetUrl) { active, msg ->
                onStreamStateChanged?.invoke(active, msg)
            }
        } else {
            Log.d("CameraStreamManager", "Queueing auto-stream to $targetUrl pending OpenGlView preview ready")
            pendingAutoStreamUrl = targetUrl
        }
    }

    fun start5GVideoStream(outputRtspUrl: String = defaultCloudRtspUrl, onStatusChange: (Boolean, String) -> Unit) {
        statusListener = onStatusChange
        val targetUrl = if (outputRtspUrl.isBlank()) defaultCloudRtspUrl else outputRtspUrl

        val rtsp = rtspCamera2
        if (rtsp == null) {
            isStreaming = false
            onStatusChange(false, "Camera Surface Not Ready (OpenGlView missing)")
            return
        }

        if (!rtsp.isStreaming) {
            if (!rtsp.isOnPreview) {
                try {
                    rtsp.startPreview()
                } catch (e: Exception) {
                    Log.e("CameraStreamManager", "Preview error before stream: ${e.localizedMessage}")
                }
            }

            var videoPrepared = try {
                rtsp.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, 1, 0)
            } catch (e: Exception) {
                false
            }

            if (!videoPrepared) {
                Log.w("CameraStreamManager", "Primary config (${streamWidth}x${streamHeight}@${streamFps}fps) failed, trying 30 FPS target")
                videoPrepared = try {
                    rtsp.prepareVideo(streamWidth, streamHeight, 30, (streamBitrate * 0.8).toInt(), 1, 0)
                } catch (e: Exception) {
                    false
                }
            }

            if (!videoPrepared) {
                Log.w("CameraStreamManager", "Falling back to 1280x720 @ 30 FPS (2.0 Mbps)")
                videoPrepared = try {
                    rtsp.prepareVideo(1280, 720, 30, 2000 * 1024, 1, 0)
                } catch (e: Exception) {
                    false
                }
            }

            if (!videoPrepared) {
                Log.w("CameraStreamManager", "Falling back to 640x480 @ 30 FPS (1.0 Mbps)")
                videoPrepared = try {
                    rtsp.prepareVideo(640, 480, 30, 1000 * 1024, 1, 0)
                } catch (e: Exception) {
                    false
                }
            }

            val audioPrepared = try { rtsp.prepareAudio() } catch (e: Exception) { false }
            Log.d("CameraStreamManager", "prepareVideo: $videoPrepared, prepareAudio: $audioPrepared")

            if (videoPrepared) {
                try {
                    rtsp.startStream(targetUrl)
                    isStreaming = true
                    onStatusChange(true, "Connecting RTSP Stream to $targetUrl...")
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

    fun stop5GVideoStream(onStatusChange: (Boolean, String) -> Unit) {
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
        Log.d("CameraStreamManager", "RTSP Connection Started: $url")
    }

    override fun onConnectionSuccess() {
        isStreaming = true
        Log.d("CameraStreamManager", "RTSP Stream Successfully Published: $defaultCloudRtspUrl")
        val msg = "LIVE RTSP STREAM: $defaultCloudRtspUrl"
        statusListener?.invoke(true, msg)
        onStreamStateChanged?.invoke(true, msg)
    }

    override fun onConnectionFailed(reason: String) {
        isStreaming = false
        Log.e("CameraStreamManager", "RTSP Connection Failed: $reason")
        val msg = "RTSP Connect Failed: $reason (Check 5G connection / Cloud Server port 8554)"
        statusListener?.invoke(false, msg)
        onStreamStateChanged?.invoke(false, msg)
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        isStreaming = false
        Log.d("CameraStreamManager", "RTSP Stream Disconnected")
        val msg = "RTSP Stream Disconnected"
        statusListener?.invoke(false, msg)
        onStreamStateChanged?.invoke(false, msg)
    }

    override fun onAuthError() {
        isStreaming = false
        Log.e("CameraStreamManager", "RTSP Auth Error")
        statusListener?.invoke(false, "RTSP Server Authentication Error")
    }

    override fun onAuthSuccess() {
        Log.d("CameraStreamManager", "RTSP Auth Success")
    }
}
