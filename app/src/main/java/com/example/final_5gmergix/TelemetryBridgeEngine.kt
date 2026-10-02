package com.example.final_5gmergix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class BridgeState {
    IDLE,
    SEARCHING_USB,
    CONNECTING_CLOUD,
    AUTHENTICATING,
    HEARTBEAT_PULSE,
    RELAY_STREAMING,
    ERROR
}

data class TelemetryStats(
    val state: BridgeState,
    val stateDescription: String,
    val isUsbConnected: Boolean,
    val usbDeviceName: String,
    val isCloudConnected: Boolean,
    val bytesReadFromUsb: Long,
    val packetsReadFromUsb: Long,
    val bytesSentToCloud: Long,
    val bytesReceivedFromCloud: Long,
    val lastHeartbeatMs: Long,
    val isRelayActive: Boolean
)

class TelemetryBridgeEngine(private val context: Context) {

    private val tag = "TelemetryBridgeEngine"
    private val usbActionPermission = "com.example.final_5gmergix.USB_PERMISSION"

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var isRunning = AtomicBoolean(false)

    val isBridgeRunning: Boolean
        get() = isRunning.get()

    // Bridge state
    @Volatile
    var currentState = BridgeState.IDLE
        private set

    @Volatile
    var currentStatusMessage = "Idle"
        private set

    // USB Hardware
    private var usbSerialPort: UsbSerialPort? = null
    var isUsbConnected = false
        private set
    var usbDeviceName = "None"
        private set

    // Network & Relay
    private var cloudSocket: Socket? = null
    var isCloudConnected = false
        private set
    var isRelayActive = false
        private set

    var bytesReadFromUsb: Long = 0
        private set
    var packetsReadFromUsb: Long = 0
        private set
    var bytesSentToCloud: Long = 0
        private set
    var bytesReceivedFromCloud: Long = 0
        private set
    var lastHeartbeatTime: Long = 0
        private set

    // Threads
    private var masterThread: Thread? = null
    private var usbReadThread: Thread? = null
    private var mavlinkStreamRequesterThread: Thread? = null
    private var localTcpServerThread: Thread? = null

    // Multiple listeners can receive FC bytes (Cloud relay + optional local Mission Planner)
    private val activeOutStreams = CopyOnWriteArrayList<OutputStream>()

    // Config cache
    private var droneId = "ajay@1"
    private var telemIp = "64.227.133.143"
    private var telemPort = 6666
    private var baudRate = 115200

