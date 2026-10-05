package com.example.final_5gmergix

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-CPU RTSP stream relay for external Ethernet / SIYI cameras.
 *
 * Connects to the local Ethernet camera (e.g., rtsp://192.168.144.25:8554/main.264),
 * negotiates SDP / RTSP transport, and transparently relays interleaved RTP H.264
 * packets over 5G to the cloud RTSP server (e.g., rtsp://64.227.133.143:8554/mystream1).
 */
class EthernetRtspRelayEngine(
    private val onLog: (String) -> Unit = {},
    private val onStateChange: (Boolean, String) -> Unit = { _, _ -> },
    private val onRelayFailed: (reason: String) -> Unit = {}
) {
    private val TAG = "EthernetRtspRelay"

    val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    var bytesRelayed: Long = 0
        private set
    var packetsRelayed: Long = 0
        private set

    companion object {
        /**
         * Fast non-blocking probe to verify if the Ethernet camera is reachable.
         */
        fun probeCamera(rtspUrl: String, timeoutMs: Int = 1200): Boolean {
            return try {
                val uri = URI(rtspUrl.trim())
                val host = uri.host ?: "192.168.144.25"
                val port = if (uri.port > 0) uri.port else 8554
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun startRelay(inputUrl: String, outputUrl: String) {
        if (isRunning.get()) {
            log("Relay already active.")
            return
        }

        isRunning.set(true)
        bytesRelayed = 0
        packetsRelayed = 0

        workerThread = Thread({
            runRelayPipeline(inputUrl.trim(), outputUrl.trim())
        }, "EthernetRtspRelayWorker").apply {
            isDaemon = true
            start()
        }
    }

    fun stopRelay() {
        if (!isRunning.getAndSet(false)) return
        log("Stopping Ethernet RTSP relay...")
        workerThread?.interrupt()
        workerThread = null
        onStateChange(false, "Ethernet RTSP Relay Stopped")
    }

    private fun runRelayPipeline(inputUrl: String, outputUrl: String) {
        log("Starting RTSP Pipeline: $inputUrl -> $outputUrl")
        onStateChange(true, "Connecting to Ethernet Camera ($inputUrl)...")

        var cameraSocket: Socket? = null
        var cloudSocket: Socket? = null

        try {
            val inUri = URI(inputUrl)
            val inHost = inUri.host ?: "192.168.144.25"
            val inPort = if (inUri.port > 0) inUri.port else 8554

            val outUri = URI(outputUrl)
            val outHost = outUri.host ?: "64.227.133.143"
            val outPort = if (outUri.port > 0) outUri.port else 8554

            // 1. Connect to Camera
            cameraSocket = Socket().apply {
                soTimeout = 5000
                tcpNoDelay = true
                connect(InetSocketAddress(inHost, inPort), 3000)
            }
            log("Connected to Ethernet Camera socket at $inHost:$inPort")

            // 2. Connect to Cloud RTSP Server
            cloudSocket = Socket().apply {
                soTimeout = 5000
                tcpNoDelay = true
                connect(InetSocketAddress(outHost, outPort), 4000)
            }
            log("Connected to Cloud RTSP Server at $outHost:$outPort")

            val camIn = cameraSocket.getInputStream()
            val camOut = cameraSocket.getOutputStream()

            val cloudIn = cloudSocket.getInputStream()
            val cloudOut = cloudSocket.getOutputStream()

            var cseq = 1

            // --- RTSP Handshake with Camera ---
            // OPTIONS
            sendRtspRequest(camOut, "OPTIONS $inputUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            readRtspResponse(camIn)

            // DESCRIBE
            sendRtspRequest(camOut, "DESCRIBE $inputUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\nAccept: application/sdp\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val describeResp = readRtspResponse(camIn)
            val sdp = describeResp.substringAfter("\r\n\r\n", "")

            // SETUP Camera Track (interleaved 0-1)
            val trackUrl = if (inputUrl.endsWith("/")) "${inputUrl}track0" else "$inputUrl/track0"
            sendRtspRequest(camOut, "SETUP $trackUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val setupResp = readRtspResponse(camIn)
            val session = extractSession(setupResp)

            // PLAY Camera
            val sessionHeader = if (session.isNotBlank()) "Session: $session\r\n" else ""
            sendRtspRequest(camOut, "PLAY $inputUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\n${sessionHeader}Range: npt=0.000-\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            readRtspResponse(camIn)

            log("Camera stream active! Establishing Cloud Publisher...")

            // --- RTSP Handshake with Cloud Publisher ---
            var cloudCseq = 1
            // OPTIONS
            sendRtspRequest(cloudOut, "OPTIONS $outputUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            readRtspResponse(cloudIn)

            // ANNOUNCE (using SDP from camera)
            val sdpContent = if (sdp.isNotBlank()) sdp else "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=MergixSIYI\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
            sendRtspRequest(cloudOut, "ANNOUNCE $outputUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\nContent-Type: application/sdp\r\nContent-Length: ${sdpContent.length}\r\nUser-Agent: MergixRelay/1.0\r\n\r\n$sdpContent")
            readRtspResponse(cloudIn)

            // SETUP Cloud Track
            val cloudTrack = if (outputUrl.endsWith("/")) "${outputUrl}track0" else "$outputUrl/track0"
            sendRtspRequest(cloudOut, "SETUP $cloudTrack RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val cloudSetupResp = readRtspResponse(cloudIn)
            val cloudSession = extractSession(cloudSetupResp)
            val cloudSessionHeader = if (cloudSession.isNotBlank()) "Session: $cloudSession\r\n" else ""

            // RECORD Cloud
            sendRtspRequest(cloudOut, "RECORD $outputUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\n${cloudSessionHeader}Range: npt=0.000-\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            readRtspResponse(cloudIn)

            log("LIVE: Ethernet Camera successfully relayed to $outputUrl")
            onStateChange(true, "LIVE: Ethernet Camera -> Cloud RTSP")

            // 3. High-Speed Interleaved RTP Data Pipe (Camera -> Cloud)
            cameraSocket.soTimeout = 4000
            val buffer = ByteArray(16384)

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val len = camIn.read(buffer)
                if (len < 0) {
                    log("Ethernet Camera stream closed by sender.")
                    break
                }
                if (len > 0) {
                    cloudOut.write(buffer, 0, len)
                    bytesRelayed += len
                    packetsRelayed++
                }
            }

        } catch (e: Exception) {
            val err = e.localizedMessage ?: e.javaClass.simpleName
            log("RTSP Relay Error: $err")
            if (isRunning.get()) {
                onRelayFailed(err)
            }
        } finally {
            try { cameraSocket?.close() } catch (_: Exception) {}
            try { cloudSocket?.close() } catch (_: Exception) {}
            isRunning.set(false)
            onStateChange(false, "Ethernet RTSP Relay Stopped")
        }
    }

    private fun sendRtspRequest(out: OutputStream, req: String) {
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readRtspResponse(input: InputStream): String {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
        var contentLength = 0

        while (true) {
            val line = reader.readLine() ?: break
            sb.append(line).append("\r\n")
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            if (line.isEmpty()) {
                break
            }
        }

        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = reader.read(body, read, contentLength - read)
                if (r < 0) break
                read += r
            }
            sb.append(body, 0, read)
        }

        return sb.toString()
    }

    private fun extractSession(resp: String): String {
        return resp.lines()
            .firstOrNull { it.lowercase().startsWith("session:") }
            ?.substringAfter(":")
            ?.substringBefore(";")
            ?.trim() ?: ""
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog("[$TAG] $msg")
    }
}
