package com.hanubees.pocketagent

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The person's notebook: "save my address", "remember my bus stop is...". Kept on the phone only. */
class Notes(private val prefs: SharedPreferences) {
    data class Item(val label: String, val text: String)

    // things that must never be stored, even if asked
    private val secret = Regex(
        "\\b(password|passcode|pass code|pwd|otp|one[- ]time|cvv|cvc|upi pin|atm pin|pin|security code)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun list(): List<Item> {
        val arr = try { JSONArray(prefs.getString("notes", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.optString("label"), o.optString("text"))
        }
    }

    private fun write(items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("label", it.label).put("text", it.text)) }
        prefs.edit().putString("notes", arr.toString()).apply()
    }

    private fun looksLikeCard(text: String): Boolean {
        for (m in Regex("\\b(?:\\d[ -]?){13,19}\\b").findAll(text)) {
            val d = m.value.filter { it.isDigit() }
            var sum = 0
            for (i in d.indices) {
                var n = d[d.length - 1 - i] - '0'
                if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
                sum += n
            }
            if (sum % 10 == 0) return true
        }
        return false
    }

    fun save(labelIn: String?, textIn: String?): String {
        val label = (labelIn ?: "").trim().take(80)
        val text = (textIn ?: "").trim().take(2000)
        if (label.isEmpty() || text.isEmpty()) return "Nothing saved: say what to call it and what to save."
        if (secret.containsMatchIn(label) || secret.containsMatchIn(text) || looksLikeCard(text))
            return "Not saved. Passwords, PINs, OTPs and card numbers are never stored. Tell the person kindly that they type those themselves."
        val items = list().filter { !it.label.equals(label, ignoreCase = true) }.toMutableList()
        items.add(Item(label, text))
        while (items.size > 200) items.removeAt(0)
        write(items)
        return "Saved \"$label\"."
    }

    fun forget(label: String?): String {
        val l = (label ?: "").trim()
        val items = list()
        val left = items.filter { !it.label.equals(l, ignoreCase = true) }
        if (left.size == items.size) return "Nothing called \"$l\" is saved."
        write(left)
        return "Forgot \"$l\"."
    }

    fun asText(): String {
        val items = list()
        if (items.isEmpty()) return "Nothing saved yet."
        return items.joinToString("\n") { "- ${it.label}: ${it.text}" }.take(12000)
    }
}
