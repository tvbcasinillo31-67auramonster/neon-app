package com.example.neonbuddy

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

enum class Sender { ME, THEM }

/** The chat on screen: labeled text, plus who sent the newest message. */
class ChatSnapshot(val text: String, val lastSender: Sender?, val lastLine: String)

class ChatAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ChatAccessibilityService? = null
        val ALLOWED_PACKAGES = setOf("com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging", "com.facebook.orca", "com.facebook.mlite")
        private val LAUNCHERS = setOf("com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3")
        private val IGNORED = setOf("com.example.neonbuddy", "com.android.systemui", "android")
        var onChatChanged: ((pkg: String) -> Unit)? = null
        var onAppChanged: ((pkg: String) -> Unit)? = null
        var currentApp: String = ""
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo?.let { info ->
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            serviceInfo = info
        }
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            when {
                pkg in LAUNCHERS -> currentApp = ""
                pkg in IGNORED || isKeyboard(pkg) -> {}
                pkg != currentApp -> { currentApp = pkg; onAppChanged?.invoke(pkg) }
            }
        }
        if (pkg in ALLOWED_PACKAGES) onChatChanged?.invoke(pkg)
    }

    private fun isKeyboard(pkg: String) =
        pkg.contains("inputmethod", true) || pkg.contains("keyboard", true) || pkg.contains("honeyboard", true)

    private class Item(val top: Int, val who: Sender?, val text: String)

    /** Reads the chat. Right-aligned bubbles are mine (ME), left-aligned are from others (THEM). */
    fun readChat(): ChatSnapshot? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() !in ALLOWED_PACKAGES) return null
        val dm = resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        val items = mutableListOf<Item>()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString()
            if (!t.isNullOrBlank() && !n.isEditable) {
                n.getBoundsInScreen(r)
                if (r.top > sh * 0.11 && r.bottom < sh * 0.93) {   // skip header and input bar
                    val diff = r.left - (sw - r.right)
                    val who = when { diff > 40 -> Sender.ME; diff < -40 -> Sender.THEM; else -> null }
                    items.add(Item(r.top, who, t))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        if (items.isEmpty()) return null
        items.sortBy { it.top }
        val last = items.lastOrNull { it.who != null }
        val text = items.takeLast(25).joinToString("\n") {
            (when (it.who) { Sender.ME -> "ME: "; Sender.THEM -> "THEM: "; null -> "" }) + it.text
        }
        return ChatSnapshot(text, last?.who, last?.text ?: "")
    }

    /** Types into the chat box, waits for the Send button to appear, then taps it. */
    suspend fun typeAndSend(text: String, send: Boolean): Boolean {
        var root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in ALLOWED_PACKAGES) return false
        val input = findFirst(root) { it.isEditable } ?: return false
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (!send) return true
        repeat(8) {
            delay(350)
            root = rootInActiveWindow ?: return@repeat
            val btn = findFirst(root) { isSendButton(it) }
            if (btn != null && clickNodeOrParent(btn)) return true
        }
        return false
    }

    private fun isSendButton(n: AccessibilityNodeInfo): Boolean {
        val d = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase() ?: return false
        return d == "send" || d == "send message"
    }

    private fun clickNodeOrParent(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        return false
    }

    private fun findFirst(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) findFirst(n.getChild(i), pred)?.let { return it }
        return null
    }
}
