package com.example.neonbuddy

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Bert's voice: the phone's built-in text-to-speech. */
class Voice(ctx: Context) {
    private var ready = false
    private lateinit var tts: TextToSpeech
    var enabled = true
    var pitch = 1.1f
    var rate = 1.0f

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale.getDefault())
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /** Returns false when it can't speak (muted or no voice installed). */
    fun speak(text: String): Boolean {
        if (!enabled || !ready) return false
        tts.setPitch(pitch); tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bert")
        return true
    }

    fun shutdown() { if (::tts.isInitialized) { tts.stop(); tts.shutdown() } }
}
