package com.example.bro

import android.content.Context
import android.content.Intent
import android.util.Log

data class AppSearchResult(
    val label: String,
    val packageName: String
)

object AppLauncher {

    private val installedApps = mutableMapOf<String, String>()

    // Common synonyms, intents, and colloquial names mapped to standard app keywords
    val COMMON_ALIASES = mapOf(
        "alarm" to listOf("clock", "alarm"),
        "alarms" to listOf("clock", "alarm"),
        "timer" to listOf("clock", "timer"),
        "stopwatch" to listOf("clock", "stopwatch"),
        "sms" to listOf("messages", "messaging", "mms"),
        "text" to listOf("messages", "messaging"),
        "texting" to listOf("messages", "messaging"),
        "chat" to listOf("whatsapp", "messages", "telegram"),
        "gallery" to listOf("photos", "gallery"),
        "pictures" to listOf("photos", "gallery"),
        "photos" to listOf("photos", "gallery"),
        "browser" to listOf("chrome", "browser", "firefox", "edge"),
        "web" to listOf("chrome", "browser"),
        "internet" to listOf("chrome", "browser"),
        "dialer" to listOf("phone", "dialer", "contacts"),
        "call" to listOf("phone", "dialer"),
        "email" to listOf("gmail", "mail", "outlook"),
        "mail" to listOf("gmail", "mail", "outlook"),
        "notes" to listOf("keep", "notes", "memo"),
        "memo" to listOf("keep", "notes", "memo"),
        "music" to listOf("spotify", "yt music", "music", "sound"),
        "songs" to listOf("spotify", "yt music", "music"),
        "maps" to listOf("maps", "navigation", "google maps"),
        "navigation" to listOf("maps", "navigation"),
        "files" to listOf("files", "file manager", "documents", "my files"),
        "documents" to listOf("files", "file manager", "documents"),
        "calculator" to listOf("calculator", "calc"),
        "calendar" to listOf("calendar", "schedule")
    )

    fun registerAppForTesting(label: String, packageName: String) {
        installedApps[label.lowercase()] = packageName
    }

    fun clearForTesting() {
        installedApps.clear()
    }
    fun loadInstalledApps(context: Context) {

        installedApps.clear()

        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = context.packageManager.queryIntentActivities(
            intent,
            android.content.pm.PackageManager.MATCH_ALL
        )

        for (app in apps) {

            val label = app.loadLabel(context.packageManager)
                .toString()

            val packageName = app.activityInfo.packageName

            installedApps[label.lowercase()] = packageName

            Log.d(
                "BRO_APPS",
                "$label -> $packageName"
            )
        }

        Log.d(
            "BRO_APPS",
            "Loaded ${installedApps.size} launchable apps"
        )
    }
    fun findPackage(appName: String): String? {
        val query = appName.trim().lowercase()
        if (query.isBlank()) return null

        // 1. Direct package match
        if (installedApps.values.any { it.equals(query, ignoreCase = true) }) {
            return installedApps.values.first { it.equals(query, ignoreCase = true) }
        }

        // 2. Exact label match
        val exactMatch = installedApps[query]
        if (exactMatch != null) return exactMatch

        // 3. Synonym / colloquial alias match (e.g. "alarm" -> "clock")
        val candidateKeywords = COMMON_ALIASES[query] ?: listOf(query)
        for (keyword in candidateKeywords) {
            val aliasExact = installedApps[keyword]
            if (aliasExact != null) return aliasExact
            val aliasContains = installedApps.entries.firstOrNull { (label, _) ->
                label.contains(keyword) || keyword.contains(label)
            }?.value
            if (aliasContains != null) return aliasContains
        }

        // 4. Substring / contains match on label
        val containsMatch = installedApps.entries.firstOrNull { (label, _) ->
            label.contains(query) || query.contains(label)
        }?.value
        if (containsMatch != null) return containsMatch

        // 5. Fuzzy match on target package name segments
        return findPackageByFuzzyTarget(query)
    }

    /**
     * On-demand tool calling lookup: searches installed apps matching a keyword, intent, or synonym.
     */
    fun searchApps(query: String): List<AppSearchResult> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return emptyList()

        val candidateKeywords = (COMMON_ALIASES[cleanQuery] ?: emptyList()) + cleanQuery
        val results = mutableListOf<AppSearchResult>()

        for ((label, pkg) in installedApps) {
            val matches = candidateKeywords.any { kw ->
                label.contains(kw) || kw.contains(label) || pkg.contains(kw)
            }
            if (matches) {
                results.add(AppSearchResult(label.replaceFirstChar { it.uppercase() }, pkg))
            }
        }

        return results.distinctBy { it.packageName }.take(6)
    }

    fun printApps() {

        installedApps.forEach {

            Log.d(
                "BRO_APPS",
                "${it.key} -> ${it.value}"
            )

        }

        }
    fun getInstalledAppNames(): List<String> {
        return installedApps.keys.sorted()
    }

    fun getLaunchableAppsWithPackages(): List<String> {
        return installedApps.entries
            .map { (label, packageName) -> "$label=$packageName" }
            .sorted()
    }

    fun getLaunchableAppsMentionedIn(text: String): List<String> {
        return installedApps.entries
            .filter { (label, packageName) ->
                val labelPattern = Regex(
                    """(?<![\p{L}\p{N}])${Regex.escape(label)}(?![\p{L}\p{N}])""",
                    RegexOption.IGNORE_CASE
                )
                labelPattern.containsMatchIn(text) ||
                    text.contains(packageName, ignoreCase = true)
            }
            .map { (label, packageName) -> "$label=$packageName" }
            .sorted()
    }

    fun findPackageByFuzzyTarget(target: String): String? {
        val ignoredSegments = setOf(
            "com", "in", "org", "net", "android", "app", "sec", "samsung",
            "google", "application"
        )
        val segments = target.lowercase()
            .split('.')
            .filter { it.isNotBlank() && it !in ignoredSegments }
        if (segments.isEmpty()) return null

        return installedApps.entries
            .map { (label, packageName) ->
                val score = segments.maxOfOrNull { segment ->
                    when {
                        label == segment -> 3
                        label.contains(segment) -> 2
                        packageName.split('.').any { it == segment } -> 1
                        else -> 0
                    }
                } ?: 0
                packageName to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}