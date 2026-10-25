package com.example.bro.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class UserConversationMemoryTest {

    @Test
    fun testAlarmTurnOffResolution() {
        val prevGoal = "set an alarm at 5 am tomorrow morning"

        val resolved1 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "turn it off")
        assertEquals("turn off the alarm at 5 am tomorrow morning", resolved1)

        val resolved2 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "turn off")
        assertEquals("turn off the alarm at 5 am tomorrow morning", resolved2)

        val resolved3 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "cancel it")
        assertEquals("cancel the alarm at 5 am tomorrow morning", resolved3)

        val resolved4 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "delete it")
        assertEquals("delete the alarm at 5 am tomorrow morning", resolved4)

        val resolved5 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "disable it")
        assertEquals("turn off the alarm at 5 am tomorrow morning", resolved5)

        val resolved6 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "change it to 6 am")
        assertEquals("change the alarm time to 6 am", resolved6)
    }

    @Test
    fun testTimerTurnOffResolution() {
        val prevGoal = "set a timer for 10 minutes"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "turn it off")
        assertEquals("cancel the timer for 10 minutes", resolved)

        val cancelResolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "cancel it")
        assertEquals("cancel the timer for 10 minutes", cancelResolved)
    }

    @Test
    fun testFlashlightTurnOffResolution() {
        val prevGoal = "turn on flashlight"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "turn it off")
        assertEquals("turn off flashlight", resolved)
    }

    @Test
    fun testMessagingFollowUp() {
        val prevGoal = "send message to Ramesh saying hello"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "send to Arun instead")
        assertEquals("send message to Arun saying hello", resolved)
    }

    @Test
    fun testContinuationPrompt() {
        val prevGoal = "set an alarm at 5 am"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "ok do that")
        assertEquals("set an alarm at 5 am", resolved)
    }

    @Test
    fun testParameterClarificationResolution() {
        val prevGoal = "check bus availability tomorrow"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "from Chennai to Bangalore")
        assertEquals("check bus availability tomorrow (from Chennai to Bangalore)", resolved)
    }

    @Test
    fun testIndependentPromptNotOverwritten() {
        val prevGoal = "set an alarm at 5 am"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "open spotify and play jazz")
        assertEquals("open spotify and play jazz", resolved)
    }

    @Test
    fun testDistinctSettingNotOverwritten() {
        val prevGoal = "turn on mobile data"
        val resolved = UserConversationMemory.resolveGoalWithContextText(prevGoal, "turn on wifi")
        assertEquals("turn on wifi", resolved)

        val resolved2 = UserConversationMemory.resolveGoalWithContextText(prevGoal, "turn off location")
        assertEquals("turn off location", resolved2)
    }
}
