package com.example.bro.agent

/**
 * Utility to reframe tasks with user feedback and clarifications,
 * preventing recursive nesting and keeping the goal focused.
 */
object GoalReframer {

    /**
     * Cleans a goal string by stripping out any previous instruction / hint annotations.
     */
    fun cleanBaseGoal(goal: String): String {
        var cleaned = goal.trim()
        // Strip previous "(User instruction: ...)" or "(User hint: ...)"
        cleaned = cleaned.replace(
            Regex("""\s*\((?:User instruction|user hint|User hint):\s*([^)]*)\)""", RegexOption.IGNORE_CASE),
            ""
        )
        // Strip previous ", user hint: ..." or ", user instruction: ..."
        cleaned = cleaned.replace(
            Regex("""\s*,\s*(?:user hint|user instruction):\s*.*$""", RegexOption.IGNORE_CASE),
            ""
        )
        return cleaned.trim()
    }

    /**
     * Reframes a base goal with a new custom user instruction / clarification.
     * Produces clean reframed goals like:
     * "Send hi to ramesh on whatsapp, user hint: you are searching in status tab search in chat tab"
     */
    fun reframe(baseGoal: String, userInstruction: String): String {
        val instruction = userInstruction.trim()
        if (instruction.isBlank()) return baseGoal

        val base = cleanBaseGoal(baseGoal)
        val effectiveBase = if (base.isBlank() ||
            base.equals("hi", ignoreCase = true) ||
            base.equals("hello", ignoreCase = true) ||
            base.equals("hey", ignoreCase = true)
        ) {
            instruction
        } else {
            base
        }

        if (effectiveBase.equals(instruction, ignoreCase = true)) {
            return effectiveBase
        }

        return "$effectiveBase, user hint: $instruction"
    }
}
