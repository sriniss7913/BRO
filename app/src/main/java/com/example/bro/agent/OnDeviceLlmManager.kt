package com.example.bro.agent

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * On-Device LLM Manager using MediaPipe tasks-genai.
 *
 * Manages the lifecycle of a locally-loaded LLM model running
 * entirely on-device (phone GPU/NPU). Supports any .task model
 * file (Gemma 3 1B, custom fine-tuned models, etc.)
 *
 * Model files should be placed at:
 *   /sdcard/Android/data/com.example.bro/files/models/<model>.task
 */
object OnDeviceLlmManager {
    private const val TAG = "BRO_ON_DEVICE"
    private const val MODELS_DIR = "models"

    private var llmInference: LlmInference? = null
    private var currentModelName: String = ""

    val isModelLoaded: Boolean get() = llmInference != null
    val loadedModelName: String get() = currentModelName

    /**
     * Returns the directory where model files should be placed.
     */
    fun getModelsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Lists all available .task model files in the models directory.
     */
    fun listAvailableModels(context: Context): List<String> {
        val dir = getModelsDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".bin") || it.name.endsWith(".gguf")) }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * Load a model from the models directory.
     * This is a blocking operation that can take 10-30 seconds on first load.
     */
    fun loadModel(context: Context, modelFileName: String): Result<String> {
        return try {
            // Close existing model if loaded
            close()

            val modelFile = File(getModelsDir(context), modelFileName)
            if (!modelFile.exists()) {
                return Result.failure(Exception(
                    "Model file not found: ${modelFile.absolutePath}\n" +
                    "Place your .task model file at:\n${getModelsDir(context).absolutePath}/"
                ))
            }

            Log.d(TAG, "Loading on-device model: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            currentModelName = modelFileName

            Log.d(TAG, "Model loaded successfully: $modelFileName")
            Result.success("Model '$modelFileName' loaded successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            llmInference = null
            currentModelName = ""
            Result.failure(Exception("Failed to load model '$modelFileName': ${e.message}"))
        }
    }

    /**
     * Generate a response from the on-device model.
     * Combines system prompt and user prompt into a single input.
     */
    fun generateResponse(systemPrompt: String, userPrompt: String): String {
        val inference = llmInference
            ?: throw Exception("No on-device model loaded. Go to Settings → 📱 On-Device → Load Model")

        // Format prompt for instruction-following models
        // Most .task models use a simple prompt format
        val combinedPrompt = buildString {
            append("<start_of_turn>user\n")
            append("SYSTEM INSTRUCTIONS:\n")
            append(systemPrompt)
            append("\n\nUSER REQUEST:\n")
            append(userPrompt)
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }

        Log.d(TAG, "On-device inference starting (prompt ${combinedPrompt.length} chars)")
        val startTime = System.currentTimeMillis()

        val result = inference.generateResponse(combinedPrompt)

        val durationMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "On-device inference complete in ${durationMs}ms (${result.length} chars output)")

        return result.trim()
    }

    /**
     * Close and release the model resources.
     */
    fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model: ${e.message}")
        }
        llmInference = null
        currentModelName = ""
    }
}
