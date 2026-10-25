package com.example.bro.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalReframerTest {

    @Test
    fun reframesSimpleGoalWithInstruction() {
        val base = "Send hi to ramesh on whatsapp"
        val instruction = "you are searching in status tab search in chat tab"
        val result = GoalReframer.reframe(base, instruction)
        assertEquals(
            "Send hi to ramesh on whatsapp, user hint: you are searching in status tab search in chat tab",
            result
        )
    }

    @Test
    fun cleansPreviousNestedInstructionsBeforeReframing() {
        val base = "Send hi to ramesh on whatsapp (User instruction: search in status tab)"
        val instruction = "search in chat tab not in status tab"
        val result = GoalReframer.reframe(base, instruction)
        assertEquals(
            "Send hi to ramesh on whatsapp, user hint: search in chat tab not in status tab",
            result
        )
    }

    @Test
    fun replacesTrivialGreetingBaseWithInstruction() {
        val base = "hi"
        val instruction = "send hi to Ramesh on WhatsApp"
        val result = GoalReframer.reframe(base, instruction)
        assertEquals("send hi to Ramesh on WhatsApp", result)
    }

    @Test
    fun cleansCommaUserHintBeforeReframing() {
        val base = "check order in zomato, user hint: click orders tab"
        val instruction = "refresh the page"
        val result = GoalReframer.reframe(base, instruction)
        assertEquals(
            "check order in zomato, user hint: refresh the page",
            result
        )
    }
}
