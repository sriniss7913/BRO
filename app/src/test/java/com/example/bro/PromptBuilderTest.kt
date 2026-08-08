package com.example.bro

import com.example.bro.accessibility.PromptBuilder
import com.example.bro.accessibility.ScreenReader
import com.example.bro.accessibility.UiCanonicalizer
import com.example.bro.accessibility.WhatsAppCanonicalizer
import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val screenReader = ScreenReader()

    @Test
    fun testEmptyScreenPromptEquivalence() {
        val screen = ScreenState(
            packageName = "com.whatsapp",
            nodes = emptyList()
        )
        val legacyOutput = PromptBuilder.build(screen)
        val canonicalOutput = screenReader.toPrompt(screen)
        assertEquals(legacyOutput, canonicalOutput)
    }

    @Test
    fun testWhatsAppChatRowCanonicalization() {
        val rawNodes = listOf(
            UiElement(id = 14, text = "Ramesh picture Ramesh Yesterday hi", contentDescription = null, className = "android.widget.RelativeLayout", clickable = true, enabled = true, editable = false, checkable = false, checked = false),
            UiElement(id = 15, text = "Ramesh picture", contentDescription = null, className = "android.widget.ImageView", clickable = true, enabled = true, editable = false, checkable = false, checked = false),
            UiElement(id = 16, text = "Ramesh picture", contentDescription = null, className = "android.widget.ImageView", clickable = true, enabled = true, editable = false, checkable = false, checked = false),
            UiElement(id = 17, text = "Ramesh", contentDescription = null, className = "android.widget.TextView", clickable = false, enabled = true, editable = false, checkable = false, checked = false)
        )
        val rawScreen = ScreenState(packageName = "com.whatsapp", nodes = rawNodes)

        val canonicalElements = WhatsAppCanonicalizer.canonicalize(rawScreen)
        assertEquals(1, canonicalElements.size)
        assertEquals(14, canonicalElements[0].targetId)
        assertEquals("CHAT_ROW", canonicalElements[0].role)
        assertEquals("Ramesh", canonicalElements[0].contact)

        val prompt = PromptBuilder.build(UiCanonicalizer.canonicalize(rawScreen))
        assertTrue(prompt.contains("[14] CHAT_ROW: Ramesh"))
    }

    @Test
    fun testWhatsAppControlsCanonicalization() {
        val rawNodes = listOf(
            UiElement(id = 0, text = "Back", contentDescription = null, className = "android.widget.ImageButton", clickable = true, enabled = true, editable = false, checkable = false, checked = false),
            UiElement(id = 1, text = "Ask Meta or Search", contentDescription = null, className = "android.widget.TextView", clickable = true, enabled = true, editable = false, checkable = false, checked = false),
            UiElement(id = 2, text = "Type a message", contentDescription = null, className = "android.widget.EditText", clickable = true, enabled = true, editable = true, checkable = false, checked = false),
            UiElement(id = 3, text = "Send", contentDescription = null, className = "android.widget.ImageButton", clickable = true, enabled = true, editable = false, checkable = false, checked = false)
        )
        val screen = ScreenState(packageName = "com.whatsapp", nodes = rawNodes)
        val canonicalScreen = UiCanonicalizer.canonicalize(screen)
        val prompt = PromptBuilder.build(canonicalScreen)

        assertTrue(prompt.contains("[0] BACK_BUTTON"))
        assertTrue(prompt.contains("[1] SEARCH_BUTTON"))
        assertTrue(prompt.contains("[2] MESSAGE_INPUT"))
        assertTrue(prompt.contains("[3] SEND_BUTTON"))
    }
}
