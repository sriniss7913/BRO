package com.example.bro

import com.example.bro.accessibility.PromptBuilder
import com.example.bro.accessibility.ScreenReader
import com.example.bro.accessibility.WhatsAppCanonicalizer
import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val screenReader = ScreenReader()

    @Test
    fun testEmptyScreenPromptsReflectTheirRepresentations() {
        val screen = ScreenState(
            packageName = "com.whatsapp",
            nodes = emptyList()
        )
        val legacyOutput = PromptBuilder.build(screen)
        val canonicalOutput = screenReader.toPrompt(screen)
        assertTrue(legacyOutput.contains("Current App: com.whatsapp"))
        assertTrue(legacyOutput.contains("(empty screen)"))
        assertEquals("SCREEN: EMPTY", canonicalOutput)
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

        val prompt = PromptBuilder.build(rawScreen)
        assertTrue(prompt.contains("[14] Ramesh picture Ramesh Yesterday hi [CLICK]"))
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
        val canonicalElements = WhatsAppCanonicalizer.canonicalize(screen)
        assertEquals(
            listOf("BACK_BUTTON", "SEARCH_BUTTON", "MESSAGE_INPUT", "SEND_BUTTON"),
            canonicalElements.map { it.role }
        )

        val prompt = PromptBuilder.build(screen)
        assertTrue(prompt.contains("[0] Back [CLICK]"))
        assertTrue(prompt.contains("[1] Ask Meta or Search [CLICK]"))
        assertTrue(prompt.contains("[2] Type a message [EDITABLE - TYPE HERE]"))
        assertTrue(prompt.contains("[3] Send [CLICK]"))
    }
}
