package com.example.bro.executor

import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidatorSafetyNetTest {

    @Test
    fun detectsPermissionDialogOverlay() {
        val overlay = ActionValidator.detectObscuringSystemOverlay(
            packageName = "com.google.android.permissioncontroller",
            rootText = "Allow BRO to access photos and videos?"
        )

        assertNotNull(overlay)
        assertTrue(overlay!!.type == "PERMISSION_DIALOG")
        assertTrue(overlay.message.contains("permission request dialog"))
    }

    @Test
    fun detectsAnrCrashDialogOverlay() {
        val overlay = ActionValidator.detectObscuringSystemOverlay(
            packageName = "android",
            rootText = "WhatsApp isn't responding. Close app / Wait"
        )

        assertNotNull(overlay)
        assertTrue(overlay!!.type == "ANR_CRASH_DIALOG")
        assertTrue(overlay.message.contains("Application Not Responding"))
    }

    @Test
    fun rejectsActionWhenPermissionOverlayBlocksTargetApp() = runBlocking {
        val action = Action(type = ActionType.CLICK, targetId = 1)
        val goal = Goal("send message to Ramesh")
        val canonicalOutput = """
            [1] CLICKABLE text="Allow"
            [2] CLICKABLE text="Don't allow"
            AVAILABLE_ACTIONS: CLICK
        """.trimIndent()

        val rejection = ActionValidator.validate(
            action = action,
            goal = goal,
            canonicalOutput = canonicalOutput,
            currentPackage = "com.google.android.permissioncontroller"
        )

        assertNotNull(rejection)
        assertTrue(rejection!!.contains("Screen is covered by an Android system permission request dialog"))
    }

    @Test
    fun allowsPermissionHandlingWhenGoalIsExplicitlyAboutPermissions() = runBlocking {
        val action = Action(type = ActionType.CLICK, targetId = 1)
        val goal = Goal("grant notification permission")
        val canonicalOutput = """
            [1] CLICKABLE text="Allow"
            AVAILABLE_ACTIONS: CLICK
        """.trimIndent()

        val rejection = ActionValidator.validate(
            action = action,
            goal = goal,
            canonicalOutput = canonicalOutput,
            currentPackage = "com.google.android.permissioncontroller"
        )

        assertNull(rejection)
    }
}
