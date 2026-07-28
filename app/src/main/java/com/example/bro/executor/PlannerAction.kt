package com.example.bro.planner

data class PlannerAction(
    val type: String,
    val targetId: Int? = null,
    val target: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val milliseconds: Long? = null,
    val reply: String? = null
)