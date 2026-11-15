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

/** Gemini version. Same interface as before, so nothing else changes. */
class LlmClient(private val apiKey: String, private val model: String = "gemini-2.5-flash") {
    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()

    suspend fun draftReply(chatText: String?, screenshot: Bitmap?, persona: String): String? =
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
                "Visible chat text:\n${chatText ?: "(none, use the screenshot)"}\n\n" +
                "If the latest message is from the OTHER person and needs a reply, write ONLY the reply. " +
                "If the latest message is mine, or nothing needs a reply, answer exactly: NO_REPLY"))

            val system = "You draft chat replies for the phone's owner. Persona/style: $persona. " +
                "Keep replies short and natural. Never invent facts, commitments, or money/payment details."
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 300)
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
                        val reply = JSONObject(txt).optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")
                            ?.optJSONArray("parts")?.optJSONObject(0)
                            ?.optString("text")?.trim()
                        return@withContext if (reply.isNullOrEmpty() || reply == "NO_REPLY") null else reply
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
