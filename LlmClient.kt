package com.example.neonbuddy

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class LlmException(msg: String) : Exception(msg)

/** What the AI noticed, and the reply it suggests (null = no reply needed). */
data class LlmResult(val sees: String, val reply: String?)

class LlmClient(private val apiKey: String, private val model: String = "gemini-2.5-flash") {
    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()

    suspend fun draftReply(chatText: String?, screenshot: Bitmap?, persona: String): LlmResult? =
        withContext(Dispatchers.IO) {
            val parts = JSONArray()
            screenshot?.let {
                val out = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, 70, out)
                parts.put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))))
            }
            parts.put(JSONObject().put("text",
                "Visible chat text:\n${chatText ?: "(none, use the screenshot)"}\n\nLook at the chat and answer in the JSON format."))

            val system = "You are Glitch, a small cyberpunk mascot living on the owner's phone and helping them chat. " +
                "Reply style for the owner's messages: $persona. " +
                "Answer ONLY with JSON: {\"sees\": \"one short sentence (max 14 words) in your own playful mascot voice saying what you notice in the chat\", " +
                "\"reply\": \"a short natural reply the owner could send, or null if the last message is the owner's or no reply is needed\"}. " +
                "Never invent facts, commitments, or money/payment details."
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 400)
                    .put("responseMimeType", "application/json")
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()

            var backoff = 2000L
            for (attempt in 0..3) {
                val res = http.newCall(req).execute()
                val code = res.code
                val txt = res.body?.string().orEmpty()
                res.close()
                when {
                    code in 200..299 -> {
                        val raw = JSONObject(txt).optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")
                            ?.optJSONArray("parts")?.optJSONObject(0)
                            ?.optString("text")?.trim()
                        if (raw.isNullOrEmpty()) return@withContext null
                        try {
                            val o = JSONObject(raw)
                            val sees = o.optString("sees", "").trim()
                            val rep = if (o.isNull("reply")) null else o.optString("reply", "").trim()
                            val reply = if (rep.isNullOrEmpty() || rep.equals("null", true)) null else rep
                            return@withContext LlmResult(sees, reply)
                        } catch (e: Exception) {
                            throw LlmException("Couldn't read the AI's answer")
                        }
                    }
                    code == 429 || code >= 500 -> {
                        if (attempt == 3) throw LlmException("Gemini busy or quota reached ($code), try later")
                        delay(backoff)
                        backoff *= 2
                    }
                    else -> throw LlmException("Gemini error $code: ${txt.take(150)}")
                }
            }
            null
        }
}
