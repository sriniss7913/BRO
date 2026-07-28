package com.example.bro.model

enum class ActionType {

    OPEN_APP,

    CLICK,

    TYPE,

    SCROLL,

    SWIPE,

    BACK,

    HOME,

    WAIT,

    READ_SCREEN,

    CHAT,

    FINISH
}


data class Action(

    val type: ActionType,

    val targetId: Int? = null,

    val target: String? = null,

    val text: String? = null,

    val packageName: String? = null,

    val summary: String? = null
)