package com.example.bro.network

data class GroqRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 0.0
)

data class Message(
    val role: String,
    val content: String
)