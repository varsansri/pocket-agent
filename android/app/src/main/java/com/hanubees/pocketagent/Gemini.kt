package com.hanubees.pocketagent

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS call to Google's Gemini API with the person's own free key. */
class Gemini(private val key: String, chosen: String) {
    class Failed(val userText: String, val detail: String = "", val code: Int = 0) : Exception(userText)

    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var cancelled = false

    // Free models, best first. When one is busy (Google says "high demand") or its free
    // limit is used up, move to the next one and stay there for this task.
    private val chain = (listOf(chosen) + BACKUPS).distinct()
    private var at = lastGood?.takeIf { System.currentTimeMillis() - lastGoodAt < 10 * 60_000 }
        ?.let { chain.indexOf(it) }?.takeIf { it >= 0 } ?: 0

    fun generate(body: JSONObject): JSONObject {
        var last: Failed? = null
        while (at < chain.size) {
            if (cancelled) throw Failed("Stopped.")
            try {
                val r = generateWith(chain[at], body)
                lastGood = chain[at]; lastGoodAt = System.currentTimeMillis()
                return r
            } catch (e: Failed) {
                last = e
                val tryNext = e.code >= 500 || e.code == 429 || e.code == 404
                if (!tryNext) throw e
                at++
            }
        }
        throw last ?: Failed("Something went wrong talking to Gemini. Try again.")
    }

    private fun generateWith(model: String, body: JSONObject): JSONObject {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 40_000 // a jammed model can hang for minutes; give up and use the next one
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return JSONObject(text)
            val why = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
            val detail = "Google said ($code): " + (why?.ifBlank { null } ?: text).take(400)
            throw Failed(
                when {
                    code == 429 || text.contains("RESOURCE_EXHAUSTED") ->
                        "Gemini's free limit is used up for now. Wait a minute and try again."
                    code == 404 -> "The AI model \"$model\" was not found. Change it in ⚙ Settings."
                    text.contains("API_KEY_INVALID") || text.contains("API key") || code == 401 || code == 403 ->
                        "The Gemini key looks wrong. Open ⚙ Settings and paste it again."
                    code >= 500 -> "All the free Gemini models are busy right now. Try again in a minute."
                    else -> "Gemini refused the request."
                },
                detail,
                code,
            )
        } catch (e: java.net.SocketTimeoutException) {
            if (cancelled) throw Failed("Stopped.")
            throw Failed("All the free Gemini models are busy right now. Try again in a minute.", "$model took too long to answer", 504)
        } catch (e: IOException) {
            if (cancelled) throw Failed("Stopped.")
            throw Failed("I can't reach the internet. Check your connection and try again.", e.toString().take(300))
        } finally {
            conn = null
            c.disconnect()
        }
    }

    /** Tiny request with no tools: does the key work at all? null = yes, else the reason. */
    fun ping(): String? = try {
        generate(JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("role", "user")
            .put("parts", org.json.JSONArray().put(JSONObject().put("text", "Reply with the word OK."))))))
        null
    } catch (e: Failed) {
        e.userText + (if (e.detail.isNotEmpty()) "\n\n" + e.detail else "")
    } catch (e: Exception) {
        e.toString().take(300)
    }

    fun cancel() {
        cancelled = true
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    companion object {
        val BACKUPS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest")
        @Volatile private var lastGood: String? = null
        @Volatile private var lastGoodAt = 0L
    }
}
