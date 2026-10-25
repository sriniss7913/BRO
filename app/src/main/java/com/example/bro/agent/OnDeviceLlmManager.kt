package com.example.bro.agent

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Modality
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val engineMutex = Mutex()
    private var llmInference: LlmInference? = null
    private var liteRtLmEngine: Engine? = null
    private var currentModelName: String = ""
    private var currentModelPath: String = ""
    private var currentLiteRtBackend: String = ""
    private var currentLiteRtVisionBackend: String = ""
    private var liteRtVisionConfigured = false
    private var liteRtCachePath: String = ""
    private var currentLiteRtSupportsVision: Boolean? = null
    private var currentLiteRtVisionBackends: List<String> = emptyList()

    val isModelLoaded: Boolean get() = llmInference != null || liteRtLmEngine != null || OnnxNavPredictor.isModelLoaded
    val loadedModelName: String get() = currentModelName
    val loadedModelPath: String get() = currentModelPath
    val loadedModelSupportsVision: Boolean? get() = currentLiteRtSupportsVision
    val loadedModelBackend: String
        get() = if (currentLiteRtBackend == "GPU") "GPU (FLOAT32 activations)" else currentLiteRtBackend

    data class ModelFileInfo(
        val fileName: String,
        val absolutePath: String,
        val sizeBytes: Long
    )

    @Synchronized
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        customModelDirPath = prefs.getString(KEY_MODEL_DIR, "/sdcard/BRO/") ?: "/sdcard/BRO/"
        val savedModelPath = prefs.getString(KEY_SELECTED_MODEL_PATH, "") ?: ""
        val savedModelName = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""

        if (savedModelPath.isNotBlank()) {
            val file = File(savedModelPath)
            if (file.exists() && file.isFile) {
                val result = loadModelByFile(context, file)
                result.onSuccess {
                    Log.d(TAG, "Restored selected model from exact path on startup: ${file.absolutePath}")
                }.onFailure {
                    Log.e(TAG, "Could not restore selected model from ${file.absolutePath}: ${it.message}", it)
                }
                return
            }
        }

        if (savedModelName.isNotBlank()) {
            val file = findModelFile(context, savedModelName)
            if (file != null && file.exists()) {
                val result = loadModelByFile(context, file)
                result.onSuccess {
                    Log.d(TAG, "Restored selected model on startup: $savedModelName at ${file.absolutePath}")
                }.onFailure {
                    Log.e(TAG, "Could not restore selected model '$savedModelName': ${it.message}", it)
                }
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

    @Synchronized
    fun loadModel(context: Context, modelPathOrName: String): Result<String> {
        return try {
            val modelFile = findModelFile(context, modelPathOrName)
                ?: return Result.failure(Exception("Model file '$modelPathOrName' not found"))

            if (isAlreadyLoaded(modelFile)) {
                return Result.success("Model '${modelFile.name}' is already loaded.")
            }

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

    @Synchronized
    fun loadModelByFile(context: Context, modelFile: File): Result<String> {
        return try {
            if (isAlreadyLoaded(modelFile)) {
                return Result.success("Model '${modelFile.name}' is already loaded.")
            }

            close()
            Log.d(TAG, "Loading on-device model: ${modelFile.absolutePath} (${modelFile.length() / 1024} KB)")
            logAvailableMemory(context, modelFile)

            val lowerName = modelFile.name.lowercase()
            val fileName = modelFile.name
            var targetFile = modelFile

            if (lowerName.endsWith(".litertlm")) {
                targetFile = prepareFileForNativeAccess(context, modelFile)
                try {
                    ModelInfo.from(targetFile.absolutePath).use { modelInfo ->
                        val modalities = modelInfo.inputModalities()
                        val visionBackends = modelInfo.supportedBackends(Modality.VISION)
                            .mapNotNull { backend ->
                                when (backend) {
                                    BackendType.GPU -> "GPU"
                                    BackendType.CPU -> "CPU"
                                    else -> null
                                }
                            }
                        currentLiteRtSupportsVision = modalities.vision
                        currentLiteRtVisionBackends = visionBackends
                        Log.i(
                            TAG,
                            "LiteRT-LM capabilities for '$fileName': " +
                                "text=${modalities.text}, vision=${modalities.vision}, " +
                                "audio=${modalities.audio}, video=${modalities.video}, " +
                                "visionBackends=${visionBackends.ifEmpty { listOf("runtime default") }}"
                        )
                    }
                } catch (metadataError: Exception) {
                    currentLiteRtSupportsVision = null
                    currentLiteRtVisionBackends = emptyList()
                    Log.e(TAG, "Could not inspect LiteRT-LM capabilities for '$fileName'", metadataError)
                }
                val usesGpuBackend = Regex("(?:^|[-_])gpu(?:[-_.]|$)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(fileName)
                val backendName = if (usesGpuBackend) "GPU" else "CPU"
                val cacheDir = if (usesGpuBackend) {
                    val safeModelName = File(fileName).nameWithoutExtension
                        .lowercase()
                        .replace(Regex("[^a-z0-9]+"), "_")
                        .trim('_')
                    File(context.cacheDir, "litertlm/${safeModelName}_gpu_float32")
                } else {
                    File(context.cacheDir, "litertlm")
                }
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    return Result.failure(Exception("Could not create LiteRT-LM cache directory"))
                }

                try {
                    val engine = createLiteRtEngine(
                        modelPath = targetFile.absolutePath,
                        backendName = backendName,
                        cachePath = cacheDir.absolutePath,
                        enableVision = false
                    )
                    engine.initialize()
                    liteRtLmEngine = engine
                    currentModelName = fileName
                    currentModelPath = targetFile.absolutePath
                    currentLiteRtBackend = backendName
                    currentLiteRtVisionBackend = ""
                    liteRtCachePath = cacheDir.absolutePath
                    liteRtVisionConfigured = false
                    saveSelectedModel(context, fileName, targetFile.absolutePath)
                    val precisionLabel = if (usesGpuBackend) " (FLOAT32 activations)" else ""
                    val visionCapability = when (currentLiteRtSupportsVision) {
                        true -> "Vision supported"
                        false -> "Vision not supported by this artifact"
                        null -> "Vision capability unknown"
                    }
                    Log.d(
                        TAG,
                        "LiteRT-LM model loaded successfully: $fileName using $backendName backend$precisionLabel; " +
                            "$visionCapability; vision encoder will be configured only for image requests"
                    )
                    return Result.success(
                        "Loaded LiteRT-LM '$fileName' using $backendName backend$precisionLabel " +
                            "(${targetFile.length() / 1024 / 1024} MB). $visionCapability."
                    )
                } catch (e: Exception) {
                    close()
                    return Result.failure(Exception("Could not initialize LiteRT-LM model '$fileName' using $backendName backend: ${e.message}"))
                }
            }

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
                    .setMaxTokens(2048)
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
                    .setMaxTokens(2048)
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

    private fun isAlreadyLoaded(modelFile: File): Boolean {
        if (!isModelLoaded || currentModelName != modelFile.name) return false
        val loadedFile = File(currentModelPath)
        return loadedFile.exists() && loadedFile.length() == modelFile.length()
    }

    private fun logAvailableMemory(context: Context, modelFile: File) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        Log.i(
            TAG,
            "Model load memory snapshot: model=${modelFile.name}, size=${modelFile.length() / (1024 * 1024)} MB, " +
                "available=${memoryInfo.availMem / (1024 * 1024)} MB, lowMemory=${memoryInfo.lowMemory}"
        )
    }

    private fun saveSelectedModel(context: Context, modelFileName: String, absolutePath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SELECTED_MODEL, modelFileName)
            .putString(KEY_SELECTED_MODEL_PATH, absolutePath)
            .apply()
    }

    private fun createLiteRtEngine(
        modelPath: String,
        backendName: String,
        cachePath: String,
        enableVision: Boolean,
        visionBackendName: String = backendName
    ): Engine {
        val backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU()
        val visionBackend = if (visionBackendName == "GPU") Backend.GPU() else Backend.CPU()
        val activationDataType = if (backendName == "GPU") ActivationDataType.FLOAT32 else null
        val config = if (enableVision) {
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = visionBackend,
                maxNumImages = 1,
                cacheDir = cachePath,
                activationDataType = activationDataType
            )
        } else {
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = cachePath,
                activationDataType = activationDataType
            )
        }
        return Engine(config)
    }

    @Synchronized
    fun prepareModelForVision(context: Context): String? {
        if (!isModelLoaded || !currentModelName.endsWith(".litertlm", ignoreCase = true)) {
            throw IllegalStateException("Vision mode requires a loaded LiteRT-LM model.")
        }
        if (currentLiteRtSupportsVision == true) return null
        if (currentLiteRtSupportsVision != false) {
            throw IllegalStateException(
                "Vision support could not be confirmed for '$currentModelName'. Reload the model and check its capability report."
            )
        }

        val selectedArtifactName = currentModelName
        val companionName = selectedArtifactName.replace(
            Regex("[-_]gpu(?=\\.litertlm$)", RegexOption.IGNORE_CASE),
            ""
        )
        if (companionName == selectedArtifactName) {
            throw IllegalStateException(
                "LiteRT-LM model '$selectedArtifactName' does not include image input support."
            )
        }
        val companionFile = findModelFile(context, companionName)
            ?: throw IllegalStateException(
                "'$currentModelName' is text-only. Vision requires its multimodal companion '$companionName', which was not found."
            )
        val companionSupportsVision = try {
            val nativeFile = prepareFileForNativeAccess(context, companionFile)
            ModelInfo.from(nativeFile.absolutePath).use { it.inputModalities().vision }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Could not verify Vision support in '$companionName': ${error.message}",
                error
            )
        }
        if (!companionSupportsVision) {
            throw IllegalStateException(
                "The companion model '$companionName' does not contain a Vision encoder."
            )
        }

        loadModelByFile(context, companionFile).getOrThrow()
        val notice = "The selected GPU artifact '$selectedArtifactName' is text-only. Vision is using its multimodal companion '$companionName' on CPU."
        Log.i(TAG, notice)
        return notice
    }

    private fun enableLiteRtVisionIfNeeded() {
        if (liteRtVisionConfigured) return
        if (currentLiteRtSupportsVision == false) {
            throw IllegalStateException(
                "LiteRT-LM model '$currentModelName' does not include image input support. " +
                    "Load a multimodal .litertlm artifact with a Vision encoder."
            )
        }
        val modelPath = currentModelPath
        if (modelPath.isBlank()) throw IllegalStateException("No LiteRT-LM model path is available for Vision mode.")

        liteRtLmEngine?.close()
        liteRtLmEngine = null
        liteRtVisionConfigured = false
        val visionBackends = (
            currentLiteRtVisionBackends + listOf(currentLiteRtBackend, "CPU", "GPU")
        ).distinct()
        val backendErrors = mutableListOf<String>()
        var visionEngine: Engine? = null

        for (visionBackendName in visionBackends) {
            val visionCachePath = File(
                liteRtCachePath,
                "vision_${visionBackendName.lowercase()}"
            )
            if (!visionCachePath.exists() && !visionCachePath.mkdirs()) {
                backendErrors.add("$visionBackendName: could not create vision cache directory")
                continue
            }
            var candidate: Engine? = null
            try {
                Log.i(TAG, "Initializing '$currentModelName' Vision encoder with $visionBackendName backend")
                val engine = createLiteRtEngine(
                    modelPath = modelPath,
                    backendName = currentLiteRtBackend,
                    cachePath = visionCachePath.absolutePath,
                    enableVision = true,
                    visionBackendName = visionBackendName
                )
                candidate = engine
                engine.initialize()
                visionEngine = engine
                currentLiteRtVisionBackend = visionBackendName
                break
            } catch (backendError: Exception) {
                candidate?.let {
                    try {
                        it.close()
                    } catch (closeError: Exception) {
                        backendError.addSuppressed(closeError)
                    }
                }
                Log.e(TAG, "Vision encoder initialization failed with $visionBackendName backend", backendError)
                backendErrors.add("$visionBackendName: ${backendError.message ?: backendError.javaClass.simpleName}")
            }
        }

        if (visionEngine != null) {
            liteRtLmEngine = visionEngine
            liteRtVisionConfigured = true
            Log.i(TAG, "Vision encoder initialized for '$currentModelName' using $currentLiteRtVisionBackend backend")
            return
        }

        val restoreError = try {
            val textEngine = createLiteRtEngine(
                modelPath = modelPath,
                backendName = currentLiteRtBackend,
                cachePath = liteRtCachePath,
                enableVision = false
            )
            textEngine.initialize()
            liteRtLmEngine = textEngine
            null
        } catch (error: Exception) {
            Log.e(TAG, "Failed to restore text inference after Vision initialization failed", error)
            error
        }
        val details = backendErrors.joinToString("; ")
        if (restoreError != null) {
            throw IllegalStateException(
                "Vision encoder initialization failed for '$currentModelName' ($details), and text inference could not be restored: ${restoreError.message}",
                restoreError
            )
        }
        throw IllegalStateException(
            "Could not initialize the Vision encoder for '$currentModelName'. Tried ${visionBackends.joinToString()} backends: $details"
        )
    }

    suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        imageBytes: ByteArray? = null
    ): String = withContext(Dispatchers.Default) {
        engineMutex.withLock {

        if (imageBytes != null) {
            if (liteRtLmEngine == null) {
                throw Exception("Vision mode requires a loaded LiteRT-LM model.")
            }
            enableLiteRtVisionIfNeeded()
            val visionEngine = liteRtLmEngine
                ?: throw Exception("LiteRT-LM Vision engine was not initialized.")
            return@withLock try {
                generateLiteRtText(visionEngine, systemPrompt, userPrompt, imageBytes)
            } catch (visionError: Exception) {
                Log.e(
                    TAG,
                    "Vision inference failed for '$currentModelName' using ${currentLiteRtVisionBackend.ifBlank { "unknown" }} vision backend",
                    visionError
                )
                liteRtLmEngine?.close()
                liteRtLmEngine = null
                liteRtVisionConfigured = false
                try {
                    val textEngine = createLiteRtEngine(
                        modelPath = currentModelPath,
                        backendName = currentLiteRtBackend,
                        cachePath = liteRtCachePath,
                        enableVision = false
                    )
                    textEngine.initialize()
                    liteRtLmEngine = textEngine
                } catch (restoreError: Exception) {
                    visionError.addSuppressed(restoreError)
                    throw Exception(
                        "Vision inference failed and restoring text inference also failed: ${restoreError.message}",
                        visionError
                    )
                }
                throw Exception(
                    "Vision inference failed for '$currentModelName' using ${currentLiteRtVisionBackend.ifBlank { "unknown" }} vision backend. " +
                        "Text inference was restored. Details: ${visionError.message}",
                    visionError
                )
            }
        }

        liteRtLmEngine?.let { engine ->
            return@withLock generateLiteRtText(engine, systemPrompt, userPrompt)
        }

        // ONNX path
        if (OnnxNavPredictor.isModelLoaded) {
            Log.d(
                TAG,
                "Running pure ONNX Neural Network Forward Pass for $currentModelName"
            )
            return@withLock OnnxNavPredictor.predict(userPrompt)
        }

        val inference = llmInference
            ?: throw Exception(
                "No active MediaPipe model initialized for '$currentModelName'."
            )

        // Limit huge accessibility prompts.
        val safeUserPrompt =
            if (userPrompt.length > 1800) {
                userPrompt.take(1800) + "\n...[truncated]"
            } else {
                userPrompt
            }

        // systemPrompt still comes from Bro's editable settings.
        val isSmolLm = currentModelName.lowercase().contains("smollm") || currentModelName.lowercase().contains("qwen") || currentModelName.lowercase().contains("lora")
        val combinedPrompt = if (isSmolLm) {
            buildString {
                append("<|system|>\n")
                append(systemPrompt)
                append("\n<|user|>\n")
                append(safeUserPrompt)
                append("\n<|assistant|>\n")
            }
        } else {
            buildString {
                append("<start_of_turn>user\n")
                append(systemPrompt)
                append("\n\n")
                append(safeUserPrompt)
                append("\n<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
        }

        Log.d(TAG, "LOCAL MODEL INPUT:\n$combinedPrompt")

        val startTime = System.currentTimeMillis()

        val rawResult: String = try {
            inference.generateResponse(combinedPrompt)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaPipe inference failed: ${t.message}", t)

            throw Exception(
                "MediaPipe model inference error: " +
                        (t.message ?: "Unknown native inference error")
            )
        }

        val durationMs = System.currentTimeMillis() - startTime

        Log.d(
            TAG,
            "MediaPipe inference complete in ${durationMs}ms " +
                    "(${rawResult.length} chars output)"
        )

        // This is what we need for debugging SmolLM.
        Log.d(TAG, "LOCAL MODEL RAW OUTPUT:\n$rawResult")

        return@withLock rawResult.trim()
        }
    }

    private fun generateLiteRtText(
        engine: Engine,
        systemPrompt: String,
        userPrompt: String,
        imageBytes: ByteArray? = null
    ): String {
        val conversation = engine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(systemPrompt), maxOutputToken = 512)
        )
        try {
            val response = if (imageBytes == null) {
                conversation.sendMessage(userPrompt)
            } else {
                conversation.sendMessage(
                    Contents.of(Content.ImageBytes(imageBytes), Content.Text(userPrompt))
                )
            }
            return extractLiteRtText(response)
        } finally {
            conversation.close()
        }
    }

    private fun extractLiteRtText(message: com.google.ai.edge.litertlm.Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .trim()

    @Synchronized
    fun close() {
        try {
            llmInference?.close()
            liteRtLmEngine?.close()
            OnnxNavPredictor.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model: ${e.message}")
        }
        llmInference = null
        liteRtLmEngine = null
        currentModelName = ""
        currentModelPath = ""
        currentLiteRtBackend = ""
        currentLiteRtVisionBackend = ""
        liteRtVisionConfigured = false
        liteRtCachePath = ""
        currentLiteRtSupportsVision = null
        currentLiteRtVisionBackends = emptyList()
    }
}
