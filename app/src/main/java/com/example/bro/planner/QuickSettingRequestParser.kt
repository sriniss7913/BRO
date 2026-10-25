package com.example.bro.planner

data class QuickSettingRequest(
    val tileLabel: String,
    val enabled: Boolean? // true: on, false: off, null: toggle
)

object QuickSettingRequestParser {
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
    private val togglePhrases = listOf(
        Regex("""\btoggle\b"""),
        Regex("""\bflip\b""")
    )

    fun parse(request: String): QuickSettingRequest? {
        val normalized = request.lowercase().replace(Regex("\\s+"), " ").trim()
        val isEnable = enablePhrases.any { it.containsMatchIn(normalized) }
        val isDisable = disablePhrases.any { it.containsMatchIn(normalized) }
        val isToggle = togglePhrases.any { it.containsMatchIn(normalized) }

        val desiredState: Boolean? = when {
            isEnable && !isDisable -> true
            isDisable && !isEnable -> false
            isToggle -> null
            else -> return null
        }

        val tile = when {
            Regex("""\b(flash\s*light|flashlight|torch)\b""").containsMatchIn(normalized) ->
                "Flashlight"
            Regex("""\b(blue\s*tooth|bluetooth)\b""").containsMatchIn(normalized) ->
                "Bluetooth"
            Regex("""\b(wi\s*-\s*fi|wifi|internet)\b""").containsMatchIn(normalized) ->
                "Wi-Fi"
            Regex("""\b(hot\s*spot|hotspot)\b""").containsMatchIn(normalized) ->
                "Hotspot"
            Regex("""\bmobile\s+data\b|\bcellular\s+data\b""").containsMatchIn(normalized) ->
                "Mobile data"
            Regex("""\blocation\b|\bgps\b""").containsMatchIn(normalized) ->
                "Location"
            Regex("""\b(airplane\s*mode|flight\s*mode)\b""").containsMatchIn(normalized) ->
                "Airplane mode"
            Regex("""\b(do\s*not\s*disturb|dnd)\b""").containsMatchIn(normalized) ->
                "Do Not Disturb"
            Regex("""\b(auto\s*rotate|rotation\s*lock)\b""").containsMatchIn(normalized) ->
                "Auto-rotate"
            Regex("""\b(dark\s*mode|dark\s*theme|night\s*mode)\b""").containsMatchIn(normalized) ->
                "Dark mode"
            Regex("""\bnfc\b""").containsMatchIn(normalized) ->
                "NFC"
            else -> return null
        }
        return QuickSettingRequest(tile, desiredState)
    }
}
