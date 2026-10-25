package com.example.bro.planner

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTaskShortcutTest {
    @Test
    fun routesLocationToggleToLocationSettings() {
        val shortcut = SystemTaskShortcut.parse("turn on Location")

        assertTrue(shortcut is SystemTaskShortcut.SettingsPanel)
        assertEquals(
            Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            (shortcut as SystemTaskShortcut.SettingsPanel).action
        )
    }

    @Test
    fun extractsMapsDestinationFromDirectionsRequest() {
        assertEquals(
            SystemTaskShortcut.MapsRoute("Central Park"),
            SystemTaskShortcut.parse("give directions to Central Park in Maps")
        )
    }
}
