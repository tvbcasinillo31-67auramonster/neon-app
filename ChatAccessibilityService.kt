package com.example.neonbuddy

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads visible chat text from whitelisted apps and can type + send a reply. */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ChatAccessibilityService? = null
        /** Only these apps are ever read or typed into. Edit to taste. */
        val ALLOWED_PACKAGES = setOf("com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging", "com.facebook.orca", "com.facebook.mlite")
        var onChatChanged: ((pkg: String) -> Unit)? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ALLOWED_PACKAGES) onChatChanged?.invoke(pkg)
    }

    /** Last ~25 text snippets on screen, or null if the app exposes none (then use vision). */
    fun readVisibleText(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() !in ALLOWED_PACKAGES) return null
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.toString()?.takeIf { it.isNotBlank() && !n.isEditable }?.let(out::add)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out.takeLast(25).joinToString("\n").ifBlank { null }
    }

    /** Types into the focused input and taps Send. Returns false if either step fails. */
    fun typeAndSend(text: String, send: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in ALLOWED_PACKAGES) return false
        val input = findFirst(root) { it.isEditable } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (!send) return true
        val btn = findFirst(root) {
            val d = (it.contentDescription ?: it.text)?.toString()?.lowercase() ?: ""
            it.isClickable && d == "send"
        } ?: return false
        return btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findFirst(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) findFirst(n.getChild(i), pred)?.let { return it }
        return null
    }
}
