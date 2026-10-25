package com.example.bro.agent

import com.example.bro.model.Action

class AgentMemory {

    val previousActions = mutableListOf<Action>()

    var step: Int = 0

    var lastScreenHash: Int = 0

    var retryCount: Int = 0

    var lastActionSuccess: Boolean? = null

    var lastResult: String = ""

    fun restore(
        actions: List<Action>,
        step: Int,
        success: Boolean? = null,
        result: String = ""
    ) {
        previousActions.clear()
        previousActions.addAll(actions.takeLast(20))
        this.step = step.coerceAtLeast(0)
        lastActionSuccess = success
        lastResult = result
    }

    fun remember(
        action: Action,
        success: Boolean? = null,
        result: String = ""
    ) {

        previousActions.add(action)

        if (previousActions.size > 20) {
            previousActions.removeAt(0)
        }

        step++

        lastActionSuccess = success
        lastResult = result
    }

    fun clear() {

        previousActions.clear()

        step = 0
        retryCount = 0
        lastScreenHash = 0
        lastActionSuccess = null
        lastResult = ""
    }
}