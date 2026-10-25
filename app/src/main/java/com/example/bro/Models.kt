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
    val packageName: String? = null,
    val app: String? = null,
    val targetId: Int? = null,
    val nodeId: Int? = null,       // new hierarchical tree node ID
    val x: Float? = null,
    val y: Float? = null,
    val text: String? = null,
    val query: String? = null,
    val direction: String? = null,
    val milliseconds: Long? = null,
    val desiredState: Boolean? = null,
    val actions: List<ChatResponse>? = null,
    val options: List<String>? = null,
    val question: String? = null,
    val subgoal: String? = null,
    val remainingPlan: String? = null
)

// isQuestion=true → renders Yes/No buttons instead of plain text
// pendingGoal → the original task goal to resume when user taps Yes
data class Message(
    val text: String,
    val isUser: Boolean,
    val isQuestion: Boolean = false,
    val pendingGoal: String = "",
    val choices: List<String> = emptyList(),
    val customReply: String = "",
    val subgoal: String = "",
    val remainingPlan: String = "",
    val targetPackage: String = ""
)