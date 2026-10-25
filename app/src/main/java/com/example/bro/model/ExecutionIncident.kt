package com.example.bro.model

enum class IncidentKind {
    REJECTED_BY_SAFETY_NET,
    UNCHANGED_SCREEN,
    EXECUTION_FAILED,
    ASSERT_FAILED,
    OBSCURED_BY_SYSTEM_DIALOG
}

data class ExecutionIncident(
    val step: Int,
    val attemptedAction: String,
    val failureKind: IncidentKind,
    val diagnostic: String,
    val suggestedDirective: String
) {
    fun formatForPrompt(): String {
        return buildString {
            appendLine("⚠️ PREVIOUS STEP EXECUTION INCIDENT (Step $step):")
            appendLine("- Attempted Action: $attemptedAction")
            appendLine("- Incident Type: ${failureKind.name}")
            appendLine("- Diagnostic: $diagnostic")
            appendLine("- Directive: $suggestedDirective")
            appendLine("CRITICAL: DO NOT repeat the failed action without altering parameters or addressing the diagnostic.")
        }.trimEnd()
    }
}
