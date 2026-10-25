package com.example.bro

import com.example.bro.model.ActionType
import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement
import com.example.bro.planner.GroqPlanner
import com.example.bro.planner.ScreenChoiceExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenChoiceExtractorTest {

    private fun createElement(
        id: Int,
        text: String?,
        clickable: Boolean = false,
        checkable: Boolean = false
    ) = UiElement(
        id = id,
        text = text,
        contentDescription = null,
        className = if (clickable) "android.widget.Button" else "android.widget.TextView",
        clickable = clickable,
        enabled = true,
        editable = false,
        checkable = checkable,
        checked = false
    )

    @Test
    fun testExtractLocationChoicesFromScreen() {
        // Simulated Zomato location modal screen
        val nodes = listOf(
            createElement(id = 1, text = "Select delivery location", clickable = false),
            createElement(id = 2, text = "Use current location", clickable = true),
            createElement(id = 3, text = "Home\nFlat 302, Green Glen Layout, Bellandur", clickable = true),
            createElement(id = 4, text = "Work\nPrestige Tech Park, Marathahalli", clickable = true),
            createElement(id = 5, text = "Search for area, apartment name...", clickable = true)
        )
        val screen = ScreenState(packageName = "com.application.zomato", nodes = nodes)

        val choices = ScreenChoiceExtractor.extractChoices(emptyList(), screen)

        assertTrue(choices.isNotEmpty())
        assertTrue("Choices should contain Home", choices.any { it.startsWith("Home", ignoreCase = true) })
        assertTrue("Choices should contain Work", choices.any { it.startsWith("Work", ignoreCase = true) })
        assertTrue("Choices should contain Current Location", choices.any { it.contains("current location", ignoreCase = true) })
        assertTrue("Choices should include Cancel task as exit", choices.contains("Cancel task"))
    }

    @Test
    fun testExtractChoicesWithModelSuggestedOptions() {
        val suggested = listOf("Home", "Office")
        val screen = ScreenState(packageName = "com.application.zomato", nodes = emptyList())

        val choices = ScreenChoiceExtractor.extractChoices(suggested, screen)

        assertTrue(choices.contains("Home"))
        assertTrue(choices.contains("Office"))
        assertTrue(choices.contains("Cancel task"))
    }

    @Test
    fun testGroqPlannerMapsAskUserAction() {
        val planner = GroqPlanner()
        val json = """
        {
            "type": "ASK_USER",
            "question": "Which location should I select?",
            "options": ["Home", "Work", "Current location"],
            "subgoal": "select delivery location",
            "remainingPlan": "Search Biryani and proceed to checkout"
        }
        """.trimIndent()

        val chatResponse = com.google.gson.Gson().fromJson(json, ChatResponse::class.java)
        val action = planner.mapResponseToAction(chatResponse, "order biryani on zomato")

        assertEquals(ActionType.ASK_USER, action.type)
        assertEquals("Which location should I select?", action.question)
        assertNotNull(action.options)
        assertEquals(3, action.options?.size)
        assertEquals("select delivery location", action.subgoal)
        assertEquals("Search Biryani and proceed to checkout", action.remainingPlan)
    }

    @Test
    fun testGroqPlannerAutoPromotesChatWithOptionsToAskUser() {
        val planner = GroqPlanner()
        val json = """
        {
            "type": "CHAT",
            "reply": "Please choose a delivery address:",
            "options": ["Home", "Work"]
        }
        """.trimIndent()

        val chatResponse = com.google.gson.Gson().fromJson(json, ChatResponse::class.java)
        val action = planner.mapResponseToAction(chatResponse, "order biryani on zomato")

        assertEquals(ActionType.ASK_USER, action.type)
        assertEquals("Please choose a delivery address:", action.question)
        assertEquals(listOf("Home", "Work"), action.options)
    }
}
