package com.example.bro

import com.example.bro.planner.TaskIntentClassifier
import com.example.bro.planner.RequestRoute
import com.example.bro.planner.UserRequestDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIntentClassifierTest {
    @Test
    fun parsesModelChatRoute() {
        assertEquals(
            RequestRoute.CHAT,
            TaskIntentClassifier.parseRequestRoute("""{"intent":"CHAT"}""")
        )
        assertEquals(
            RequestRoute.CHAT,
            TaskIntentClassifier.parseRequestRoute("```json\n{\"intent\":\"chat\"}\n```")
        )
    }

    @Test
    fun parsesModelActionRoute() {
        assertEquals(
            RequestRoute.ACTION,
            TaskIntentClassifier.parseRequestRoute("""{"intent":"ACTION"}""")
        )
    }

    @Test
    fun rejectsMissingOrUnknownModelIntent() {
        assertNull(TaskIntentClassifier.parseRequestRoute("I think this is probably chat."))
        assertNull(TaskIntentClassifier.parseRequestRoute("""{"intent":"MAYBE"}"""))
    }

    @Test
    fun parsesToolCallActionDecision() {
        val json = """{"tool":"execute_phone_task","task":"set an alarm at 5:00 AM tomorrow","reply":"Sure, setting an alarm."}"""
        val decision = TaskIntentClassifier.parseUserRequestDecision(json)
        assertTrue(decision is UserRequestDecision.Action)
        val action = decision as UserRequestDecision.Action
        assertEquals("set an alarm at 5:00 AM tomorrow", action.taskGoal)
        assertEquals("Sure, setting an alarm.", action.conversationalReply)
    }

    @Test
    fun parsesMarkdownToolCallActionDecision() {
        val markdown = """```json
{"tool":"execute_phone_task","task":"turn off the 5:00 AM alarm","reply":"Turning off the alarm."}
```"""
        val decision = TaskIntentClassifier.parseUserRequestDecision(markdown)
        assertTrue(decision is UserRequestDecision.Action)
        val action = decision as UserRequestDecision.Action
        assertEquals("turn off the 5:00 AM alarm", action.taskGoal)
        assertEquals("Turning off the alarm.", action.conversationalReply)
    }

    @Test
    fun parsesToolCallWebSearchDecision() {
        val json = """{"tool":"web_search","query":"public holiday tomorrow in India"}"""
        val decision = TaskIntentClassifier.parseUserRequestDecision(json)
        assertTrue(decision is UserRequestDecision.WebSearch)
        val search = decision as UserRequestDecision.WebSearch
        assertEquals("public holiday tomorrow in India", search.query)
    }

    @Test
    fun parsesMarkdownToolCallWebSearchDecision() {
        val markdown = """```json
{"tool":"web_search","query":"samsung s24 price amazon","reply":"Searching for Samsung S24 price."}
```"""
        val decision = TaskIntentClassifier.parseUserRequestDecision(markdown)
        assertTrue(decision is UserRequestDecision.WebSearch)
        val search = decision as UserRequestDecision.WebSearch
        assertEquals("samsung s24 price amazon", search.query)
        assertEquals("Searching for Samsung S24 price.", search.conversationalReply)
    }

    @Test
    fun parsesPlainTextChatDecision() {
        val text = "Good morning! How can I help you today?"
        val decision = TaskIntentClassifier.parseUserRequestDecision(text)
        assertTrue(decision is UserRequestDecision.Chat)
        val chat = decision as UserRequestDecision.Chat
        assertEquals("Good morning! How can I help you today?", chat.reply)
    }

    @Test
    fun parsesInstructionalExplanationAsChat() {
        val explanation = "To set an alarm manually on Android, open the Clock app, tap '+', pick your time, and tap Save."
        val decision = TaskIntentClassifier.parseUserRequestDecision(explanation)
        assertTrue(decision is UserRequestDecision.Chat)
        val chat = decision as UserRequestDecision.Chat
        assertEquals(explanation, chat.reply)
    }

    @Test
    fun detectsQueriesRequiringWebSearch() {
        assertTrue(TaskIntentClassifier.requiresWebSearch("What's the weather today in Chennai?"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("Who won the cricket match yesterday?"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("Latest news about space mission"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("Current price of Bitcoin"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("What is the temperature right now?"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("search of amazon price"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("search of amazon price, do websearch"))
        assertTrue(TaskIntentClassifier.requiresWebSearch("do web search for best laptop under 50000"))

        // Explicit web search helper
        assertTrue(TaskIntentClassifier.isExplicitWebSearch("do websearch"))
        assertTrue(TaskIntentClassifier.isExplicitWebSearch("search the web for pixel 9 pro"))
        assertTrue(TaskIntentClassifier.isExplicitWebSearch("search of amazon price"))

        // Pure conversational or app automation requests should not trigger web search
        org.junit.Assert.assertFalse(TaskIntentClassifier.requiresWebSearch("Good morning!"))
        org.junit.Assert.assertFalse(TaskIntentClassifier.requiresWebSearch("Set an alarm for 5 AM tomorrow"))
        org.junit.Assert.assertFalse(TaskIntentClassifier.requiresWebSearch("Send a message to Ramesh saying hello"))
        org.junit.Assert.assertFalse(TaskIntentClassifier.isExplicitWebSearch("open amazon app"))
    }
}
