package com.example.bro

import android.content.Context
import android.content.Intent
import android.util.Log

object AppLauncher {

    private val installedApps = mutableMapOf<String, String>()



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

        val query = appName.lowercase().trim()

        for ((label, packageName) in installedApps) {

            if (label.lowercase().contains(query)) {
                return packageName
            }
        }

        return null
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
}