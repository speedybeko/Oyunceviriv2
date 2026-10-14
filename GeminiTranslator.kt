package com.berkay.oyunceviri

import com.berkay.oyunceviri.core.SubtitleTranslator
import com.berkay.oyunceviri.core.TranslationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gemini ile bağlam duyarlı İngilizce -> Türkçe çeviri.
 * Önceki 3 satır bağlam olarak gönderilir; aynı metin önbellekten gelir.
 * Coroutine iptal edilirse HTTP isteği de iptal edilir (eski altyazı için boşa beklenmez).
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String,
    private val gameHint: String
) : SubtitleTranslator {

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        const val SYSTEM =
            "You are a subtitle translator for video games. Translate the given English subtitle " +
                "into natural, colloquial Turkish, the way a good game localizer would. " +
                "Translate EVERY word, including ordinary English words (for example 'united', 'ally', " +
                "'route'): never leave an English common word untranslated. " +
                "Only character names, place names and made-up proper nouns stay unchanged. " +
                "Output ONLY the Turkish translation: no quotes, notes or explanations. " +
                "The text comes from OCR and may contain small recognition errors; silently fix obvious ones. " +
                "Keep it short enough to read quickly. " +
                "Previous lines are context only: never translate or repeat them."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private val history = ArrayDeque<String>()
    private val cache = HashMap<String, String>()

    override suspend fun translate(text: String): String {
        synchronized(lock) { cache[text] }?.let { return it }

        val url = try {
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                .toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build()
        } catch (e: IllegalArgumentException) {
            throw TranslationException(400, "Geçersiz model adı", e)
        }
        val request = Request.Builder()
            .url(url)
            .post(buildBody(text).toString().toRequestBody(JSON))
            .build()

        val body = call(request)
        val out = parse(body) ?: throw TranslationException(0, "Boş yanıt (güvenlik filtresi olabilir)")
        synchronized(lock) {
            if (cache.size > 200) cache.clear()
            cache[text] = out
            history.addLast(text)
            while (history.size > 3) history.removeFirst()
        }
        return out
    }

    /** OkHttp callback'ini askıya alınabilir çağrıya çevirir (Adaptör). */
    private suspend fun call(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(TranslationException(0, "Ağ hatası", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    try {
                        val text = r.body?.string().orEmpty()
                        if (!cont.isActive) return
                        if (r.isSuccessful) cont.resume(text)
                        else cont.resumeWithException(TranslationException(r.code, "HTTP ${r.code}"))
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(TranslationException(0, "Ağ hatası", e))
                    }
                }
            }
        })
    }

    private fun buildBody(text: String): JSONObject {
        val prev = synchronized(lock) { history.toList() }
        val prompt = buildString {
            if (gameHint.isNotEmpty()) append("Game: ").append(gameHint).append("\n\n")
            if (prev.isNotEmpty()) {
                append("Previous subtitle lines (context only, do not translate them):\n")
                for (p in prev) append("- ").append(p).append('\n')
                append('\n')
            }
            append("Translate this subtitle into Turkish:\n").append(text)
        }
        val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
        val user = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        return JSONObject()
            .put("systemInstruction", sys)
            .put("contents", JSONArray().put(user))
            .put("generationConfig", JSONObject().put("temperature", 0.3))
    }

    private fun parse(body: String): String? = try {
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        sb.toString().trim().removeSurrounding("\"").trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
