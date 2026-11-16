package com.example.neonbuddy

import kotlinx.coroutines.*

enum class Mode { MANUAL_APPROVAL, FULL_AUTO }

/** Debounces chat changes, asks the AI, then speaks, shows a draft, or sends. */
class ReplyOrchestrator(
    private val scope: CoroutineScope,
    private val llm: LlmClient,
    private val capture: ScreenCaptureManager,
    private val ui: Ui,
) {
    interface Ui {
        fun setState(s: MascotState)
        fun say(msg: String)      // spoken aloud
        fun error(msg: String)    // shown as text
        fun showDraft(text: String, onApprove: () -> Unit, onReject: () -> Unit)
    }

    var mode = Mode.MANUAL_APPROVAL
    var persona = Persona.NORMAL
    val signature = "Bert"
    val isBusy: Boolean get() = busy
    private var debounceJob: Job? = null
    private var busy = false
    private var lastHandled = ""
    private var lastSent = ""
    private var lastCallAt = 0L
    private val minGapMs = 8_000L

    fun onChatChanged() {
        if (busy) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(1500)
            val wait = minGapMs - (System.currentTimeMillis() - lastCallAt)
            if (wait > 0) delay(wait)
            if (busy) return@launch
            busy = true
            scope.launch {
                try {
                    process()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui.error("Oops: " + e.javaClass.simpleName + " " + (e.message ?: "").take(150))
                    ui.setState(MascotState.IDLE)
                } finally {
                    busy = false
                }
            }
        }
    }

    private suspend fun process() {
        val svc = ChatAccessibilityService.instance ?: return ui.error("Turn on the accessibility service")
        val snap = svc.readChat()
        if (snap != null) {
            // Never answer my own messages, or Bert's own replies.
            if (snap.lastSender == Sender.ME) return
            if (snap.lastLine.contains("- $signature")) return
            if (lastSent.isNotEmpty() && snap.text.trimEnd().endsWith(lastSent)) return
            if (snap.text.hashCode().toString() == lastHandled) return
        }
        val shot = capture.grab()
        if (snap == null && shot == null) return ui.error("I can't read this chat")

        ui.setState(MascotState.THINKING)
        lastCallAt = System.currentTimeMillis()
        try {
            val r = llm.draftReply(snap?.text, shot, persona.forReply())
            lastHandled = snap?.text?.hashCode().toString()
            if (r == null) { ui.error("Gemini gave no answer"); ui.setState(MascotState.IDLE); return }
            val reply = r.reply?.let { it.trimEnd() + " - " + signature }
            if (reply == null) { ui.setState(MascotState.IDLE); return }
            if (r.sees.isNotBlank()) ui.say(r.sees)
            ui.setState(MascotState.TALKING)
            when (mode) {
                Mode.FULL_AUTO -> send(reply)
                Mode.MANUAL_APPROVAL -> ui.showDraft(reply,
                    onApprove = { send(reply) },
                    onReject = { ui.setState(MascotState.IDLE) })
            }
        } catch (e: LlmException) {
            ui.error(e.message ?: "LLM error"); ui.setState(MascotState.IDLE)
        }
    }

    private fun send(reply: String) {
        scope.launch {
            lastSent = reply
            val ok = ChatAccessibilityService.instance?.typeAndSend(reply, send = true) == true
            ui.setState(if (ok) MascotState.HAPPY else MascotState.IDLE)
            if (!ok) ui.error("Couldn't send. Is the message box showing?")
            delay(2500)
            ui.setState(MascotState.IDLE)
        }
    }
}
