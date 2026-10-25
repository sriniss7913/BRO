package com.example.bro.planner

import android.content.Intent
import android.net.Uri
import android.provider.Settings

sealed class SystemTaskShortcut {
    data class SettingsPanel(val action: String) : SystemTaskShortcut()
    data class MapsRoute(val destination: String) : SystemTaskShortcut()

    fun toIntent(): Intent = when (this) {
        is SettingsPanel -> Intent(action)
        is MapsRoute -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}")
        ).setPackage("com.google.android.apps.maps")
    }

    companion object {
        private val enablePhrases = listOf(
            Regex("""\bturn\s+on\b"""),
            Regex("""\bswitch\s+on\b"""),
            Regex("""\benable\b"""),
            Regex("""\bactivate\b""")
        )
        private val disablePhrases = listOf(
            Regex("""\bturn\s+off\b"""),
            Regex("""\bswitch\s+off\b"""),
            Regex("""\bdisable\b"""),
            Regex("""\bdeactivate\b""")
        )

        fun parse(goal: String): SystemTaskShortcut? {
            val normalized = goal.lowercase().replace(Regex("\\s+"), " ").trim()
            val enabling = enablePhrases.any { it.containsMatchIn(normalized) }
            val disabling = disablePhrases.any { it.containsMatchIn(normalized) }
            if (enabling == disabling) return parseMapsRoute(goal)

            val settingAction = when {
                Regex("""\b(location|gps)\b""").containsMatchIn(normalized) ->
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS
                Regex("""\b(wi-?fi|wireless)\b""").containsMatchIn(normalized) ->
                    Settings.ACTION_WIFI_SETTINGS
                Regex("""\bbluetooth\b""").containsMatchIn(normalized) ->
                    Settings.ACTION_BLUETOOTH_SETTINGS
                Regex("""\b(mobile|cellular)\s+data\b""").containsMatchIn(normalized) ->
                    Settings.ACTION_NETWORK_OPERATOR_SETTINGS
                else -> null
            }
            return settingAction?.let(::SettingsPanel) ?: parseMapsRoute(goal)
        }

        private fun parseMapsRoute(goal: String): SystemTaskShortcut? {
            if (!Regex("""\b(directions?|route|navigate|drive|walk)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(goal)
            ) return null
            val destination = Regex(
                """\b(?:directions?|route|navigate|drive|walk)\s+(?:to\s+)?(.+?)(?:\s+(?:in|using|with)\s+(?:google\s+)?maps)?[.!?]*$""",
                RegexOption.IGNORE_CASE
            ).find(goal)?.groupValues?.get(1)
                ?.trim()
                ?.trimEnd('.', '!', '?')
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return MapsRoute(destination)
        }
    }
}
