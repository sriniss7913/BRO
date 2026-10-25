package com.example.bro.accessibility



import android.view.accessibility.AccessibilityNodeInfo

object NodeRepository {

    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
    private val roles = mutableMapOf<Int, UiCanonicalizer.Role>()

    fun clear() {
        nodes.clear()
        roles.clear()
    }

    fun put(id: Int, node: AccessibilityNodeInfo?, role: UiCanonicalizer.Role? = null) {
        if (node != null) {
            nodes[id] = node
        }
        if (role != null) {
            roles[id] = role
        }
    }

    fun get(id: Int): AccessibilityNodeInfo? {
        return nodes[id]
    }
    
    fun getRole(id: Int): UiCanonicalizer.Role? {
        return roles[id]
    }

    fun firstByRole(role: UiCanonicalizer.Role): AccessibilityNodeInfo? =
        roles.entries.firstOrNull { it.value == role }?.key?.let(nodes::get)

    fun idForRole(role: UiCanonicalizer.Role): Int? =
        roles.entries.firstOrNull { it.value == role }?.key
}