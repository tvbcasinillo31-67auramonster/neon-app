package com.example.neonbuddy

import android.content.Context
import android.widget.TextView

enum class Persona(val style: String, val pitch: Float, val rate: Float) {
    NORMAL("friendly, upbeat, casual and a little playful", 1.15f, 1.0f),
    NO_EMOTION("completely flat and deadpan with zero emotion, extremely short, like a robot stating facts", 0.7f, 0.9f),
    TSUNDERE("tsundere: acts annoyed and prideful and pretends not to care, but is secretly helpful and caring", 1.35f, 1.1f);

    fun forReply() = "Your name is Bert (not Glitch). Personality: $style. " +
        "If someone asks a question in the chat, answer it properly. " +
        "Lines starting with ME: are the owner's messages and THEM: are from other people. " +
        "In screenshots the owner's messages are the colored bubbles on the RIGHT side."

    fun appOpen(app: String, again: Boolean, late: Boolean): String {
        val lines: List<String> = when (this) {
            NORMAL -> when {
                late -> listOf("It's really late. Sleep soon, okay?", "$app this late? Okay, but then sleep!")
                again -> listOf("Oh, opening $app again?", "$app again? Okay okay.", "Back to $app. I see you.")
                else -> listOf("Opening $app! Have fun!", "$app time!", "Ooh, $app.")
            }
            NO_EMOTION -> when {
                late -> listOf("It is late. You should sleep.", "$app opened. It is past midnight.")
                again -> listOf("$app opened again. Noted.", "Opening $app. Again.")
                else -> listOf("$app opened.", "Launching $app.")
            }
            TSUNDERE -> when {
                late -> listOf("Hmph. It's this late and you're on $app? Go to sleep, baka!", "Not that I care, but sleep already!")
                again -> listOf("$app again?! Don't get addicted, baka.", "Hmph. $app again. It's not like I'm worried.")
                else -> listOf("Hmph. $app? Fine, whatever.", "I-it's not like I wanted to watch $app with you.")
            }
        }
        return lines.random()
    }

    companion object {
        fun from(name: String?): Persona = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

object Apps {
    private val names = mapOf(
        "com.zhiliaoapp.musically" to "TikTok", "com.ss.android.ugc.trill" to "TikTok",
        "com.google.android.youtube" to "YouTube", "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook", "com.facebook.orca" to "Messenger",
        "com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram",
        "com.android.chrome" to "Chrome", "com.spotify.music" to "Spotify",
        "com.roblox.client" to "Roblox", "com.discord" to "Discord", "com.mojang.minecraftpe" to "Minecraft"
    )
    val videoApps = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.google.android.youtube", "com.instagram.android", "com.facebook.katana")
    val quiet = setOf("com.android.settings", "com.google.android.gms", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.google.android.packageinstaller", "com.samsung.android.packageinstaller")

    fun label(ctx: Context, pkg: String): String = names[pkg] ?: try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}

/** Typed commands that change Bert's settings without calling the AI. */
object ModeCommands {
    fun handle(ctx: Context, text: String, out: TextView): Boolean {
        val t = text.lowercase()
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun reply(msg: String): Boolean {
            out.text = msg
            MascotOverlayService.instance?.let { it.reloadSettings(); it.say(msg) }
            return true
        }
        fun persona(p: Persona, msg: String): Boolean {
            prefs.edit().putString("persona", p.name).apply()
            return reply(msg)
        }
        return when {
            "unmute" in t || "start talking" in t -> { prefs.edit().putBoolean("voice", true).apply(); reply("Voice on.") }
            "mute" in t || "be quiet" in t || "shut up" in t -> { prefs.edit().putBoolean("voice", false).apply(); reply("Okay, I'll stay quiet.") }
            "stop comment" in t || "don't comment" in t || "no comment" in t -> { prefs.edit().putBoolean("commentary", false).apply(); reply("No more video comments.") }
            "start comment" in t || "comment on" in t -> { prefs.edit().putBoolean("commentary", true).apply(); reply("I'll comment on your videos.") }
            "tsundere" in t -> persona(Persona.TSUNDERE, "Hmph. Fine. Tsundere mode. Not that I wanted to.")
            "no emotion" in t || "emotionless" in t || "deadpan" in t -> persona(Persona.NO_EMOTION, "Emotion disabled.")
            "normal mode" in t || "be normal" in t || "normal bert" in t -> persona(Persona.NORMAL, "Back to normal! Hi!")
            else -> false
        }
    }
}
