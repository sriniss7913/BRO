package com.example.bro.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class GroqRepository(
    private val api: GroqApi = RetrofitClient.api
) {

    companion object {

        // IMPORTANT:
        // Do not ship a permanent API key inside the APK.
        // We'll move this to secure/local configuration later.
        var apiKey: String = "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"
        var providerMode: String = "GROQ" // "GROQ", "OPENROUTER", "LOCAL_PC", "ON_DEVICE"
        var localPcUrl: String = "http://192.168.1.35:1234/v1/"
        var localModelName: String = "local-model"
        var selectedGroqModel: String = "llama-3.3-70b-versatile"
        var openRouterApiKey: String = ""
        var selectedOpenRouterModel: String = "meta-llama/llama-3.3-70b-instruct"

        fun isCloudConfigured(): Boolean {
            return when (providerMode) {
                "GROQ" -> apiKey.isNotBlank() && !apiKey.contains("PASTE_")
                "OPENROUTER" -> openRouterApiKey.isNotBlank()
                "LOCAL_PC" -> localPcUrl.isNotBlank()
                "ON_DEVICE" -> false
                else -> false
            }
        }

        val OPENROUTER_MODELS = listOf(
            "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B Instruct (Fast & Powerful)",
            "google/gemini-2.0-flash-exp:free" to "Gemini 2.0 Flash (Free)",
            "deepseek/deepseek-r1" to "DeepSeek R1 (Reasoning)",
            "deepseek/deepseek-chat" to "DeepSeek V3",
            "anthropic/claude-3.5-sonnet" to "Claude 3.5 Sonnet",
            "openai/gpt-4o-mini" to "GPT-4o Mini",
            "qwen/qwen-2.5-72b-instruct" to "Qwen 2.5 72B Instruct",
            "mistralai/mistral-large-2411" to "Mistral Large"
        )

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
        userPrompt: String,
        maxOutputTokens: Int? = null,
        requestJsonObject: Boolean = false
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BRO_GROQ", "On-Device Model error: ${e.message}")
                throw Exception("On-Device Model error: ${e.message}")
            }
        }

        // OPENROUTER BRANCH
        if (providerMode == "OPENROUTER") {
            val key = openRouterApiKey.trim()
            if (key.isBlank()) {
                throw Exception("OpenRouter API key is missing. Please enter your OpenRouter key in Settings (get one at openrouter.ai/keys).")
            }

            val modelName = selectedOpenRouterModel.ifBlank { "meta-llama/llama-3.3-70b-instruct" }
            val request = GroqRequest(
                model = modelName,
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userPrompt)
                ),
                maxTokens = maxOutputTokens,
                responseFormat = if (requestJsonObject) ResponseFormat("json_object") else null
            )

            requestCount++
            val currentRequest = requestCount
            Log.d("BRO_OPENROUTER", "REQUEST #$currentRequest | model=$modelName")
            val startTime = System.currentTimeMillis()

            try {
                val response = RetrofitClient.openRouterApi.chat(
                    authorization = "Bearer $key",
                    request = request
                )
                val durationMs = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    val body = response.body() ?: throw Exception("Empty response from OpenRouter API")
                    val usage = body.usage
                    if (usage != null) {
                        cumulativePromptTokens += usage.prompt_tokens
                        cumulativeCompletionTokens += usage.completion_tokens
                        cumulativeTotalTokens += usage.total_tokens
                    }
                    successfulCallCount++
                    val content = body.choices.firstOrNull()?.message?.content
                        ?: throw Exception("No choice message returned from OpenRouter API")
                    Log.d("BRO_OPENROUTER", "RESPONSE #$currentRequest SUCCESS in ${durationMs}ms | tokens=${usage?.total_tokens ?: 0}")
                    return content
                } else {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    Log.e("BRO_OPENROUTER", "OpenRouter HTTP ${response.code()}: $errorBody")
                    throw Exception("OpenRouter API error (${response.code()}): $errorBody")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BRO_OPENROUTER", "OpenRouter error: ${e.message}")
                throw e
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
                ),
                maxTokens = maxOutputTokens
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
            } catch (e: CancellationException) {
                throw e
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
                ),
                maxTokens = maxOutputTokens,
                responseFormat = if (requestJsonObject) ResponseFormat("json_object") else null
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

            } catch (e: CancellationException) {
                throw e
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