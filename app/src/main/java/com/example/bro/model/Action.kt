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

    SEARCH,

    SEND_MESSAGE,

    TOGGLE_QUICK_SETTING,

    LOCK_SCREEN,

    FIND_APP,

    ASK_USER,

    FINISH,

    WEB_SEARCH,

    READ_NOTIFICATIONS
}


data class Action(

    val type: ActionType,

    val targetId: Int? = null,

    val x: Float? = null,

    val y: Float? = null,

    val target: String? = null,

    val text: String? = null,

    val packageName: String? = null,

    val app: String? = null,

    val summary: String? = null,

    val desiredState: Boolean? = null,

    val question: String? = null,

    val options: List<String>? = null,

    val subgoal: String? = null,

    val remainingPlan: String? = null
)