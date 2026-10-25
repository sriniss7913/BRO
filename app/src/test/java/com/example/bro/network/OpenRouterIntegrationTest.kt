package com.example.bro.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterIntegrationTest {

    @Test
    fun openRouterConfigContainsExpectedDefaultsAndModels() {
        assertEquals("meta-llama/llama-3.3-70b-instruct", GroqRepository.selectedOpenRouterModel)
        assertTrue(GroqRepository.OPENROUTER_MODELS.isNotEmpty())
        assertTrue(GroqRepository.OPENROUTER_MODELS.any { it.first == "meta-llama/llama-3.3-70b-instruct" })
        assertTrue(GroqRepository.OPENROUTER_MODELS.any { it.first == "google/gemini-2.0-flash-exp:free" })
        assertTrue(GroqRepository.OPENROUTER_MODELS.any { it.first == "deepseek/deepseek-r1" })
    }

    @Test
    fun retrofitClientProvidesOpenRouterBaseUrl() {
        assertEquals("https://openrouter.ai/api/", RetrofitClient.DEFAULT_OPENROUTER_URL)
    }

    @Test
    fun throwsClearExceptionWhenOpenRouterApiKeyIsMissing() = runBlocking {
        val repo = GroqRepository()
        GroqRepository.providerMode = "OPENROUTER"
        GroqRepository.openRouterApiKey = ""

        try {
            repo.sendPrompt("system prompt", "user prompt")
            fail("Expected exception when OpenRouter API key is missing")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("OpenRouter API key is missing"))
        } finally {
            GroqRepository.providerMode = "GROQ"
        }
    }
}
