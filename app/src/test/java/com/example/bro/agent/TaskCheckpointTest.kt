package com.example.bro.agent

import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCheckpointTest {
    private val gson = Gson()

    @Test
    fun checkpointRoundTripRetainsTaskProgressAndPendingAction() {
        val checkpoint = TaskCheckpoint(
            goal = "Send hi to Ramesh on WhatsApp",
            originalPrompt = "send hi to Ramesh",
            executionMode = "ACCESSIBILITY",
            state = TaskCheckpointState.RUNNING,
            phase = TaskCheckpointPhase.DISPATCHING,
            step = 4,
            recentStepHistory = listOf("Step 3: WhatsApp chat -> TYPE -> Screen changed"),
            previousActions = listOf(Action(ActionType.TYPE, targetId = 47, text = "hi")),
            sendClickedThisTask = false,
            pendingAction = Action(ActionType.SEND_MESSAGE),
            pendingQuestion = null,
            questionChoices = emptyList()
        )

        val restored = gson.fromJson(gson.toJson(checkpoint), TaskCheckpoint::class.java)

        assertEquals(checkpoint.goal, restored.goal)
        assertEquals(checkpoint.step, restored.step)
        assertEquals(checkpoint.phase, restored.phase)
        assertEquals(ActionType.SEND_MESSAGE, restored.pendingAction?.type)
        assertEquals("hi", restored.previousActions.single().text)
        assertFalse(restored.sendClickedThisTask)
    }

    @Test
    fun waitingForUserCheckpointRetainsQuestionAndChoices() {
        val checkpoint = TaskCheckpoint(
            goal = "Find unread messages",
            originalPrompt = "check unread messages",
            executionMode = "ACCESSIBILITY",
            state = TaskCheckpointState.WAITING_FOR_USER,
            pendingQuestion = "Which contact should I open?",
            questionChoices = listOf("Ramesh", "Cancel task")
        )

        val restored = gson.fromJson(gson.toJson(checkpoint), TaskCheckpoint::class.java)

        assertEquals(TaskCheckpointState.WAITING_FOR_USER, restored.state)
        assertEquals("Which contact should I open?", restored.pendingQuestion)
        assertTrue(restored.questionChoices.contains("Ramesh"))
    }
}
