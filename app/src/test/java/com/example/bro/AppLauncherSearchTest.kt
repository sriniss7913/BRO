package com.example.bro

import com.example.bro.model.ActionType
import com.example.bro.planner.GroqPlanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLauncherSearchTest {

    @Before
    fun setup() {
        AppLauncher.clearForTesting()
        AppLauncher.registerAppForTesting("WhatsApp", "com.whatsapp")
        AppLauncher.registerAppForTesting("Clock", "com.google.android.deskclock")
        AppLauncher.registerAppForTesting("Settings", "com.android.settings")
        AppLauncher.registerAppForTesting("Messages", "com.google.android.apps.messaging")
        AppLauncher.registerAppForTesting("Spotify", "com.spotify.music")
        AppLauncher.registerAppForTesting("Voice Recorder", "com.google.android.apps.recorder")
    }

    @After
    fun tearDown() {
        AppLauncher.clearForTesting()
    }

    @Test
    fun testFindPackageByExactLabel() {
        assertEquals("com.whatsapp", AppLauncher.findPackage("WhatsApp"))
        assertEquals("com.whatsapp", AppLauncher.findPackage("whatsapp"))
        assertEquals("com.android.settings", AppLauncher.findPackage("Settings"))
    }

    @Test
    fun testFindPackageByDirectPackage() {
        assertEquals("com.whatsapp", AppLauncher.findPackage("com.whatsapp"))
        assertEquals("com.google.android.deskclock", AppLauncher.findPackage("com.google.android.deskclock"))
    }

    @Test
    fun testFindPackageByAlias() {
        // "alarm" -> resolves to Clock app
        assertEquals("com.google.android.deskclock", AppLauncher.findPackage("alarm"))
        assertEquals("com.google.android.deskclock", AppLauncher.findPackage("timer"))

        // "sms" / "text" -> resolves to Messages
        assertEquals("com.google.android.apps.messaging", AppLauncher.findPackage("sms"))
        assertEquals("com.google.android.apps.messaging", AppLauncher.findPackage("text"))

        // "music" / "songs" -> resolves to Spotify
        assertEquals("com.spotify.music", AppLauncher.findPackage("music"))
        assertEquals("com.spotify.music", AppLauncher.findPackage("songs"))
    }

    @Test
    fun testSearchAppsKeyword() {
        val recorderResults = AppLauncher.searchApps("recorder")
        assertTrue(recorderResults.isNotEmpty())
        assertEquals("com.google.android.apps.recorder", recorderResults.first().packageName)

        val musicResults = AppLauncher.searchApps("music")
        assertTrue(musicResults.any { it.packageName == "com.spotify.music" })

        val alarmResults = AppLauncher.searchApps("alarm")
        assertTrue(alarmResults.any { it.packageName == "com.google.android.deskclock" })
    }

    @Test
    fun testGroqPlannerOpenAppWithNaturalAppName() {
        val planner = GroqPlanner()
        val json = """{"type": "OPEN_APP", "app": "WhatsApp", "reply": "Opening WhatsApp"}"""
        val chatResponse = com.google.gson.Gson().fromJson(json, ChatResponse::class.java)
        val action = planner.mapResponseToAction(chatResponse, "open WhatsApp")

        assertEquals(ActionType.OPEN_APP, action.type)
        assertEquals("WhatsApp", action.packageName)
        assertEquals("WhatsApp", action.app)
    }

    @Test
    fun testGroqPlannerFindApp() {
        val planner = GroqPlanner()
        val json = """{"type": "FIND_APP", "query": "voice recorder", "reply": "Searching for voice recorder"}"""
        val chatResponse = com.google.gson.Gson().fromJson(json, ChatResponse::class.java)
        val action = planner.mapResponseToAction(chatResponse, "record audio")

        assertEquals(ActionType.FIND_APP, action.type)
        assertEquals("voice recorder", action.text)
    }
}
