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
    var signature = "Bert"   // added to the end of every reply so people know it's the mascot
    var persona = "friendly, casual, concise. In screenshots, MY messages are the colored bubbles on the RIGHT side; the other person's are on the LEFT"
    private var debounceJob: Job? = null
    private var busy = false
    private var lastHandled = ""
    private var lastCallAt = 0L
    private val minGapMs = 8_000L   // local rate limit so we never spam the API

    fun onChatChanged() {
        if (busy) return   // already thinking: never interrupt a running request
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(1500)  // wait for the screen to settle
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
                    ui.say("Oops: " + e.javaClass.simpleName + " " + (e.message ?: "").take(150))
                    ui.setState(MascotState.IDLE)
                } finally {
                    busy = false
                }
            }
        }
    }

    private suspend fun process() {
        val svc = ChatAccessibilityService.instance ?: return ui.say("Turn on the accessibility service")
        val text = svc.readVisibleText()
        // Loop guard: same screen content as last time -> ignore.
        if (text != null && text.hashCode().toString() == lastHandled) return
        val shot = capture.grab()  // screenshot so the AI can see who sent what
        if (text == null && shot == null) return ui.say("I can't read this chat")

        ui.setState(MascotState.THINKING)
        lastCallAt = System.currentTimeMillis()
        try {
            val r = llm.draftReply(text, shot, persona)
            lastHandled = text?.hashCode().toString()
            if (r == null) {
                ui.say("Gemini gave no answer"); ui.setState(MascotState.IDLE); return
            }
            val reply = r.reply?.let { it.trimEnd() + " - " + signature }
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
            ui.say(e.message ?: "LLM error"); ui.setState(MascotState.IDLE)
        }
    }

    private fun send(reply: String) {
        scope.launch {
            val ok = ChatAccessibilityService.instance?.typeAndSend(reply, send = true) == true
            ui.setState(if (ok) MascotState.HAPPY else MascotState.IDLE)
            if (!ok) ui.say("Couldn't send. Is the chat open with the message box showing?")
            delay(2500)
            ui.setState(MascotState.IDLE)
        }
    }
}
