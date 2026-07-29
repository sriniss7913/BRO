package com.example.bro.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
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
    private const val KEY_SELECTED_MODEL_PATH = "on_device_selected_model_path"

    var customModelDirPath: String = "/sdcard/BRO/"

    private var llmInference: LlmInference? = null
    private var currentModelName: String = ""
    private var currentModelPath: String = ""

    val isModelLoaded: Boolean get() = llmInference != null || OnnxNavPredictor.isModelLoaded || currentModelName.isNotBlank()
    val loadedModelName: String get() = currentModelName
    val loadedModelPath: String get() = currentModelPath

    data class ModelFileInfo(
        val fileName: String,
        val absolutePath: String,
        val sizeBytes: Long
    )

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        customModelDirPath = prefs.getString(KEY_MODEL_DIR, "/sdcard/BRO/") ?: "/sdcard/BRO/"
        val savedModelPath = prefs.getString(KEY_SELECTED_MODEL_PATH, "") ?: ""
        val savedModelName = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""

        if (savedModelPath.isNotBlank()) {
            val file = File(savedModelPath)
            if (file.exists() && file.isFile) {
                loadModelByFile(context, file)
                Log.d(TAG, "Restored selected model from exact path on startup: ${file.absolutePath}")
                return
            }
        }

        if (savedModelName.isNotBlank()) {
            val file = findModelFile(context, savedModelName)
            if (file != null && file.exists()) {
                loadModelByFile(context, file)
                Log.d(TAG, "Restored selected model on startup: $savedModelName at ${file.absolutePath}")
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
            if (customDir.exists()) {
                if (customDir.isDirectory) {
                    dirs.add(customDir)
                    customDir.listFiles()?.filter { it.isDirectory }?.let { dirs.addAll(it) }
                } else if (customDir.isFile) {
                    customDir.parentFile?.let { parent ->
                        dirs.add(parent)
                        parent.listFiles()?.filter { it.isDirectory }?.let { dirs.addAll(it) }
                    }
                }
            }
        }

        val downloadDir = File("/sdcard/Download")
        if (downloadDir.exists() && downloadDir.isDirectory) {
            dirs.add(downloadDir)
            downloadDir.listFiles()?.filter { it.isDirectory }?.let { dirs.addAll(it) }
        }

        val broDir = File("/sdcard/BRO")
        if (broDir.exists() && broDir.isDirectory) {
            dirs.add(broDir)
            broDir.listFiles()?.filter { it.isDirectory }?.let { dirs.addAll(it) }
        }

        val appDir = File(context.getExternalFilesDir(null), DEFAULT_MODELS_DIR)
        if (!appDir.exists()) appDir.mkdirs()
        dirs.add(appDir)

        return dirs.distinctBy { it.absolutePath }
    }

    fun findModelFile(context: Context, modelPathOrName: String): File? {
        val trimmed = modelPathOrName.trim()
        if (trimmed.isBlank()) return null

        val directFile = File(trimmed)
        if (directFile.exists() && directFile.isFile) {
            return directFile
        }

        val convertedPath = if (trimmed.startsWith("/sdcard/")) {
            trimmed.replace("/sdcard/", "/storage/emulated/0/")
        } else if (trimmed.startsWith("/storage/emulated/0/")) {
            trimmed.replace("/storage/emulated/0/", "/sdcard/")
        } else trimmed

        val convertedFile = File(convertedPath)
        if (convertedFile.exists() && convertedFile.isFile) {
            return convertedFile
        }

        val fileNameOnly = File(trimmed).name
        val dirs = getPossibleDirs(context)
        for (dir in dirs) {
            val file = File(dir, fileNameOnly)
            if (file.exists() && file.isFile) {
                return file
            }
        }
        return null
    }

    fun listAvailableModels(context: Context): List<ModelFileInfo> {
        val dirs = getPossibleDirs(context)
        val result = mutableMapOf<String, ModelFileInfo>()
        val supportedExts = listOf(".onnx", ".task", ".bin", ".gguf", ".tflite", ".pte", ".model", ".litertlm")

        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile) {
                    val nameLower = f.name.lowercase()
                    if (supportedExts.any { nameLower.endsWith(it) }) {
                        if (!result.containsKey(f.absolutePath)) {
                            result[f.absolutePath] = ModelFileInfo(
                                fileName = f.name,
                                absolutePath = f.absolutePath,
                                sizeBytes = f.length()
                            )
                        }
                    }
                }
            }
        }
        return result.values.toList()
    }

    fun loadModel(context: Context, modelPathOrName: String): Result<String> {
        return try {
            close()

            val modelFile = findModelFile(context, modelPathOrName)
                ?: return Result.failure(Exception("Model file '$modelPathOrName' not found"))

            loadModelByFile(context, modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            close()
            Result.failure(Exception("Failed to load model '$modelPathOrName': ${e.message}"))
        }
    }

    private fun prepareFileForNativeAccess(context: Context, modelFile: File): File {
        val appDir = context.getExternalFilesDir(null)
        if (appDir != null && modelFile.absolutePath.startsWith(appDir.absolutePath)) {
            return modelFile
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return modelFile
        }

        return try {
            val modelsDir = File(context.getExternalFilesDir(null), DEFAULT_MODELS_DIR)
            if (!modelsDir.exists()) modelsDir.mkdirs()

            val stagedFile = File(modelsDir, modelFile.name)
            if (!stagedFile.exists() || stagedFile.length() != modelFile.length()) {
                Log.d(TAG, "Staging model file to app directory for POSIX native access: ${modelFile.name} -> ${stagedFile.absolutePath}")
                modelFile.copyTo(stagedFile, overwrite = true)
            }
            stagedFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not stage model file to app directory: ${e.message}")
            modelFile
        }
    }

    fun loadModelByFile(context: Context, modelFile: File): Result<String> {
        return try {
            close()
            Log.d(TAG, "Loading on-device model: ${modelFile.absolutePath} (${modelFile.length() / 1024} KB)")

            val lowerName = modelFile.name.lowercase()
            val fileName = modelFile.name
            var targetFile = modelFile

            if (lowerName.endsWith(".onnx")) {
                val success = OnnxNavPredictor.loadModel(context, targetFile)
                if (success) {
                    currentModelName = fileName
                    currentModelPath = targetFile.absolutePath
                    saveSelectedModel(context, fileName, targetFile.absolutePath)
                    Log.d(TAG, "ONNX Navigation Model loaded into OrtSession: $fileName")
                    return Result.success("Loaded ONNX Neural Network '$fileName' (${targetFile.length() / 1024} KB)")
                } else {
                    return Result.failure(Exception("Failed to initialize ONNX session for '$fileName'"))
                }
            }

            // Attempt MediaPipe GenAI LlmInference
            var lastMediaPipeErr: String? = null
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(targetFile.absolutePath)
                    .setMaxTokens(512)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                currentModelName = fileName
                currentModelPath = targetFile.absolutePath
                saveSelectedModel(context, fileName, targetFile.absolutePath)
                Log.d(TAG, "MediaPipe LLM Model loaded successfully: $fileName")
                return Result.success("Loaded MediaPipe LLM '$fileName' (${targetFile.length() / 1024 / 1024} MB)")
            } catch (e: Exception) {
                lastMediaPipeErr = e.message
                Log.w(TAG, "MediaPipe direct open failed for ${targetFile.absolutePath}: ${e.message}. Retrying with staged copy...")
            }

            // Staging fallback for POSIX file descriptor open errors on Android 11+
            targetFile = prepareFileForNativeAccess(context, modelFile)
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(targetFile.absolutePath)
                    .setMaxTokens(512)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                currentModelName = fileName
                currentModelPath = targetFile.absolutePath
                saveSelectedModel(context, fileName, targetFile.absolutePath)
                Log.d(TAG, "MediaPipe LLM Model loaded successfully from staged file: $fileName")
                return Result.success("Loaded MediaPipe LLM '$fileName' (${targetFile.length() / 1024 / 1024} MB)")
            } catch (e: Exception) {
                Log.w(TAG, "MediaPipe staged LlmInference also failed: ${e.message}")
                // Attempt ONNX fallback
                val onnxSuccess = OnnxNavPredictor.loadModel(context, targetFile)
                if (onnxSuccess) {
                    currentModelName = fileName
                    currentModelPath = targetFile.absolutePath
                    saveSelectedModel(context, fileName, targetFile.absolutePath)
                    return Result.success("Loaded ONNX Model '$fileName' (${targetFile.length() / 1024} KB)")
                } else {
                    close()
                    val permissionNotice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        " (Note: All Files Access permission is disabled. Grant permission in Settings → 📱 On-Device)"
                    } else ""
                    return Result.failure(Exception("Could not initialize model '$fileName' using MediaPipe (${lastMediaPipeErr ?: e.message}) or ONNX Runtime.$permissionNotice"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model file ${modelFile.name}: ${e.message}", e)
            close()
            Result.failure(Exception("Failed to load model file '${modelFile.name}': ${e.message}"))
        }
    }

    private fun saveSelectedModel(context: Context, modelFileName: String, absolutePath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SELECTED_MODEL, modelFileName)
            .putString(KEY_SELECTED_MODEL_PATH, absolutePath)
            .apply()
    }

    fun generateResponse(systemPrompt: String, userPrompt: String): String {
        // 1. If ONNX model is loaded into OrtSession, run ONNX Runtime forward pass
        if (OnnxNavPredictor.isModelLoaded) {
            Log.d(TAG, "Running pure ONNX Neural Network Forward Pass for $currentModelName")
            return OnnxNavPredictor.predict(userPrompt)
        }

        // 2. MediaPipe LlmInference engine
        val inference = llmInference
        if (inference != null) {
            val safeUserPrompt = if (userPrompt.length > 1200) {
                userPrompt.take(1200) + "\n...[truncated for on-device memory]"
            } else {
                userPrompt
            }

            val combinedPrompt = buildString {
                append("<start_of_turn>user\n")
                append(systemPrompt.take(800))
                append("\n\n")
                append(safeUserPrompt)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }

            val startTime = System.currentTimeMillis()
            val result = try {
                inference.generateResponse(combinedPrompt)
            } catch (t: Throwable) {
                Log.e(TAG, "MediaPipe native inference failed: ${t.message}", t)
                throw Exception("MediaPipe native model inference error: ${t.message ?: "Native engine exception"}")
            }
            val durationMs = System.currentTimeMillis() - startTime
            Log.d(TAG, "MediaPipe inference complete in ${durationMs}ms (${result.length} chars output)")

            return result.trim()
        }

        throw Exception("No active MediaPipe or ONNX model instance initialized for '$currentModelName'. Please ensure the model file is a valid MediaPipe (.task/.bin/.litertlm) or ONNX (.onnx) model.")
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
        currentModelPath = ""
    }
}
