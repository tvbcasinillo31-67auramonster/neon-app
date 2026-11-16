package com.example.neonbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Plan(
    val action: String, val app: String, val query: String, val text: String,
    val kind: String, val target: String, val mode: String,
)

/** Turns what you type into an action (open app, search, share, answer...). */
object AssistantBrain {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val models = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash")
    private var working: String? = null

    suspend fun route(apiKey: String, userText: String): Plan = withContext(Dispatchers.IO) {
        val system = "You are Bert, a friendly cyberpunk mascot who is a phone assistant. " +
            "Turn the user's request into JSON with ALL these keys (use empty strings when unused): " +
            "action (one of: open_app, web_search, share_media, set_mode, answer), " +
            "app (app name, for open_app), query (search text, for web_search), " +
            "text (your short friendly answer under 60 words, for answer), " +
            "kind (photo, video or any, for share_media), " +
            "target (messenger, whatsapp, telegram, messages or empty, for share_media), " +
            "mode (auto or manual, for set_mode: auto = reply without asking, manual = ask first). " +
            "Use web_search for news, scores, prices, weather or anything that changes. " +
            "Use answer for general knowledge, chat, advice and writing help. " +
            "Never claim to have done something you cannot do."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts",
                JSONArray().put(JSONObject().put("text", userText)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 800)
                .put("responseMimeType", "application/json"))
            .toString()

        val order = listOfNotNull(working) + models.filter { it != working }
        var lastErr = "no model answered"
        for (model in order) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType())).build()
            val res = http.newCall(req).execute()
            val code = res.code
            val txt = res.body?.string().orEmpty()
            res.close()
            if (code in 200..299) {
                working = model
                val raw = JSONObject(txt).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optString("text")?.trim()
                if (raw.isNullOrEmpty()) throw IllegalStateException("Empty answer from Gemini")
                val o = JSONObject(raw)
                return@withContext Plan(
                    o.optString("action", "answer"), o.optString("app", ""), o.optString("query", ""),
                    o.optString("text", ""), o.optString("kind", ""), o.optString("target", ""),
                    o.optString("mode", ""))
            } else if (code == 404 || code == 400) {
                lastErr = "$model -> $code"
                continue
            } else {
                throw IllegalStateException("Gemini error $code: ${txt.take(120)}")
            }
        }
        throw IllegalStateException("No Gemini model worked. Last: $lastErr")
    }
}
