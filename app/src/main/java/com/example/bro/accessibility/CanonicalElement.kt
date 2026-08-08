package com.example.bro.accessibility

data class CanonicalElement(
    val targetId: Int,
    val role: String,
    val text: String = "",
    val contact: String = "",
    val preview: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false
)