package com.example.bro.executor

import android.util.Log
import com.example.bro.agent.OnDeviceLlmManager
import com.example.bro.network.GroqRepository
import java.util.concurrent.ConcurrentHashMap

object SemanticEntityCritic {

    private const val TAG = "SemanticEntityCritic"
    private val verificationCache = ConcurrentHashMap<String, Boolean>()

    fun clearCache() {
        verificationCache.clear()
    }

    /**
     * Tier 2 Micro-LLM Critic: Evaluates whether candidate contact is a plausible match for the goal.
     * Only invoked when Tier 1 algorithmic matching is uncertain.
     */
    suspend fun isPlausibleMatch(
        goal: String,
        candidateName: String,
        groqRepository: GroqRepository? = null
    ): Boolean {
        val cacheKey = "${goal.lowercase().trim()}::${candidateName.lowercase().trim()}"
        verificationCache[cacheKey]?.let {
            Log.d(TAG, "Cache hit for '$cacheKey' -> $it")
            return it
        }

        val systemPrompt = """
            You are a strict entity validation critic for an Android phone automation assistant.
            Given the User Goal and the Candidate Contact name displayed on screen:
            Decide if Candidate Contact is a valid, intended match for the recipient in the goal.
            Consider:
            - Exact or partial name match (e.g., "Ramesh" -> "Rameshbabu")
            - Relationships/Nicknames (e.g., "Mom" -> "Amma", "Dad" -> "Appa", "Bro" -> brother's name)
            - Minor phonetic/spelling variations
            Output strict JSON: {"match": true} OR {"match": false, "reason": "different person"}
        """.trimIndent()

        val userPrompt = """
            User Goal: "$goal"
            Candidate Contact: "$candidateName"
        """.trimIndent()

        return try {
            val response = if (OnDeviceLlmManager.isModelLoaded) {
                Log.d(TAG, "Evaluating entity match on-device...")
                OnDeviceLlmManager.generateResponse(systemPrompt, userPrompt)
            } else {
                Log.d(TAG, "Evaluating entity match via Groq...")
                (groqRepository ?: GroqRepository()).sendPrompt(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxOutputTokens = 60,
                    requestJsonObject = true
                )
            }

            val isMatch = Regex(""""match"\s*:\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(response)
            Log.i(TAG, "Critic result for '$candidateName' -> match=$isMatch (raw: ${response.trim()})")
            verificationCache[cacheKey] = isMatch
            isMatch
        } catch (ex: Exception) {
            Log.w(TAG, "Critic evaluation failed, failing open: ${ex.message}")
            true
        }
    }
}
