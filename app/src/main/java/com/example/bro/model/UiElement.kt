package com.example.bro.model

data class UiElement(

    val id: Int,

    val text: String?,

    val contentDescription: String?,

    val className: String?,

    val clickable: Boolean,

    val enabled: Boolean,

    val editable: Boolean,

    val checkable: Boolean,

    val checked: Boolean

)