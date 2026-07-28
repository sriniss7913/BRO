package com.example.bro.model

data class PlannerResponse(
    val thought: String,
    val action: Action,
    val actions: List<Action>? = null
)