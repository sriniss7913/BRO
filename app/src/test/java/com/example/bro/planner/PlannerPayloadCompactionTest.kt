package com.example.bro.planner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerPayloadCompactionTest {
    @Test
    fun keepsScreenHeadersAndPrioritizesInteractiveNodesWithinBudget() {
        val nodes = (0..60).joinToString("\n\n") { id ->
            "[$id] CLICKABLE\n    label=\"Navigation item $id with a long label\""
        }
        val input = """
            SCREEN: example.app
            SCREEN_STATE: GENERIC_APP
            AVAILABLE_ACTIONS: CLICK, BACK
        """.trimIndent() + "\n\n" + nodes +
            "\n\n[61] SWITCH\n    label=\"Alarm\"\n    state=\"ON\"" +
            "\n\n[62] INPUT\n    label=\"Minute\""

        val compacted = compactPlannerScreen(input, maxChars = 1200)

        assertTrue(compacted.contains("AVAILABLE_ACTIONS: CLICK, BACK"))
        assertTrue(compacted.contains("[61] SWITCH"))
        assertTrue(compacted.contains("[62] INPUT"))
        assertTrue(compacted.length <= 1200)
    }

    @Test
    fun truncatesOversizedLabels() {
        val input = """
            SCREEN: example.app
            SCREEN_STATE: GENERIC_APP
            AVAILABLE_ACTIONS: CLICK

            [0] CLICKABLE
                label="${"x".repeat(500)}"
        """.trimIndent()

        val compacted = compactPlannerScreen(input)

        assertTrue(compacted.length < 300)
        assertFalse(compacted.contains("x".repeat(200)))
    }
}
