package com.example.bro

data class ChatRequest(
    val goal: String,
    val screen: String
)

data class ChatResponse(
    val thought: String? = null,
    val type: String? = null,
    val reply: String? = null,
    val target: String? = null,
    val targetId: Int? = null,
    val nodeId: Int? = null,       // new hierarchical tree node ID
    val text: String? = null,
    val query: String? = null,
    val direction: String? = null,
    val milliseconds: Long? = null,
    val actions: List<ChatResponse>? = null
)

// isQuestion=true → renders Yes/No buttons instead of plain text
// pendingGoal → the original task goal to resume when user taps Yes
data class Message(
    val text: String,
    val isUser: Boolean,
    val isQuestion: Boolean = false,
    val pendingGoal: String = ""
)