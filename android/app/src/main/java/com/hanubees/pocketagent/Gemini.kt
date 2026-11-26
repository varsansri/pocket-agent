package com.hanubees.pocketagent

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS call to Google's Gemini API with the person's own free key. */
class Gemini(private val key: String, private val model: String) {
    class Failed(val userText: String) : Exception(userText)

    @Volatile private var conn: HttpURLConnection? = null

    fun generate(body: JSONObject): JSONObject {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return JSONObject(text)
            throw Failed(
                when {
                    code == 429 || text.contains("RESOURCE_EXHAUSTED") ->
                        "Gemini's free limit is used up for now. Wait a minute and try again."
                    code == 404 -> "The AI model \"$model\" was not found. Change it in ⚙ Settings."
                    text.contains("API_KEY_INVALID") || text.contains("API key") || code == 401 || code == 403 ->
                        "The Gemini key looks wrong. Open ⚙ Settings and paste it again."
                    code >= 500 -> "Gemini is busy right now. Try again in a minute."
                    else -> "Something went wrong talking to Gemini. Try again."
                }
            )
        } catch (e: IOException) {
            throw Failed("I can't reach the internet. Check your connection and try again.")
        } finally {
            conn = null
            c.disconnect()
        }
    }

    fun cancel() {
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}
