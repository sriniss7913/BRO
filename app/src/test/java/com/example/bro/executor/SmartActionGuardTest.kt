package com.example.bro.executor

import com.example.bro.accessibility.NodeRepository
import com.example.bro.accessibility.UiCanonicalizer
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartActionGuardTest {

    @Before
    fun setUp() {
        NodeRepository.clear()
        SemanticEntityCritic.clearCache()
    }

    @Test
    fun testExtractTargetEntity() {
        assertEquals("Ramesh", SmartActionGuard.extractTargetEntity("send hi to Ramesh on WhatsApp"))
        assertEquals("Ramesh Babu", SmartActionGuard.extractTargetEntity("message Ramesh Babu on WhatsApp"))
        assertEquals("Arun", SmartActionGuard.extractTargetEntity("send message to Arun"))
        assertEquals("Mom", SmartActionGuard.extractTargetEntity("text Mom I will be late"))
        assertEquals("Soundarya", SmartActionGuard.extractTargetEntity("whatsapp Soundarya hello"))
        assertEquals("Pooja", SmartActionGuard.extractTargetEntity("chat with Pooja"))
    }

    @Test
    fun testTier1MatchExactAndCompounds() {
        // Exact match
        assertTrue(SmartActionGuard.tier1Match("Ramesh", "Ramesh"))

        // Suffix / Compound word: "Rameshbabu" contains "Ramesh"
        assertTrue(SmartActionGuard.tier1Match("Ramesh", "Rameshbabu"))

        // Prefix / Title: "Dr. Ramesh" contains "Ramesh"
        assertTrue(SmartActionGuard.tier1Match("Ramesh", "Dr. Ramesh"))

        // Token overlap: "Ramesh Kumar" contains token "Ramesh"
        assertTrue(SmartActionGuard.tier1Match("Ramesh", "Ramesh Kumar"))
        assertTrue(SmartActionGuard.tier1Match("Ramesh Kumar", "Ramesh"))

        // Minor typo Levenshtein <= 1
        assertTrue(SmartActionGuard.tier1Match("Ramesh", "Rameesh"))

        // Completely different names
        assertFalse(SmartActionGuard.tier1Match("Ramesh", "Arun"))
        assertFalse(SmartActionGuard.tier1Match("Ramesh", "Praveen"))
        assertFalse(SmartActionGuard.tier1Match("Mom", "Amma"))
    }

    @Test
    fun testRejectsWrongContactWhenTargetIsVisible() = runBlocking {
        val canonicalOutput = """
            SCREEN: com.whatsapp
            SCREEN_STATE: WHATSAPP_MAIN
            AVAILABLE_ACTIONS: SEARCH_CONTACT, OPEN_CHAT

            [0] SEARCH
                purpose="search chats, contacts, or messages"

            [1] CHAT_ROW
                contact="Arun"
                time="09:12"
                last_msg="Hey"

            [2] CHAT_ROW
                contact="Ramesh"
                time="08:45"
                last_msg="Hello"
        """.trimIndent()

        NodeRepository.put(1, null, UiCanonicalizer.Role.CHAT_ROW)
        NodeRepository.put(2, null, UiCanonicalizer.Role.CHAT_ROW)

        // Action is clicking Arun (node 1) when goal asks for Ramesh
        val action = Action(type = ActionType.CLICK, targetId = 1)
        val goal = Goal("send hi to Ramesh on WhatsApp")

        val result = ActionValidator.validate(action, goal, canonicalOutput)

        assertNotNull(result)
        assertTrue(result!!.contains("You selected contact 'Arun'"))
        assertTrue(result.contains("Click node [2] ('Ramesh') instead"))
    }

    @Test
    fun testPassesCompoundNameImmediatelyInTier1() = runBlocking {
        val canonicalOutput = """
            SCREEN: com.whatsapp
            SCREEN_STATE: WHATSAPP_MAIN
            AVAILABLE_ACTIONS: SEARCH_CONTACT, OPEN_CHAT

            [1] CHAT_ROW
                contact="Rameshbabu"
                time="08:45"
                last_msg="Hello"
        """.trimIndent()

        NodeRepository.put(1, null, UiCanonicalizer.Role.CHAT_ROW)

        // Action is clicking Rameshbabu (node 1) when goal asks for Ramesh
        val action = Action(type = ActionType.CLICK, targetId = 1)
        val goal = Goal("send hi to Ramesh on WhatsApp")

        // No semanticCritic needed — should pass instantly in Tier 1!
        val result = ActionValidator.validate(action, goal, canonicalOutput, semanticCritic = null)
        assertNull("Rameshbabu should be accepted for Ramesh without errors", result)
    }

    @Test
    fun testTier2SemanticCriticPassesNickname() = runBlocking {
        val canonicalOutput = """
            SCREEN: com.whatsapp
            SCREEN_STATE: WHATSAPP_MAIN
            AVAILABLE_ACTIONS: SEARCH_CONTACT, OPEN_CHAT

            [1] CHAT_ROW
                contact="Amma"
                time="10:00"
                last_msg="Eat food"
        """.trimIndent()

        NodeRepository.put(1, null, UiCanonicalizer.Role.CHAT_ROW)

        // Action is clicking Amma (node 1) when goal asks for Mom
        val action = Action(type = ActionType.CLICK, targetId = 1)
        val goal = Goal("send hi to Mom on WhatsApp")

        // Mock semanticCritic returning true for Amma -> Mom
        val critic: suspend (String, String) -> Boolean = { _, candidate ->
            candidate == "Amma"
        }

        val result = ActionValidator.validate(action, goal, canonicalOutput, semanticCritic = critic)
        assertNull("Amma should be accepted for Mom when semantic critic confirms", result)
    }

    @Test
    fun testTier2SemanticCriticRejectsIrrelevantContactAndSuggestsSearch() = runBlocking {
        val canonicalOutput = """
            SCREEN: com.whatsapp
            SCREEN_STATE: WHATSAPP_MAIN
            AVAILABLE_ACTIONS: SEARCH_CONTACT, OPEN_CHAT

            [0] SEARCH
                purpose="search chats, contacts, or messages"

            [1] CHAT_ROW
                contact="Swiggy Delivery"
                time="10:00"
                last_msg="Order delivered"
        """.trimIndent()

        NodeRepository.put(1, null, UiCanonicalizer.Role.CHAT_ROW)

        val action = Action(type = ActionType.CLICK, targetId = 1)
        val goal = Goal("send hi to Ramesh on WhatsApp")

        // Critic rejects Swiggy
        val critic: suspend (String, String) -> Boolean = { _, _ -> false }

        val result = ActionValidator.validate(action, goal, canonicalOutput, semanticCritic = critic)
        assertNotNull(result)
        assertTrue(result!!.contains("is not a match for 'Ramesh'"))
        assertTrue(result.contains("Click SEARCH"))
    }

    @Test
    fun testTravelGoalAndNonCommunicationAppBypassesTargetEntityGuard() = runBlocking {
        // RedBus screen with "From" selector (node 9)
        val canonicalOutput = """
            SCREEN: in.redbus.android
            SCREEN_STATE: GENERIC_APP
            AVAILABLE_ACTIONS: CLICK, BACK, HOME, SCROLL, SEARCH

            [9] CLICKABLE
                label="From"
                id="View_source"

            [10] CLICKABLE
                label="To"
                id="View_destination"

            [16] SEARCH
                label="Search buses"

            [25] CLICKABLE
                label="Show contact information"
        """.trimIndent()

        NodeRepository.put(9, null, UiCanonicalizer.Role.CLICKABLE)
        NodeRepository.put(10, null, UiCanonicalizer.Role.CLICKABLE)
        NodeRepository.put(16, null, UiCanonicalizer.Role.SEARCH)
        NodeRepository.put(25, null, UiCanonicalizer.Role.CLICKABLE)

        val action = Action(type = ActionType.CLICK, targetId = 9)
        val goal = Goal("check RedBus app for bus availability tomorrow from Chennai to Madurai")

        // Clicking "From" (node 9) must NOT be rejected as a contact mismatch
        val result = ActionValidator.validate(
            action = action,
            goal = goal,
            canonicalOutput = canonicalOutput,
            currentPackage = "in.redbus.android"
        )
        assertNull("RedBus form selector should never be treated as a contact entity", result)

        // Verify extractTargetEntity ignores "from X to Y"
        assertNull(SmartActionGuard.extractTargetEntity("check RedBus app for bus availability tomorrow from Chennai to Madurai"))
        assertNull(SmartActionGuard.extractTargetEntity("flight from Delhi to Mumbai tomorrow"))
    }
}
