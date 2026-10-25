package com.example.bro.network

import com.google.gson.annotations.SerializedName

data class GroqRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 0.0,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("response_format")
    val responseFormat: ResponseFormat? = null
)

data class ResponseFormat(val type: String)

data class Message(
    val role: String,
    val content: String
)