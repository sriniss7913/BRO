package com.example.bro.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * On-Device LLM & Pointer Network Manager.
 *
 * Manages on-device inference using MediaPipe tasks-genai or ONNX models.
 */
object OnDeviceLlmManager {
    private const val TAG = "BRO_ON_DEVICE"
    private const val DEFAULT_MODELS_DIR = "models"
    private const val PREFS_NAME = "bro_prefs"
    private const val KEY_MODEL_DIR = "on_device_model_dir"
    private const val KEY_SELECTED_MODEL = "on_device_selected_model"

    var customModelDirPath: String = "/sdcard/BRO/"

    private var llmInference: LlmInference? = null
    private var currentModelName: String = ""

    val isModelLoaded: Boolean get() = llmInference != null || OnnxNavPredictor.isModelLoaded || currentModelName.isNotBlank()
    val loadedModelName: String get() = currentModelName

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        customModelDirPath = prefs.getString(KEY_MODEL_DIR, "/sdcard/BRO/") ?: "/sdcard/BRO/"
        val savedModel = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""
        if (savedModel.isNotBlank()) {
            val file = findModelFile(context, savedModel)
            if (file != null && file.exists()) {
                currentModelName = savedModel
                if (savedModel.lowercase().endsWith(".onnx")) {
                    OnnxNavPredictor.loadModel(context, file)
                }
                Log.d(TAG, "Restored selected model on startup: $savedModel at ${file.absolutePath}")
            }
        }
    }

    private fun getPossibleDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()

        var rawPath = customModelDirPath.trim()
        if (rawPath.startsWith("/storage/emulated/0/")) {
            rawPath = rawPath.replace("/storage/emulated/0/", "/sdcard/")
        }

        if (rawPath.isNotBlank()) {
            val customDir = File(rawPath)
            if (customDir.exists() && customDir.isDirectory) {
                dirs.add(customDir)
            }
        }

        val broDir = File("/sdcard/BRO")
        if (broDir.exists() && broDir.isDirectory) dirs.add(broDir)

        val downloadDir = File("/sdcard/Download")
        if (downloadDir.exists() && downloadDir.isDirectory) dirs.add(downloadDir)

        val appDir = File(context.getExternalFilesDir(null), DEFAULT_MODELS_DIR)
        if (!appDir.exists()) appDir.mkdirs()
        dirs.add(appDir)

        return dirs.distinctBy { it.absolutePath }
    }

    fun findModelFile(context: Context, modelFileName: String): File? {
        val dirs = getPossibleDirs(context)
        for (dir in dirs) {
            val file = File(dir, modelFileName)
            if (file.exists() && file.isFile) {
                return file
            }
        }
        return null
    }

    fun listAvailableModels(context: Context): List<String> {
        val dirs = getPossibleDirs(context)
        val result = mutableSetOf<String>()
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile) {
                    val nameLower = f.name.lowercase()
                    if (nameLower.endsWith(".onnx") || nameLower.endsWith(".task") || nameLower.endsWith(".bin") || nameLower.endsWith(".gguf")) {
                        result.add(f.name)
                    }
                }
            }
        }
        return result.toList()
    }

    fun loadModel(context: Context, modelFileName: String): Result<String> {
        return try {
            close()

            val modelFile = findModelFile(context, modelFileName)
                ?: return Result.failure(Exception("Model file '$modelFileName' not found in any directory"))

            Log.d(TAG, "Loading on-device model: ${modelFile.absolutePath} (${modelFile.length() / 1024} KB)")

            val lowerName = modelFileName.lowercase()
            if (lowerName.endsWith(".task")) {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                currentModelName = modelFileName
                saveSelectedModel(context, modelFileName)
                Log.d(TAG, "MediaPipe Task Model loaded successfully: $modelFileName")
                Result.success("Loaded MediaPipe Model '$modelFileName' (${modelFile.length() / 1024 / 1024}MB)")
            } else if (lowerName.endsWith(".onnx")) {
                val success = OnnxNavPredictor.loadModel(context, modelFile)
                if (success) {
                    currentModelName = modelFileName
                    saveSelectedModel(context, modelFileName)
                    Log.d(TAG, "ONNX Navigation Model loaded into OrtSession: $modelFileName")
                    Result.success("Loaded ONNX Neural Network '$modelFileName' (${modelFile.length() / 1024} KB)")
                } else {
                    Result.failure(Exception("Failed to initialize ONNX session for $modelFileName"))
                }
            } else {
                currentModelName = modelFileName
                saveSelectedModel(context, modelFileName)
                Result.success("Loaded Model '$modelFileName'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            llmInference = null
            currentModelName = ""
            Result.failure(Exception("Failed to load model '$modelFileName': ${e.message}"))
        }
    }

    private fun saveSelectedModel(context: Context, modelFileName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_MODEL, modelFileName).apply()
    }

    fun generateResponse(systemPrompt: String, userPrompt: String): String {
        // 1. If ONNX model is loaded, run pure ONNX Runtime neural network forward pass!
        if (currentModelName.lowercase().endsWith(".onnx") && OnnxNavPredictor.isModelLoaded) {
            Log.d(TAG, "Running pure ONNX Neural Network Forward Pass for $currentModelName")
            return OnnxNavPredictor.predict(userPrompt)
        }

        // 2. Fallback to MediaPipe LlmInference
        val inference = llmInference
            ?: throw Exception("No on-device MediaPipe or ONNX model loaded. Load a model in Settings → 📱 On-Device")

        val combinedPrompt = buildString {
            append("<start_of_turn>user\n")
            append("SYSTEM INSTRUCTIONS:\n")
            append(systemPrompt)
            append("\n\nUSER REQUEST:\n")
            append(userPrompt)
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }

        val startTime = System.currentTimeMillis()
        val result = inference.generateResponse(combinedPrompt)
        val durationMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "MediaPipe inference complete in ${durationMs}ms (${result.length} chars output)")

        return result.trim()
    }

    fun close() {
        try {
            llmInference?.close()
            OnnxNavPredictor.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model: ${e.message}")
        }
        llmInference = null
        currentModelName = ""
    }
}
