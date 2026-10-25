package com.example.bro.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroqPlannerJsonSanitizerTest {
    @Test
    fun removesTrailingQuotesAfterIntegerIds() {
        val malformed = """{"type":"TYPE","nodeId":1","targetId":2"}"""

        assertEquals(
            """{"type":"TYPE","nodeId":1,"targetId":2}""",
            sanitizeJsonArtifacts(malformed)
        )
    }

    @Test
    fun leavesValidJsonUnchanged() {
        val valid = """{"type":"CLICK","nodeId":12}"""

        assertEquals(valid, sanitizeJsonArtifacts(valid))
    }

    @Test
    fun openAppResponseUsesPackageNameInsteadOfNodeId() {
        val response = com.google.gson.Gson().fromJson(
            """{"type":"OPEN_APP","packageName":"com.whatsapp"}""",
            com.example.bro.ChatResponse::class.java
        )

        assertEquals("com.whatsapp", response.packageName)
        assertNull(response.nodeId)
    }

    @Test
    fun defaultPromptRequiresAndroidSupportedActionJson() {
        val prompt = SystemPromptManager.DEFAULT_SYSTEM_PROMPT

        org.junit.Assert.assertTrue(prompt.contains("never use comma-separated action names"))
        org.junit.Assert.assertTrue(prompt.contains("OPEN_APP uses packageName"))
        org.junit.Assert.assertTrue(prompt.contains("CLICK and TYPE require a current-screen nodeId"))
    }
}
