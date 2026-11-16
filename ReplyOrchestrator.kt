package com.example.neonbuddy

import kotlinx.coroutines.*

enum class Mode { MANUAL_APPROVAL, FULL_AUTO }

/** Debounces chat changes, asks the LLM, then shows a draft or sends it. */
class ReplyOrchestrator(
    private val scope: CoroutineScope,
    private val llm: LlmClient,
    private val capture: ScreenCaptureManager,
    private val ui: Ui,
) {
    interface Ui {
        fun setState(s: MascotState)
        fun say(msg: String)
        fun showDraft(text: String, onApprove: () -> Unit, onReject: () -> Unit)
        fun toast(msg: String)
    }

    var mode = Mode.MANUAL_APPROVAL
    var persona = "friendly, casual, concise. In screenshots, MY messages are the colored bubbles on the RIGHT side; the other person's are on the LEFT"
    private var job: Job? = null
    private var lastHandled = ""
    private var lastCallAt = 0L
    private val minGapMs = 8_000L   // local rate limit so we never spam the API

    fun onChatChanged() {
        job?.cancel()
        job = scope.launch {
            delay(1500)  // debounce: wait for the screen to settle
            if (System.currentTimeMillis() - lastCallAt < minGapMs) return@launch
            process()
        }
    }

    private suspend fun process() {
        val svc = ChatAccessibilityService.instance ?: return ui.toast("Enable the accessibility service")
        val text = svc.readVisibleText()
        // Loop guard: same screen content as last time -> ignore.
        if (text != null && text.hashCode().toString() == lastHandled) return
        val shot = capture.grab()  // screenshot so the AI can see who sent what
        if (text == null && shot == null) return ui.toast("Couldn't read the chat")

        ui.setState(MascotState.THINKING)
        lastCallAt = System.currentTimeMillis()
        try {
            val r = llm.draftReply(text, shot, persona)
            lastHandled = text?.hashCode().toString()
            if (r == null) {
                ui.toast("Gemini gave no answer"); ui.setState(MascotState.IDLE); return
            }
            val reply = r.reply
            if (reply == null) {
                ui.say(r.sees.ifBlank { "Nothing to reply to right now." })
                ui.setState(MascotState.IDLE); return
            }
            ui.setState(MascotState.TALKING)
            val shown = if (r.sees.isBlank()) reply else r.sees + "\n\n> " + reply
            when (mode) {
                Mode.FULL_AUTO -> { ui.say(shown); send(reply) }
                Mode.MANUAL_APPROVAL -> ui.showDraft(shown,
                    onApprove = { send(reply) },
                    onReject = { ui.setState(MascotState.IDLE) })
            }
        } catch (e: LlmException) {
            ui.toast(e.message ?: "LLM error"); ui.setState(MascotState.IDLE)
        }
    }

    private fun send(reply: String) {
        val ok = ChatAccessibilityService.instance?.typeAndSend(reply, send = true) == true
        ui.setState(if (ok) MascotState.HAPPY else MascotState.IDLE)
        if (!ok) ui.toast("Couldn't type/send. Is the chat open?")
        scope.launch { delay(2500); ui.setState(MascotState.IDLE) }
    }
}
