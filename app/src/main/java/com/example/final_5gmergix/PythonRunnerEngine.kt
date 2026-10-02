package com.example.final_5gmergix

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.system.measureTimeMillis

data class PythonExecutionResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val executionTimeMs: Long,
    val scriptName: String
)

class PythonRunnerEngine(private val context: Context) {

    init {
        copyAssetScriptsToStorage()
    }

    fun copyAssetScriptsToStorage() {
        try {
            val scriptsDir = File(context.filesDir, "python_scripts")
            if (!scriptsDir.exists()) scriptsDir.mkdirs()
            val assetManager = context.assets

            val files = assetManager.list("python") ?: emptyArray()
            for (filename in files) {
                val outFile = File(scriptsDir, filename)
                assetManager.open("python/$filename").use { inputStream ->
                    outFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d("PythonRunnerEngine", "Copied asset script: $filename to ${outFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("PythonRunnerEngine", "Error copying asset files: ${e.localizedMessage}")
        }
    }

    fun getAvailableScripts(): List<File> {
        val scriptsDir = File(context.filesDir, "python_scripts")
        if (!scriptsDir.exists()) return emptyList()
        return scriptsDir.listFiles { _, name -> name.endsWith(".py") || name.endsWith(".sh") }?.toList() ?: emptyList()
    }

    fun getScriptContent(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            "Error reading file: ${e.localizedMessage}"
        }
    }

    fun saveScript(name: String, content: String): File {
        val scriptsDir = File(context.filesDir, "python_scripts")
        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        val cleanName = if (name.endsWith(".py")) name else "$name.py"
        val file = File(scriptsDir, cleanName)
        file.writeText(content)
        return file
    }
}
