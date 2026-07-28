package com.example.bro.network

data class GroqRequest(

    val model: String,

    val messages: List<Message>

)

data class Message(

    val role: String,

    val content: String

)