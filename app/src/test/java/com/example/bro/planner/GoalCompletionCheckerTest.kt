package com.example.bro.planner

import com.example.bro.model.Goal
import com.example.bro.model.ScreenState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCompletionCheckerTest {
    private val screen = ScreenState("com.whatsapp", emptyList())

    @Test
    fun requiresSendActionEmptyInputAndDeliveryStatus() {
        val deliveredMessage = """
            SCREEN_STATE: WHATSAPP_CHAT
            AVAILABLE_ACTIONS: TYPE_MESSAGE, SEND_MESSAGE, BACK

            [1] MESSAGE_INPUT

            [2] TEXT
                text="Delivered"
        """.trimIndent()

        assertTrue(
            GoalCompletionChecker.isComplete(
                Goal("send hello on WhatsApp"),
                screen,
                null,
                deliveredMessage,
                sendWasClicked = true
            )
        )
        assertFalse(
            GoalCompletionChecker.isComplete(
                Goal("send hello on WhatsApp"),
                screen,
                null,
                deliveredMessage,
                sendWasClicked = false
            )
        )
    }

    @Test
    fun doesNotCompleteWhileMessageRemainsInInput() {
        val messageStillTyped = """
            SCREEN_STATE: WHATSAPP_CHAT
            AVAILABLE_ACTIONS: TYPE_MESSAGE, SEND_MESSAGE, BACK

            [1] MESSAGE_INPUT
                text="Hello"

            [2] TEXT
                text="Sent"
        """.trimIndent()

        assertFalse(
            GoalCompletionChecker.isComplete(
                Goal("send hello on WhatsApp"),
                screen,
                null,
                messageStillTyped,
                sendWasClicked = true
            )
        )
    }
}
