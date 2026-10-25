package com.example.bro.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionIncidentTest {

    @Test
    fun formatsPromptBlockWithDiagnosticsAndDirectives() {
        val incident = ExecutionIncident(
            step = 3,
            attemptedAction = "CLICK(targetId=4, text=null)",
            failureKind = IncidentKind.UNCHANGED_SCREEN,
            diagnostic = "Element did not respond to tap; screen signature remained identical.",
            suggestedDirective = "Do not re-tap node [4]. Try scrolling or choosing an alternate control."
        )

        val formatted = incident.formatForPrompt()

        assertTrue(formatted.contains("PREVIOUS STEP EXECUTION INCIDENT (Step 3)"))
        assertTrue(formatted.contains("UNCHANGED_SCREEN"))
        assertTrue(formatted.contains("Element did not respond to tap"))
        assertTrue(formatted.contains("Do not re-tap node [4]"))
        assertTrue(formatted.contains("CRITICAL: DO NOT repeat the failed action"))
    }

    @Test
    fun formatsSafetyNetRejectionIncident() {
        val incident = ExecutionIncident(
            step = 1,
            attemptedAction = "TYPE(targetId=2, text=Hello)",
            failureKind = IncidentKind.REJECTED_BY_SAFETY_NET,
            diagnostic = "ACTION REJECTED: Screen is covered by an Android system permission request dialog.",
            suggestedDirective = "Grant or deny permission first."
        )

        val formatted = incident.formatForPrompt()

        assertTrue(formatted.contains("REJECTED_BY_SAFETY_NET"))
        assertTrue(formatted.contains("permission request dialog"))
    }
}
