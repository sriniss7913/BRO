package com.example.bro.network

data class GroqResponse(
    val choices: List<Choice>,
    val usage: GroqUsage? = null
)

data class Choice(
    val message: ResponseMessage
)

data class ResponseMessage(
    val role: String,
    val content: String
)

data class GroqUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)