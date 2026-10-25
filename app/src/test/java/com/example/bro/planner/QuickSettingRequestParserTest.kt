package com.example.bro.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickSettingRequestParserTest {
    @Test
    fun parsesLocationEnableRequest() {
        assertEquals(
            QuickSettingRequest("Location", true),
            QuickSettingRequestParser.parse("ok turn on location in phone settings")
        )
    }

    @Test
    fun parsesMobileDataDisableRequest() {
        assertEquals(
            QuickSettingRequest("Mobile data", false),
            QuickSettingRequestParser.parse("disable mobile data")
        )
    }

    @Test
    fun parsesFlashlightEnableAndDisableRequests() {
        assertEquals(
            QuickSettingRequest("Flashlight", true),
            QuickSettingRequestParser.parse("turn on the torch")
        )
        assertEquals(
            QuickSettingRequest("Flashlight", false),
            QuickSettingRequestParser.parse("switch off flashlight")
        )
    }

    @Test
    fun parsesBluetoothAndWifiRequests() {
        assertEquals(
            QuickSettingRequest("Bluetooth", true),
            QuickSettingRequestParser.parse("turn on bluetooth")
        )
        assertEquals(
            QuickSettingRequest("Wi-Fi", true),
            QuickSettingRequestParser.parse("turn on Wi-Fi")
        )
        assertEquals(
            QuickSettingRequest("Bluetooth", null),
            QuickSettingRequestParser.parse("toggle bluetooth")
        )
    }

    @Test
    fun ignoresAmbiguousOrUnrelatedRequests() {
        assertNull(QuickSettingRequestParser.parse("check whether location is on"))
        assertNull(QuickSettingRequestParser.parse("turn on location and turn off GPS"))
    }
}
