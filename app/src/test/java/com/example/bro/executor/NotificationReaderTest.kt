package com.example.bro.executor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReaderTest {

    @Test
    fun detectsNotificationSummaryRequests() {
        assertTrue(NotificationReader.isNotificationGoal("read notifications"))
        assertTrue(NotificationReader.isNotificationGoal("check my notifications"))
        assertTrue(NotificationReader.isNotificationGoal("summarize notifications from notification bar"))
        assertTrue(NotificationReader.isNotificationGoal("read notification, summarize it from the notification bar"))
        assertTrue(NotificationReader.isNotificationGoal("what are my notifications"))
        assertTrue(NotificationReader.isNotificationGoal("any new notifications?"))
        assertTrue(NotificationReader.isNotificationGoal("show notifications from dropdown"))
        assertTrue(NotificationReader.isNotificationGoal("check notification shade"))
    }

    @Test
    fun rejectsUnrelatedRequests() {
        assertFalse(NotificationReader.isNotificationGoal("send a message to Ramesh on WhatsApp"))
        assertFalse(NotificationReader.isNotificationGoal("turn on flashlight"))
        assertFalse(NotificationReader.isNotificationGoal("open camera"))
        assertFalse(NotificationReader.isNotificationGoal("set alarm for 7am"))
    }

    @Test
    fun handlesDirectSettingsExecutorSupportedSettings() {
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Location"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Wi-Fi"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Mobile data"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Bluetooth"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Hotspot"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Airplane mode"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Do Not Disturb"))
        assertTrue(DirectSettingsExecutor.canHandleDirectIntent("Dark mode"))
        assertFalse(DirectSettingsExecutor.canHandleDirectIntent("Flashlight"))
        assertFalse(DirectSettingsExecutor.canHandleDirectIntent("NFC"))
    }
}
