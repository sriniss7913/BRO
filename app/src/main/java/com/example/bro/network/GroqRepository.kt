package com.example.bro.network

import android.util.Log
import kotlinx.coroutines.delay

class GroqRepository(
    private val api: GroqApi = RetrofitClient.api
) {

    companion object {

        // IMPORTANT:
        // Do not ship a permanent API key inside the APK.
        // We'll move this to secure/local configuration later.
        var apiKey: String = "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"
        var providerMode: String = "GROQ" // "GROQ" or "LOCAL_PC"
        var localPcUrl: String = "http://192.168.1.35:1234/v1/"
        var localModelName: String = "local-model"
        var selectedGroqModel: String = "llama-3.3-70b-versatile"

        private val MODELS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "deepseek-r1-distill-llama-70b",
            "gemma2-9b-it",
            "mixtral-8x7b-32768",
            "llama3-70b-8192",
            "llama3-8b-8192"
        )

        // Models that have hit their daily quota.
        private val tpdExhaustedModels = mutableSetOf<String>()

        private var lastCallMs = 0L
        private const val MIN_INTERVAL_MS = 500L

        // Token statistics for this app process.
        private var requestCount = 0
        private var successfulCallCount = 0

        private var cumulativePromptTokens = 0L
        private var cumulativeCompletionTokens = 0L
        private var cumulativeTotalTokens = 0L
    }

    suspend fun sendPrompt(
        systemPrompt: String,
        userPrompt: String
    ): String {

        val now = System.currentTimeMillis()
        val waitMs = MIN_INTERVAL_MS - (now - lastCallMs)

        if (waitMs > 0) {
            delay(waitMs)
        }

        lastCallMs = System.currentTimeMillis()

        // ON-DEVICE MODEL BRANCH
        if (providerMode == "ON_DEVICE") {
            if (!com.example.bro.agent.OnDeviceLlmManager.isModelLoaded) {
                throw Exception("No on-device model loaded. Go to Settings → 📱 On-Device → Load Model")
            }
            Log.d("BRO_GROQ", "Sending prompt to On-Device Model (${com.example.bro.agent.OnDeviceLlmManager.loadedModelName}) on Dispatchers.IO...")
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.bro.agent.OnDeviceLlmManager.generateResponse(systemPrompt, userPrompt)
                }
                if (result.isBlank()) {
                    throw Exception("On-device model returned empty response")
                }
                return result
            } catch (e: Exception) {
                Log.e("BRO_GROQ", "On-Device Model error: ${e.message}")
                throw Exception("On-Device Model error: ${e.message}")
            }
        }

        // LOCAL PC MODEL SERVER BRANCH
        if (providerMode == "LOCAL_PC") {
            var baseUrl = localPcUrl.trim()
            if (baseUrl.endsWith("/v1/")) {
                baseUrl = baseUrl.substringBeforeLast("v1/")
            } else if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substringBeforeLast("v1")
            }
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/"
            }

            val customApi = RetrofitClient.createCustomApi(baseUrl)
            val request = GroqRequest(
                model = localModelName.ifBlank { "local-model" },
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userPrompt)
                )
            )
            Log.d("BRO_GROQ", "Sending prompt to Local PC Model at ${baseUrl}v1/chat/completions (model=$localModelName)")
            try {
                val response = customApi.chat(authorization = "Bearer local-key", request = request)
                if (response.isSuccessful) {
                    val body = response.body() ?: throw Exception("Empty response from Local PC Model")
                    return body.choices.firstOrNull()?.message?.content
                        ?: throw Exception("No choice returned from Local PC Model")
                } else {
                    val err = response.errorBody()?.string() ?: response.message()
                    throw Exception("Local PC Model error (${response.code()}): $err")
                }
            } catch (e: Exception) {
                Log.e("BRO_GROQ", "Local PC Model connection error: ${e.message}")
                throw Exception("Cannot connect to PC Server at '$localPcUrl'. Ensure LM Studio / Ollama is running and phone is on same Wi-Fi. Error: ${e.message}")
            }
        }

        val key = apiKey.trim()

        if (key.isBlank() || key == "PUT_YOUR_NEW_GROQ_API_KEY_HERE") {
            throw Exception(
                "Groq API key is missing. Please enter your API key in Settings."
            )
        }

        var lastException: Exception? = null

        val modelsToTry = mutableListOf(selectedGroqModel)
        modelsToTry.addAll(MODELS.filter { it != selectedGroqModel })

        for (modelName in modelsToTry) {

            if (tpdExhaustedModels.contains(modelName)) {
                Log.d(
                    "BRO_GROQ",
                    "Skipping exhausted model=$modelName"
                )
                continue
            }

            val request = GroqRequest(
                model = modelName,
                messages = listOf(
                    Message(
                        role = "system",
                        content = systemPrompt
                    ),
                    Message(
                        role = "user",
                        content = userPrompt
                    )
                )
            )

            requestCount++

            val currentRequest = requestCount

            Log.d(
                "BRO_GROQ",
                "REQUEST #$currentRequest | model=$modelName"
            )

            Log.d(
                "BRO_GROQ",
                "Prompt chars | system=${systemPrompt.length} | " +
                        "user=${userPrompt.length} | " +
                        "total=${systemPrompt.length + userPrompt.length}"
            )

            val startTime = System.currentTimeMillis()

            try {

                val response = api.chat(
                    authorization = "Bearer $key",
                    request = request
                )

                val durationMs =
                    System.currentTimeMillis() - startTime

                if (response.isSuccessful) {

                    val body = response.body()
                        ?: throw Exception(
                            "Empty response from Groq API"
                        )

                    val usage = body.usage

                    val promptTokens =
                        usage?.prompt_tokens ?: 0

                    val completionTokens =
                        usage?.completion_tokens ?: 0

                    val totalTokens =
                        usage?.total_tokens ?: 0

                    successfulCallCount++

                    cumulativePromptTokens += promptTokens
                    cumulativeCompletionTokens += completionTokens
                    cumulativeTotalTokens += totalTokens

                    Log.d(
                        "BRO_TOKEN",
                        "REQUEST #$currentRequest | " +
                                "model=$modelName | " +
                                "input=$promptTokens | " +
                                "output=$completionTokens | " +
                                "total=$totalTokens | " +
                                "time=${durationMs}ms"
                    )

                    Log.d(
                        "BRO_TOKEN",
                        "SESSION TOTAL | " +
                                "successful_calls=$successfulCallCount | " +
                                "input=$cumulativePromptTokens | " +
                                "output=$cumulativeCompletionTokens | " +
                                "total=$cumulativeTotalTokens"
                    )

                    return body.choices
                        .firstOrNull()
                        ?.message
                        ?.content
                        ?: throw Exception(
                            "No response choice returned by Groq API"
                        )
                }

                val code = response.code()

                val errorMsg =
                    response.errorBody()?.string()
                        ?: response.message()

                Log.w(
                    "BRO_GROQ",
                    "REQUEST #$currentRequest FAILED | " +
                            "model=$modelName | " +
                            "code=$code | " +
                            "time=${durationMs}ms"
                )

                if (
                    code == 429 ||
                    code == 400 ||
                    errorMsg.contains(
                        "limit",
                        ignoreCase = true
                    )
                ) {

                    Log.w(
                        "GROQ_REPOSITORY",
                        "Model $modelName quota/limit reached ($code). " +
                                "Marking exhausted and trying fallback."
                    )

                    tpdExhaustedModels.add(modelName)

                    lastException =
                        Exception(
                            "Model $modelName daily limit reached"
                        )

                    continue
                }

                Log.w(
                    "GROQ_REPOSITORY",
                    "Model $modelName error " +
                            "($code: $errorMsg). " +
                            "Trying fallback model."
                )

                lastException =
                    Exception(
                        "Groq API Error ($code): $errorMsg"
                    )

            } catch (e: Exception) {

                Log.e(
                    "GROQ_REPOSITORY",
                    "REQUEST #$currentRequest | " +
                            "Exception on model $modelName: ${e.message}"
                )

                lastException = e
            }
        }

        tpdExhaustedModels.clear()

        throw lastException
            ?: Exception(
                "All Groq models exhausted token limit. " +
                        "Please try again in a few minutes."
            )
    }
}