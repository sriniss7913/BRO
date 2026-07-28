package com.example.bro

data class ChatRequest(
    val goal: String,
    val screen: String
)

data class ChatResponse(
    val type: String? = null,
    val reply: String? = null,
    val target: String? = null,
    val targetId: Int? = null,
    val text: String? = null,
    val direction: String? = null,
    val milliseconds: Long? = null,
    val actions: List<ChatResponse>? = null
)