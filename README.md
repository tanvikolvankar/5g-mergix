# 5G MERGIX 🛸

**High-Performance 5G Drone Companion Computer & Telemetry/Video Gateway**

5G MERGIX transforms an Android device or Qualcomm smart module into an enterprise-grade drone companion computer. It provides real-time MAVLink telemetry relay and ultra-low-latency HD video transmission over 5G/4G cellular networks directly to your cloud relay and Ground Control Station (Mission Planner / QGroundControl).

---

## 🚀 Key Features

* **Zero-CPU RTSP Video Relay (< 150 ms Latency)**
  * Direct bitstream packet passthrough for external Ethernet IP cameras (SIYI, Skydroid, RunCam, Sony).
  * No software re-encoding or generation loss — pure 1:1 sensor quality at full 1080p/720p HD.
* **Dual-Camera Auto-Switching & Redundancy**
  * Automatic detection of USB/Ethernet IP cameras.
  * Instant, seamless failover to the phone's internal camera if the external gimbal camera is disconnected, and auto-recovery when reconnected.
* **Resilient MAVLink Telemetry Bridge**
  * Direct USB Host serial connection to Pixhawk, Cube, ArduPilot, and PX4 flight controllers.
  * Native USB permissions without requiring root or terminal setup.
* **Infinite Auto-Healing & Reconnect Engine**
  * Flight-ready resilience: Telemetry and video streaming never terminate on connection breaks, cable vibrations, or cell tower handoffs.
  * Continuous background re-arming and auto-reconnection.
* **Mission Control HUD Dashboard**
  * Modern Dark-Mode Jetpack Compose UI with real-time flight stats, MAVLink packet counters, FPS/resolution overlays, and live terminal logs.
  * On-device interactive settings to configure Drone ID, cloud endpoints, and ports.
* **Persistent GCS Client (`gcs_handshake_client.py`)**
  * Connects to the cloud server, authenticates, triggers drone telemetry, and exposes a persistent local TCP server on port `5760` for Mission Planner and QGroundControl.

---

## 📐 Architecture Overview

```
 ┌──────────────────────────────────────────────────────────────┐
 │                      DRONE PLATFORM                          │
 │                                                              │
 │   ┌──────────────────────┐        ┌──────────────────────┐   │
 │   │ Pixhawk / Flight Ctl │        │ Ethernet IP Camera   │   │
 │   │  (ArduPilot / PX4)   │        │   (SIYI / Skydroid)  │   │
 │   └──────────┬───────────┘        └──────────┬───────────┘   │
 │              │ USB / TELEM                   │ RTSP Stream   │
 │              ▼                               ▼               │
 │   ┌──────────────────────────────────────────────────────┐   │
 │   │                   5G MERGIX ENGINE                   │   │
 │   │    • TelemetryBridgeEngine   • EthernetRtspRelay     │   │
 │   │    • CameraStreamManager     • MergixDashboard       │   │
 │   └──────────────────────────┬───────────────────────────┘   │
 └──────────────────────────────┼───────────────────────────────┘
                                │ 5G / 4G Cellular Link
                                ▼
 ┌──────────────────────────────────────────────────────────────┐
 │                     CLOUD RELAY SERVER                       │
 │  • Port 7777 : GCS Authentication & Port Allocation          │
 │  • Port 6666 : Telemetry Relay Server                        │
 │  • Port 8554 : MediaMTX / RTSP Video Broadcast Server        │
 └──────────────────────────────┬───────────────────────────────┘
                                │ Internet
                                ▼
 ┌──────────────────────────────────────────────────────────────┐
 │                 GROUND CONTROL STATION (PC)                  │
 │                                                              │
 │   ┌──────────────────────────┴───────────────────────────┐   │
 │   │              gcs_handshake_client.py                 │   │
 │   │       Exposes local TCP server on port 5760          │   │
 │   └──────────────────────────┬───────────────────────────┘   │
 │                              │ Local Bridge (tcp:127.0.0.1:5760)
 │                              ▼
 │   ┌──────────────────────────────────────────────────────┐   │
 │   │          Mission Planner / QGroundControl            │   │
 │   │              + VLC / RTSP Video Player               │   │
 │   └──────────────────────────────────────────────────────┘   │
 └──────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Quick Start Guide

### 1. Build and Install the Android App
Open the project in **Android Studio** and run it directly on your Android phone or companion board, or build the APK via Gradle:
```bash
./gradlew assembleDebug
```
Install the generated APK onto your device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Device Configuration
Configure the connection parameters directly in the app's **Settings Tab** on your device, or via `static_mergix_data.json`:

```json
{
  "drone_id": "DRONE_01",
  "telem_ip": "<YOUR_CLOUD_SERVER_IP>",
  "telem_port": 6666,
  "drone_connection_type": "serial",
  "drone_port": "/dev/ttyAMA0",
  "baud_rate": 115200,
  "video_input_link": "rtsp://192.168.144.108:554/stream=0",
  "video_output_link": "rtsp://<YOUR_CLOUD_SERVER_IP>:8554/mystream1",
  "video_res": "1280:720"
}
```

### 3. Flight Connection
1. Connect the flight controller (Pixhawk) to your phone via USB-OTG.
2. If using an external Ethernet IP camera, connect it via a USB-to-Ethernet adapter.
3. Open **5G MERGIX** on the device.
4. Tap **START TELEM** (bridges MAVLink over 5G).
5. Tap **START VIDEO** (initiates zero-CPU RTSP relay).

---

## 💻 Ground Control Station (GCS) Setup

To connect Mission Planner or QGroundControl on your ground station laptop:

1. Run the Python handshake client:
   ```bash
   python gcs_handshake_client.py --server <YOUR_CLOUD_SERVER_IP> --username <YOUR_USERNAME> --drone_id DRONE_01
   ```
2. Open **Mission Planner** or **QGroundControl**:
   * Connection Type: **TCP**
   * Host: `127.0.0.1`
   * Port: `5760`
   * Click **Connect**.
3. To view the live video stream, open VLC or your GCS video widget:
   * URL: `rtsp://<YOUR_CLOUD_SERVER_IP>:8554/mystream1`

---

## 📂 Project Structure

```
Final_5GMERGIX/
├── app/src/main/
│   ├── java/com/example/final_5gmergix/
│   │   ├── MainActivity.kt               # App entrypoint and lifecycle
│   │   ├── TelemetryBridgeEngine.kt      # Native USB-Serial MAVLink 5G bridge
│   │   ├── CameraStreamManager.kt        # Internal camera capture & failover
│   │   ├── EthernetRtspRelayEngine.kt    # Zero-CPU raw H.264 socket relay
│   │   ├── MergixConfigManager.kt        # Persistent configuration manager
│   │   ├── PythonRunnerEngine.kt         # Embedded Python execution environment
│   │   ├── ui/
│   │   │   └── MergixDashboard.kt        # Jetpack Compose Mission Control UI
│   │   └── service/
│   │       └── MergixForegroundService.kt# High-priority lifeline background service
│   ├── assets/config/
│   │   └── static_mergix_data.json       # Default configuration schema
│   └── res/xml/
│       └── device_filter.xml             # Direct USB auto-bind intent filters
├── gcs_handshake_client.py               # Ground Control Station Python bridge
└── README.md                             # Documentation
```

---

## 📜 License
Proprietary & Confidential - 5G MERGIX Avionics Team.
