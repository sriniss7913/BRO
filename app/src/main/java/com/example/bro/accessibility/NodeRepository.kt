package com.example.bro.accessibility



import android.view.accessibility.AccessibilityNodeInfo

object NodeRepository {

    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()

    fun clear() {
        nodes.clear()
    }

    fun put(id: Int, node: AccessibilityNodeInfo) {
        nodes[id] = node
    }

    fun get(id: Int): AccessibilityNodeInfo? {
        return nodes[id]
    }
}