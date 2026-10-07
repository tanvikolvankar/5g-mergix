package com.example.final_5gmergix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-CPU RTSP stream relay for external Ethernet / SIYI cameras.
 *
 * Connects to the local Ethernet camera (e.g., rtsp://192.168.144.108:554/stream=0),
 * negotiates SDP / RTSP transport, and transparently relays interleaved RTP H.264
 * packets over 5G to the cloud RTSP server (e.g., rtsp://YOUR_CLOUD_SERVER_IP:8554/mystream1).
 */
class EthernetRtspRelayEngine(
    private val context: Context? = null,
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
        fun bindSocketToEthernetIfPossible(context: Context?, socket: Socket): Boolean {
            if (context == null) return false
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val ethNetwork = cm.allNetworks.firstOrNull { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                }
                if (ethNetwork != null) {
                    ethNetwork.bindSocket(socket)
                    Log.d("EthernetRtspRelay", "Bound socket to TRANSPORT_ETHERNET interface")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w("EthernetRtspRelay", "Could not bind socket to Ethernet: ${e.message}")
                false
            }
        }

        /**
         * Probe with detailed diagnostic error string.
         */
        fun probeCameraDetailed(context: Context?, rtspUrl: String, timeoutMs: Int = 1500): Pair<Boolean, String> {
            return try {
                val uri = URI(rtspUrl.trim())
                val host = uri.host ?: "192.168.144.25"
                val port = if (uri.port > 0) uri.port else 8554
                // Attempt 1: bound to Ethernet interface if found
                try {
                    Socket().use { socket ->
                        val bound = bindSocketToEthernetIfPossible(context, socket)
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                        return Pair(true, "Connected successfully (ethBound=$bound)")
                    }
                } catch (_: Exception) {}

                // Attempt 2: direct system routing table
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                        return Pair(true, "Connected successfully (direct)")
                    }
                } catch (e: Exception) {
                    val msg = "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message ?: "timeout"}"
                    return Pair(false, msg)
                }
            } catch (e: Exception) {
                Pair(false, e.localizedMessage ?: "error")
            }
        }

        /**
         * Fast non-blocking probe to verify if the Ethernet camera is reachable.
         */
        fun probeCamera(rtspUrl: String, timeoutMs: Int = 2500): Boolean {
            return probeCameraDetailed(null, rtspUrl, timeoutMs).first
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
            val inHost = inUri.host ?: "192.168.144.108"
            val inPort = if (inUri.port > 0) inUri.port else 554

            val outUri = URI(outputUrl)
            val outHost = outUri.host ?: "YOUR_CLOUD_SERVER_IP"
            val outPort = if (outUri.port > 0) outUri.port else 8554

            // 1. Connect to Camera (bound directly to USB Ethernet interface)
            cameraSocket = Socket().apply {
                soTimeout = 5000
                tcpNoDelay = true
                bindSocketToEthernetIfPossible(context, this)
                connect(InetSocketAddress(inHost, inPort), 4000)
            }
            log("Connected to Ethernet Camera socket at $inHost:$inPort")

            val camIn = cameraSocket.getInputStream()
            val camOut = cameraSocket.getOutputStream()

            var camCseq = 1

            // --- Step A: Get SDP from Camera first ---
            // 1. OPTIONS Camera
            sendRtspRequest(camOut, "OPTIONS $inputUrl RTSP/1.0\r\nCSeq: ${camCseq++}\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val optResp = readRtspResponse(camIn)
            log("Camera OPTIONS: ${optResp.lines().firstOrNull() ?: ""}")

            // 2. DESCRIBE Camera
            sendRtspRequest(camOut, "DESCRIBE $inputUrl RTSP/1.0\r\nCSeq: ${camCseq++}\r\nAccept: application/sdp\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val describeResp = readRtspResponse(camIn)
            log("Camera DESCRIBE: ${describeResp.lines().firstOrNull() ?: ""}")
            val sdp = describeResp.substringAfter("\r\n\r\n", "")

            // Extract camera track
            val camTrackControl = extractTrackControl(sdp)
            val camTrackUrl = when {
                camTrackControl.startsWith("rtsp://", ignoreCase = true) -> camTrackControl
                inputUrl.endsWith("/") -> "$inputUrl$camTrackControl"
                else -> "$inputUrl/$camTrackControl"
            }

            // --- Step B: Connect and Arm Cloud Publisher IMMEDIATELY (no idle delay) ---
            cloudSocket = Socket().apply {
                soTimeout = 5000
                tcpNoDelay = true
                connect(InetSocketAddress(outHost, outPort), 4000)
            }
            log("Connected to Cloud RTSP Server at $outHost:$outPort")

            val cloudIn = cloudSocket.getInputStream()
            val cloudOut = cloudSocket.getOutputStream()

            var cloudCseq = 1

            // 3. OPTIONS Cloud
            sendRtspRequest(cloudOut, "OPTIONS $outputUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val cloudOptResp = readRtspResponse(cloudIn)
            log("Cloud OPTIONS: ${cloudOptResp.lines().firstOrNull() ?: ""}")

            // 4. ANNOUNCE Cloud (using Camera's SDP)
            val sdpContent = if (sdp.isNotBlank()) sdp else "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=stream\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\n"
            sendRtspRequest(cloudOut, "ANNOUNCE $outputUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\nContent-Type: application/sdp\r\nContent-Length: ${sdpContent.length}\r\nUser-Agent: MergixRelay/1.0\r\n\r\n$sdpContent")
            val annResp = readRtspResponse(cloudIn)
            log("Cloud ANNOUNCE: ${annResp.lines().firstOrNull() ?: ""}")

            // Extract matching cloud track
            val cloudTrackControl = extractTrackControl(sdpContent)
            val cloudTrackUrl = when {
                cloudTrackControl.startsWith("rtsp://", ignoreCase = true) -> cloudTrackControl
                outputUrl.endsWith("/") -> "$outputUrl$cloudTrackControl"
                else -> "$outputUrl/$cloudTrackControl"
            }

            // 5. SETUP Cloud Track (interleaved 0-1)
            sendRtspRequest(cloudOut, "SETUP $cloudTrackUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val cloudSetupResp = readRtspResponse(cloudIn)
            log("Cloud SETUP ($cloudTrackControl): ${cloudSetupResp.lines().firstOrNull() ?: ""}")
            val cloudSession = extractSession(cloudSetupResp)
            val cloudSessionHeader = if (cloudSession.isNotBlank()) "Session: $cloudSession\r\n" else ""

            // 6. RECORD Cloud (Cloud is now 100% armed and waiting for live video packets!)
            sendRtspRequest(cloudOut, "RECORD $outputUrl RTSP/1.0\r\nCSeq: ${cloudCseq++}\r\n${cloudSessionHeader}Range: npt=0.000-\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val recResp = readRtspResponse(cloudIn)
            log("Cloud RECORD: ${recResp.lines().firstOrNull() ?: ""}")

            // --- Step C: Start Camera Stream ---
            // 7. SETUP Camera Track (interleaved 0-1)
            sendRtspRequest(camOut, "SETUP $camTrackUrl RTSP/1.0\r\nCSeq: ${camCseq++}\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")
            val setupResp = readRtspResponse(camIn)
            log("Camera SETUP ($camTrackControl): ${setupResp.lines().firstOrNull() ?: ""}")
            val camSession = extractSession(setupResp)
            val camSessionHeader = if (camSession.isNotBlank()) "Session: $camSession\r\n" else ""

            // 8. PLAY Camera (video frames begin arriving)
            sendRtspRequest(camOut, "PLAY $inputUrl RTSP/1.0\r\nCSeq: ${camCseq++}\r\n${camSessionHeader}Range: npt=0.000-\r\nUser-Agent: MergixRelay/1.0\r\n\r\n")

            // Safe peek: if binary RTP frame ($) arrives first, don't corrupt it with text parser!
            cameraSocket.soTimeout = 4000
            val pushback = PushbackInputStream(camIn, 8)
            val firstByte = pushback.read()
            if (firstByte != -1) {
                if (firstByte == '$'.code) {
                    pushback.unread(firstByte)
                    log("Camera PLAY: Live interleaved binary stream began immediately ($)")
                } else {
                    pushback.unread(firstByte)
                    val playResp = readRtspResponse(pushback)
                    log("Camera PLAY: ${playResp.lines().firstOrNull() ?: ""}")
                }
            }

            log("LIVE: Ethernet Camera successfully relayed to $outputUrl")
            onStateChange(true, "LIVE: Ethernet Camera -> Cloud RTSP")

            // --- Step D: Framed RTP Data Pipe (Filters out interleaved RTSP responses) ---
            val header = ByteArray(4)
            val packetBuf = ByteArray(65536)

            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val b = pushback.read()
                if (b == -1) {
                    log("Ethernet Camera stream closed by sender.")
                    break
                }

                if (b == '$'.code) {
                    // Interleaved binary RTP/RTCP frame: $ (1 byte) + channel (1 byte) + length (2 bytes)
                    header[0] = b.toByte()
                    readExact(pushback, header, 1, 3)
                    val flen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

                    if (flen > 0) {
                        val targetBuf = if (flen <= packetBuf.size) packetBuf else ByteArray(flen)
                        readExact(pushback, targetBuf, 0, flen)
                        cloudOut.write(header, 0, 4)
                        cloudOut.write(targetBuf, 0, flen)
                        bytesRelayed += (4 + flen)
                        packetsRelayed++
                    }
                } else if (b == 'R'.code) {
                    // Interleaved delayed RTSP text response from camera (e.g. 'RTSP/1.0 200 OK\r\n...')
                    // Must NOT be forwarded to Cloud MediaMTX or MediaMTX will abort the connection!
                    val textBytes = java.io.ByteArrayOutputStream()
                    textBytes.write(b)
                    while (true) {
                        val tb = pushback.read()
                        if (tb == -1) break
                        textBytes.write(tb)
                        val s = textBytes.size()
                        val arr = textBytes.toByteArray()
                        if (s >= 4 && arr[s-4] == '\r'.code.toByte() && arr[s-3] == '\n'.code.toByte() &&
                            arr[s-2] == '\r'.code.toByte() && arr[s-1] == '\n'.code.toByte()) {
                            break
                        }
                    }
                    val textStr = textBytes.toString(Charsets.US_ASCII.name()).trim()
                    log("Filtered out delayed camera RTSP response: ${textStr.lines().firstOrNull() ?: ""}")
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

    private fun readExact(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val r = input.read(buffer, offset + totalRead, length - totalRead)
            if (r < 0) throw java.io.EOFException("Unexpected end of stream")
            totalRead += r
        }
    }

    /**
     * Byte-accurate reader that leaves the raw InputStream intact for binary RTP streaming.
     */
    private fun readRtspResponse(input: InputStream): String {
        val headerBytes = java.io.ByteArrayOutputStream()
        var matched = 0

        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBytes.write(b)
            val size = headerBytes.size()
            if (size >= 4) {
                val arr = headerBytes.toByteArray()
                if (arr[size - 4] == '\r'.code.toByte() && arr[size - 3] == '\n'.code.toByte() &&
                    arr[size - 2] == '\r'.code.toByte() && arr[size - 1] == '\n'.code.toByte()) {
                    break
                }
            }
        }

        val headerStr = headerBytes.toString(Charsets.US_ASCII.name())
        var contentLength = 0
        for (line in headerStr.lines()) {
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }

        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = input.read(body, read, contentLength - read)
                if (r < 0) break
                read += r
            }
            return headerStr + String(body, 0, read, Charsets.US_ASCII)
        }

        return headerStr
    }

    private fun extractTrackControl(sdp: String): String {
        for (line in sdp.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("a=control:") && !trimmed.contains("*")) {
                val control = trimmed.substringAfter("a=control:").trim()
                if (control.isNotBlank()) return control
            }
        }
        return "trackID=1"
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
