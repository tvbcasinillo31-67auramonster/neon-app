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

class LlmClient(
    private val apiKey: String,
    // Tried in order (fast model first). Google retires models often, so edit this list if one stops working.
    private val models: List<String> = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash"),
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private var working: String? = null  // remembers which model answered

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
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1500)
                    .put("responseMimeType", "application/json"))
                .toString()

            val order = listOfNotNull(working) + models.filter { it != working }
            var lastErr = "no model answered"
            for (model in order) {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody("application/json".toMediaType())).build()

                var backoff = 2000L
                var attempt = 0
                while (attempt < 3) {
                    attempt++
                    val res = try {
                        http.newCall(req).execute()
                    } catch (e: java.io.IOException) {
                        throw LlmException("Network problem: " + e.javaClass.simpleName + " " + (e.message ?: ""))
                    }
                    val code = res.code
                    val txt = res.body?.string().orEmpty()
                    res.close()
                    if (code in 200..299) {
                        working = model
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
                    } else if (code == 404 || code == 400 || (code == 429 && txt.contains("limit: 0"))) {
                        // Model missing or not allowed on this key: try the next one.
                        lastErr = "$model -> $code: ${txt.take(100)}"
                        break
                    } else if (code == 429 || code >= 500) {
                        if (attempt == 3) throw LlmException("Gemini busy or quota reached ($code), try later")
                        delay(backoff)
                        backoff *= 2
                    } else {
                        throw LlmException("Gemini error $code: ${txt.take(150)}")
                    }
                }
            }
            throw LlmException("No Gemini model worked. Last: $lastErr")
        }
}