    var onStatusUpdated: ((TelemetryStats) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null
    var onUsbConnectedAutoTrigger: (() -> Unit)? = null
    var onUsbDisconnectedAutoTrigger: (() -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                usbActionPermission -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                log("[USB] Permission granted for: ${it.deviceName}")
                                connectUsbSerial(it)
                            }
                        } else {
                            log("[USB] Permission denied for device: ${device?.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("[USB] Flight Controller cable attached")
                    scanAndConnectUsb()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("[USB] Flight Controller cable detached - stopping hardware link")
                    closeUsb()
                    onUsbDisconnectedAutoTrigger?.invoke()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(usbActionPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    private fun log(message: String) {
        Log.d(tag, message)
        onLogMessage?.invoke(message)
    }

    private fun updateState(state: BridgeState, description: String) {
        currentState = state
        currentStatusMessage = description
        log("[$state] $description")
        notifyStats()
    }

    private fun notifyStats() {
        val stats = TelemetryStats(
            state = currentState,
            stateDescription = currentStatusMessage,
            isUsbConnected = isUsbConnected,
            usbDeviceName = usbDeviceName,
            isCloudConnected = isCloudConnected,
            bytesReadFromUsb = bytesReadFromUsb,
            packetsReadFromUsb = packetsReadFromUsb,
            bytesSentToCloud = bytesSentToCloud,
            bytesReceivedFromCloud = bytesReceivedFromCloud,
            lastHeartbeatMs = lastHeartbeatTime,
            isRelayActive = isRelayActive
        )
        onStatusUpdated?.invoke(stats)
    }

    /**
     * Start the unified telemetry bridge.
     */
    fun startBridge(config: MergixConfig) {
        if (isRunning.get()) {
            log("[BRIDGE] Already running")
            return
        }

        droneId = config.droneId
        telemIp = config.telemIp
        telemPort = config.telemPort
        baudRate = config.baudRate

        isRunning.set(true)
        isRelayActive = false
        bytesSentToCloud = 0
        bytesReceivedFromCloud = 0

        updateState(BridgeState.SEARCHING_USB, "Scanning for Flight Controller (USB OTG)...")

        // 1. Scan and connect USB
        scanAndConnectUsb()

        // 2. Start Cloud Socket Relay Thread (Implements drone_app_bridge.cpp state machine)
        startCloudRelayMaster()

        // 3. Start Local Mission Planner TCP Server (Port 5760 for direct local Wi-Fi connection)
        startLocalTcpServer(5760)
    }

    fun stopBridge() {
        log("[BRIDGE] Stopping...")
        isRunning.set(false)
        isRelayActive = false
        isCloudConnected = false

        // Interrupt threads
        masterThread?.interrupt()
        usbReadThread?.interrupt()
        localTcpServerThread?.interrupt()

        masterThread = null
        usbReadThread = null
        localTcpServerThread = null

        activeOutStreams.clear()

        // Close sockets
        try { cloudSocket?.close() } catch (_: Exception) {}
        cloudSocket = null

        closeUsb()

        updateState(BridgeState.IDLE, "Bridge Stopped")
    }

    fun destroy() {
        stopBridge()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    // =========================================================================
    // USB Serial Hardware Layer
    // =========================================================================

    private var usbScannerThread: Thread? = null

    private fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable().apply {
            addProduct(0x26AC, 0x0011, CdcAcmSerialDriver::class.java) // 3DR Pixhawk
            addProduct(0x26AC, 0x0010, CdcAcmSerialDriver::class.java) // Pixhawk FMU
            addProduct(0x26AC, 0x0032, CdcAcmSerialDriver::class.java) // Pixhawk 4 / V5
            addProduct(0x2DAE, 0x1016, CdcAcmSerialDriver::class.java) // Hex Cube Orange+
            addProduct(0x2DAE, 0x1011, CdcAcmSerialDriver::class.java) // Hex Cube Black
            addProduct(0x2DAE, 0x1001, CdcAcmSerialDriver::class.java) // Hex Cube
            addProduct(0x0483, 0x5740, CdcAcmSerialDriver::class.java) // STM32 Virtual COM (Matek / Holybro / Pixhawk)
            addProduct(0x1204, 0x0001, CdcAcmSerialDriver::class.java) // Cypress / USB-UART
            addProduct(0x10C4, 0xEA60, com.hoho.android.usbserial.driver.Cp21xxSerialDriver::class.java) // CP2102
            addProduct(0x0403, 0x6001, com.hoho.android.usbserial.driver.FtdiSerialDriver::class.java)   // FTDI
            addProduct(0x1A86, 0x7523, com.hoho.android.usbserial.driver.Ch34xSerialDriver::class.java) // CH340
        }
        return UsbSerialProber(customTable)
    }

    fun startUsbAutoScanner() {
        if (usbScannerThread != null && usbScannerThread?.isAlive == true) return
        usbScannerThread = thread(start = true, isDaemon = true, name = "usb_scanner") {
            while (true) {
                try {
                    if (!isUsbConnected) {
                        scanAndConnectUsb()
                    }
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }
    }

    fun scanAndConnectUsb() {
        try {
            val rawDevices = usbManager.deviceList.values.toList()
            if (rawDevices.isEmpty()) {
                if (usbSerialPort != null) closeUsb()
                return
            }

            for (device in rawDevices) {
                if (!usbManager.hasPermission(device)) {
                    log("[USB] Found ${device.deviceName} (${device.productName ?: "FC"}). Requesting USB permission...")
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(usbActionPermission), flags)
                    usbManager.requestPermission(device, permissionIntent)
                    return
                }

                if (usbSerialPort == null) {
                    connectUsbSerial(device)
                    break
                }
            }
        } catch (e: Exception) {
            log("[USB] Scan error: ${e.localizedMessage}")
        }
    }

    fun connectUsbSerial(device: UsbDevice) {
        val customDrivers = getCustomProber().findAllDrivers(usbManager)
        val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val drivers = (customDrivers + defaultDrivers).filter { it.device.deviceId == device.deviceId }
        val driver = drivers.firstOrNull() ?: CdcAcmSerialDriver(device)

        try {
            val connection = usbManager.openDevice(driver.device) ?: run {
                log("[USB] Unable to open USB connection to ${driver.device.deviceName}")
                return
            }

            if (driver.ports.isEmpty()) {
                log("[USB] Driver found but no ports available")
                return
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                port.dtr = true
                port.rts = true
            } catch (_: Exception) {}

            usbSerialPort = port
            isUsbConnected = true
            usbDeviceName = device.productName ?: device.deviceName

            log("[USB] -? Connected to $usbDeviceName @ $baudRate baud")
            notifyStats()

            // Start MAVLink stream requester daemon
            startMavlinkStreamRequester(port)

            // Start reading from USB
            startUsbReadLoop(port)

            // Auto-trigger video and cloud telemetry bridge
            onUsbConnectedAutoTrigger?.invoke()

        } catch (e: Exception) {
            log("[USB] Connection error: ${e.localizedMessage}")
            closeUsb()
        }
    }

    private fun startMavlinkStreamRequester(port: UsbSerialPort) {
        mavlinkStreamRequesterThread?.interrupt()
        mavlinkStreamRequesterThread = thread(start = true, isDaemon = true, name = "mavlink_stream_requester") {
            var seq = 0
            while (isUsbConnected) {
                try {
                    val req = generateRequestDataStreamPacket(seq)
                    val hb = generateHeartbeat(seq)
                    port.write(req, 100)
                    port.write(hb, 100)
                    seq = (seq + 1) % 256
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }
    }

    private fun startUsbReadLoop(port: UsbSerialPort) {
        usbReadThread?.interrupt()
        usbReadThread = thread(start = true, name = "usb_read_thread") {
            val buffer = ByteArray(4096)
            var lastUiUpdate = 0L

            while (isUsbConnected) {
                try {
                    val len = port.read(buffer, 100)
                    if (len > 0) {
                        bytesReadFromUsb += len
                        packetsReadFromUsb++

                        // Forward directly to Cloud TCP output stream and any local GCS clients
                        for (out in activeOutStreams) {
                            try {
                                out.write(buffer, 0, len)
                                out.flush()
                            } catch (_: Exception) {}
                        }
                        if (activeOutStreams.isNotEmpty()) {
                            bytesSentToCloud += len
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate > 1000) {
                            lastUiUpdate = now
                            notifyStats()
                        }
                    } else {
                        // CRITICAL: When no serial bytes are ready, sleep 10ms to yield CPU.
                        // This prevents 100% CPU core spinning, drastically cools the phone, and saves battery.
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isUsbConnected) {
                        log("[USB] Read loop ended: ${e.localizedMessage}")
                    }
                    break
                }
            }
        }
    }

    private fun writeToUsb(data: ByteArray, length: Int) {
        usbSerialPort?.let { port ->
            if (isUsbConnected) {
                try {
                    port.write(data.copyOf(length), 100)
                } catch (e: Exception) {
                    log("[USB] Write error: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun closeUsb() {
        mavlinkStreamRequesterThread?.interrupt()
        mavlinkStreamRequesterThread = null
        usbReadThread?.interrupt()
        usbReadThread = null
        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null
        isUsbConnected = false
        usbDeviceName = "Disconnected"
        notifyStats()
    }

    private fun generateRequestDataStreamPacket(seq: Int): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(10.toShort()) // 10 Hz rate
            put(1.toByte())        // stream_id = MAV_DATA_STREAM_ALL
            put(1.toByte())        // start_stop = 1 (start)
            put(0.toByte())
            put(1.toByte())
        }.array()
        return buildMavlinkV2Packet(66, seq, payload, 148)
    }

    private fun generateHeartbeat(seq: Int): ByteArray {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0)          // custom_mode
            put(2.toByte())    // type = MAV_TYPE_QUADROTOR
            put(3.toByte())    // autopilot = MAV_AUTOPILOT_ARDUPILOTMEGA
            put(0x81.toByte()) // base_mode
            put(4.toByte())    // system_status = MAV_STATE_ACTIVE
            put(3.toByte())    // mavlink_version = 3
        }.array()
        return buildMavlinkV2Packet(0, seq, payload, 50)
    }

    private fun buildMavlinkV2Packet(msgId: Int, seq: Int, payload: ByteArray, crcExtra: Int): ByteArray {
        val headerLength = 10
        val packetLength = headerLength + payload.size + 2
        val buffer = ByteArray(packetLength)

        buffer[0] = 0xFD.toByte()
        buffer[1] = payload.size.toByte()
        buffer[2] = 0x00.toByte()
        buffer[3] = 0x00.toByte()
        buffer[4] = (seq and 0xFF).toByte()
        buffer[5] = 0x01.toByte()
        buffer[6] = 0x01.toByte()
        buffer[7] = (msgId and 0xFF).toByte()
        buffer[8] = ((msgId shr 8) and 0xFF).toByte()
        buffer[9] = ((msgId shr 16) and 0xFF).toByte()

        System.arraycopy(payload, 0, buffer, headerLength, payload.size)

        val crc = calculateMavlinkCrc(buffer, 1, headerLength - 1 + payload.size, crcExtra)
        buffer[packetLength - 2] = (crc and 0xFF).toByte()
        buffer[packetLength - 1] = ((crc shr 8) and 0xFF).toByte()

        return buffer
    }

    private fun calculateMavlinkCrc(buffer: ByteArray, start: Int, length: Int, crcExtra: Int): Int {
        var crc = 0xFFFF
        for (i in start until (start + length)) {
            var tmp = (buffer[i].toInt() and 0xFF) xor (crc and 0xFF)
            tmp = (tmp xor (tmp shl 4)) and 0xFF
            crc = ((crc shr 8) and 0xFF) xor (tmp shl 8) xor (tmp shl 3) xor ((tmp shr 4) and 0xFF)
        }
        var tmp = (crcExtra and 0xFF) xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        crc = ((crc shr 8) and 0xFF) xor (tmp shl 8) xor (tmp shl 3) xor ((tmp shr 4) and 0xFF)
        return crc
    }

    // =========================================================================
    // Cloud Socket Relay Layer (Exact Match to drone_app_bridge.cpp & mergix_drone.py)
    // =========================================================================

    private fun startCloudRelayMaster() {
        masterThread?.interrupt()
        masterThread = thread(start = true, name = "cloud_relay_master") {
            while (isRunning.get()) {
                try {
                    updateState(BridgeState.CONNECTING_CLOUD, "Connecting to $telemIp:$telemPort ...")

                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(telemIp, telemPort), 5000)
                    cloudSocket = socket
                    isCloudConnected = true

                    val outStream = socket.getOutputStream()
                    val inStream = socket.getInputStream()

                    // --- PHASE 1: HEARTBEAT & HANDSHAKE ---
                    val handshakeOk = executeHeartbeatHandshake(socket, outStream, inStream)

                    if (handshakeOk && isRunning.get()) {
                        // --- PHASE 2: BIDIRECTIONAL RELAY ---
                        executeBridgeRelay(socket, outStream, inStream)
                    }

                } catch (e: Exception) {
                    if (isRunning.get()) {
                        log("[TCP] Connection error: ${e.localizedMessage}. Retrying in 3s...")
                        updateState(BridgeState.ERROR, "Reconnecting: ${e.localizedMessage}")
                    }
                } finally {
                    isCloudConnected = false
                    isRelayActive = false
                    try { cloudSocket?.close() } catch (_: Exception) {}
                    cloudSocket = null
                    notifyStats()
                }

                if (isRunning.get()) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /**
     * Executes the exact heartbeat logic from mergix_drone.py and drone_app_bridge.cpp:
     * 1. Send DRONE_ID
     * 2. Send 0xFD keepalive ping byte every 1 sec
     * 3. Await echo and wait for "START RELAY"
     */
    private fun executeHeartbeatHandshake(socket: Socket, outStream: OutputStream, inStream: InputStream): Boolean {
        updateState(BridgeState.AUTHENTICATING, "Sending DRONE_ID '$droneId' to server...")

        // Send Drone ID
        outStream.write(droneId.toByteArray(Charsets.UTF_8))
        outStream.flush()
        log("[HB] DRONE_ID sent: $droneId")

        // 2 second pause matching mergix_drone.py
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return false }

        socket.soTimeout = 5000 // HEARTBEAT_TIMEOUT = 5s
        val rxBuf = ByteArray(1024)

        while (isRunning.get() && socket.isConnected && !socket.isClosed) {
            // Send keepalive ping byte 0xFD
            outStream.write(byteArrayOf(0xFD.toByte()))
            outStream.flush()

            try {
                val bytesRead = inStream.read(rxBuf)
                if (bytesRead <= 0) {
                    log("[HB] Server closed connection during handshake")
                    return false
                }

                val receivedText = String(rxBuf, 0, bytesRead, Charsets.ISO_8859_1)

                // Check for echo byte
                var hasEcho = false
                for (i in 0 until bytesRead) {
                    if (rxBuf[i] == 0xFD.toByte()) {
                        hasEcho = true
                        lastHeartbeatTime = System.currentTimeMillis()
                        break
                    }
                }
                if (hasEcho) {
                    updateState(BridgeState.HEARTBEAT_PULSE, "Server Echo Received (Heartbeat Active)")
                }

                // Check for "START RELAY" from server
                if (receivedText.contains("START RELAY")) {
                    log("[HB] 'START RELAY' received! Entering active bridge mode.")
                    return true
                }

            } catch (e: SocketTimeoutException) {
                log("[HB] No echo in 5s — link dead, reconnecting")
                return false
            }

            try { Thread.sleep(1000) } catch (_: InterruptedException) { return false }
        }

        return false
    }

    /**
     * Executes bidirectional relay between USB and Cloud Socket with watchdog monitoring.
     */
    private fun executeBridgeRelay(socket: Socket, outStream: OutputStream, inStream: InputStream) {
        updateState(BridgeState.RELAY_STREAMING, "5G Cloud Telemetry Relay ACTIVE")
        isRelayActive = true
        activeOutStreams.add(outStream)

        // Timestamp of last seen MAVLink keepalive byte
        var lastAlive = System.currentTimeMillis()
        val watchdogTimeoutMs = 5000L // BRIDGE_WATCHDOG = 5s
        socket.soTimeout = 2000 // Check watchdog every 2s

        val buffer = ByteArray(4096)

        try {
            while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                val bytesRead: Int
                try {
                    bytesRead = inStream.read(buffer)
                } catch (e: SocketTimeoutException) {
                    // Timeout check - normal when link is idle, verify watchdog
                    if (System.currentTimeMillis() - lastAlive > watchdogTimeoutMs) {
                        log("[Bridge] Watchdog timeout (${watchdogTimeoutMs / 1000}s without 0xFD/0xFE) — reconnecting")
                        break
                    }
                    continue
                }

                if (bytesRead <= 0) {
                    log("[Bridge] Server closed connection")
                    break
                }

                bytesReceivedFromCloud += bytesRead

                // Check for MAVLink v2 (0xFD) or v1 (0xFE)
                for (i in 0 until bytesRead) {
                    if (buffer[i] == 0xFD.toByte() || buffer[i] == 0xFE.toByte()) {
                        lastAlive = System.currentTimeMillis()
                        break
                    }
                }

                // Forward cloud commands to Pixhawk USB
                writeToUsb(buffer, bytesRead)
            }
        } finally {
            activeOutStreams.remove(outStream)
            isRelayActive = false
        }
    }

    // =========================================================================
    // Local TCP Server (Port 5760 for direct local Mission Planner on Wi-Fi)
    // =========================================================================

    private fun startLocalTcpServer(port: Int) {
        localTcpServerThread?.interrupt()
        localTcpServerThread = thread(start = true, name = "local_tcp_server") {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(port)
                log("[Local TCP] Mission Planner server listening on port $port")

                while (isRunning.get()) {
                    val client = serverSocket.accept()
                    client.tcpNoDelay = true
                    log("[Local TCP] Local GCS (Mission Planner/QGC) connected: ${client.remoteSocketAddress}")

                    val clientOut = client.getOutputStream()
                    activeOutStreams.add(clientOut)

                    thread(start = true, isDaemon = true) {
                        val inStream = client.getInputStream()
                        val buf = ByteArray(2048)
                        try {
                            while (isRunning.get() && client.isConnected && !client.isClosed) {
                                val read = inStream.read(buf)
                                if (read > 0) {
                                    writeToUsb(buf, read)
                                } else break
                            }
                        } catch (_: Exception) {} finally {
                            activeOutStreams.remove(clientOut)
                            try { client.close() } catch (_: Exception) {}
                            log("[Local TCP] Local GCS disconnected")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) log("[Local TCP] Server error: ${e.localizedMessage}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }
}
