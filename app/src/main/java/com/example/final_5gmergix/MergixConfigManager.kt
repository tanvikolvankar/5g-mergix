package com.example.final_5gmergix

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

data class MergixConfig(
    val droneId: String,
    val telemIp: String,
    val telemPort: Int,
    val droneConnectionType: String,
    val dronePort: String,
    val baudRate: Int,
    val videoInputLink: String,
    val videoOutputLink: String,
    val videoRes: String
)

class MergixConfigManager(private val context: Context) {

    private val configFileName = "static_mergix_data.json"
    private val configFile: File
        get() = File(context.filesDir, configFileName)

    init {
        ensureConfigFileExists()
    }

    private fun ensureConfigFileExists() {
        try {
            if (!configFile.exists()) {
                val assetManager = context.assets
                assetManager.open("config/$configFileName").use { inputStream ->
                    configFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d("MergixConfigManager", "Copied default static_mergix_data.json to ${configFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("MergixConfigManager", "Error ensuring config file: ${e.localizedMessage}")
        }
    }

    fun loadConfig(): MergixConfig {
        return try {
            if (!configFile.exists()) ensureConfigFileExists()
            val json = JSONObject(configFile.readText())
            MergixConfig(
                droneId = json.optString("drone_id", "ajay@1"),
                telemIp = json.optString("telem_ip", "64.227.133.143"),
                telemPort = json.optInt("telem_port", 6666),
                droneConnectionType = json.optString("drone_connection_type", "serial"),
                dronePort = json.optString("drone_port", "/dev/ttyAMA0"),
                baudRate = json.optInt("baud_rate", 115200),
                videoInputLink = json.optString("video_input_link", "rtsp://192.168.144.25:8554/main.264"),
                videoOutputLink = json.optString("video_output_link", "rtsp://64.227.133.143:8554/mystream1"),
                videoRes = json.optString("video_res", "1280:720")
            )
        } catch (e: Exception) {
            Log.e("MergixConfigManager", "Error loading config: ${e.localizedMessage}")
            MergixConfig(
                droneId = "ajay@1",
                telemIp = "64.227.133.143",
                telemPort = 6666,
                droneConnectionType = "serial",
                dronePort = "/dev/ttyAMA0",
                baudRate = 115200,
                videoInputLink = "rtsp://192.168.144.25:8554/main.264",
                videoOutputLink = "rtsp://64.227.133.143:8554/mystream1",
                videoRes = "1280:720"
            )
        }
    }

    fun saveConfig(config: MergixConfig): Boolean {
        return try {
            val json = JSONObject().apply {
                put("drone_id", config.droneId)
                put("telem_ip", config.telemIp)
                put("telem_port", config.telemPort)
                put("drone_connection_type", config.droneConnectionType)
                put("drone_port", config.dronePort)
                put("baud_rate", config.baudRate)
                put("video_input_link", config.videoInputLink)
                put("video_output_link", config.videoOutputLink)
                put("video_res", config.videoRes)
            }
            configFile.writeText(json.toString(2))
            Log.d("MergixConfigManager", "Saved updated config to ${configFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("MergixConfigManager", "Error saving config: ${e.localizedMessage}")
            false
        }
    }
}
