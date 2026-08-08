package com.example.bro.model

import android.view.accessibility.AccessibilityNodeInfo

data class ScreenState(

    val packageName: String,

    val nodes: List<UiElement>,

    // Raw root node — carried through so HierarchicalFormatter can traverse the live tree
    val rawRoot: AccessibilityNodeInfo? = null

)