package com.hanubees.pocketagent

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Accounts + "Talk to us" chat, on the HanuBees Supabase project.
 * Tables pa_profiles / pa_messages / pa_admins (see supabase/pocket_agent_chat.sql); row security does the guarding.
 * Every call blocks: run them off the main thread.
 */
class Cloud(private val prefs: SharedPreferences) {
    class Err(message: String) : Exception(message)

    companion object {
        const val BASE = "https://whfxrovgvulmhqkhumuz.supabase.co"
        // publishable key: public by design (it ships in every app copy); the tables' row security protects the data
        const val KEY = "sb_publishable_pdhpMolBnG71stSPc_NzTw_Kjs9KpQ2"
    }

    val userId: String get() = prefs.getString("sb_uid", "") ?: ""
    val email: String get() = prefs.getString("sb_email", "") ?: ""
    val name: String get() = prefs.getString("sb_name", "") ?: ""
    val signedIn: Boolean get() = userId.isNotEmpty()
    var isAdmin: Boolean
        get() = prefs.getBoolean("sb_admin", false)
        private set(v) { prefs.edit().putBoolean("sb_admin", v).apply() }

    // ------------------------------------------------------------ http
    private fun http(method: String, path: String, body: String?, token: String?, prefer: String? = null): Pair<Int, String> {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } catch (e: IOException) {
            throw Err("No internet connection. Check it and try again.")
        } finally {
            c.disconnect()
        }
    }

    private fun authError(text: String): String {
        val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val code = o.optString("error_code").ifEmpty { o.optString("error") }
        val msg = o.optString("msg").ifEmpty { o.optString("error_description").ifEmpty { o.optString("message") } }
        return when {
            code == "user_already_exists" || msg.contains("already registered") -> "That email already has an account. Tap \"I have an account\" to sign in."
            code == "invalid_credentials" || code == "invalid_grant" || msg.contains("Invalid login") -> "Wrong email or password. Try again."
            code == "weak_password" || msg.contains("Password should") -> "Use a password with at least 6 characters."
            code == "validation_failed" || msg.contains("email") && msg.contains("invalid") -> "That email doesn't look right."
            code == "over_request_rate_limit" || code == "over_email_send_rate_limit" -> "Too many tries. Wait a minute and try again."
            else -> msg.ifEmpty { "Something went wrong. Try again." }
        }
    }

    private fun keepSession(o: JSONObject) {
        val user = o.optJSONObject("user") ?: throw Err("Something went wrong. Try again.")
        val expiresAt = o.optLong("expires_at", 0).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600))
        prefs.edit()
            .putString("sb_access", o.getString("access_token"))
            .putString("sb_refresh", o.getString("refresh_token"))
            .putLong("sb_expires", expiresAt)
            .putString("sb_uid", user.getString("id"))
            .putString("sb_email", user.optString("email"))
            .apply()
    }

    // ------------------------------------------------------------ accounts
    fun signUp(name: String, email: String, password: String, mobile: String) {
        val body = JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("name", name))
        val (code, text) = http("POST", "/auth/v1/signup", body.toString(), null)
        if (code !in 200..299) throw Err(authError(text))
        val o = JSONObject(text)
        if (!o.has("access_token")) throw Err("Check your email for a link to confirm, then sign in here.")
        keepSession(o)
        prefs.edit().putString("sb_name", name).apply()
        saveProfile(name, mobile.ifBlank { null })
        checkAdmin()
    }

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email).put("password", password)
        val (code, text) = http("POST", "/auth/v1/token?grant_type=password", body.toString(), null)
        if (code !in 200..299) throw Err(authError(text))
        keepSession(JSONObject(text))
        // their name, for the menu
        try {
            val arr = JSONArray(rest("GET", "/rest/v1/pa_profiles?select=name&user_id=eq.$userId", null))
            if (arr.length() > 0) prefs.edit().putString("sb_name", arr.getJSONObject(0).optString("name")).apply()
            else saveProfile(email.substringBefore('@'), null)
        } catch (_: Exception) {}
        checkAdmin()
    }

    fun signOut() {
        prefs.edit().remove("sb_access").remove("sb_refresh").remove("sb_expires").remove("sb_uid")
            .remove("sb_email").remove("sb_name").remove("sb_admin").apply()
    }

    private fun token(): String {
        val access = prefs.getString("sb_access", null) ?: throw Err("Please sign in again.")
        if (prefs.getLong("sb_expires", 0) - 120 > System.currentTimeMillis() / 1000) return access
        return refresh()
    }

    fun tokenOrNull(): String? = if (!signedIn) null else try { token() } catch (e: Exception) { null }

    private fun refresh(): String {
        val r = prefs.getString("sb_refresh", null) ?: throw Err("Please sign in again.")
        val (code, text) = http("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", r).toString(), null)
        if (code in 400..499) { signOut(); throw Err("Please sign in again.") }
        if (code !in 200..299) throw Err("Something went wrong. Try again.")
        keepSession(JSONObject(text))
        return prefs.getString("sb_access", "")!!
    }

    /** Signed-in call to the database; refreshes the login once if it has run out. */
    private fun rest(method: String, path: String, body: String?, prefer: String? = null): String {
        var (code, text) = http(method, path, body, token(), prefer)
        if (code == 401) { val t = refresh(); val r = http(method, path, body, t, prefer); code = r.first; text = r.second }
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
            throw Err("Couldn't reach the chat right now. ${msg.take(120)}".trim())
        }
        return text
    }

    private fun saveProfile(name: String, mobile: String?) {
        val o = JSONObject().put("user_id", userId).put("name", name.take(80)).put("last_seen", now())
        if (mobile != null) o.put("mobile", mobile.take(20))
        rest("POST", "/rest/v1/pa_profiles?on_conflict=user_id", o.toString(), "resolution=merge-duplicates,return=minimal")
    }

    fun touch() {
        try { rest("PATCH", "/rest/v1/pa_profiles?user_id=eq.$userId", JSONObject().put("last_seen", now()).toString(), "return=minimal") } catch (_: Exception) {}
    }

    fun checkAdmin(): Boolean {
        val yes = try { JSONArray(rest("GET", "/rest/v1/pa_admins?select=user_id&user_id=eq.$userId", null)).length() > 0 } catch (e: Exception) { isAdmin }
        isAdmin = yes
        return yes
    }

    // ------------------------------------------------------------ chat
    /** Messages in one person's conversation (theirs = userId; admin passes the person's id). */
    fun messages(person: String, afterId: Long): JSONArray =
        JSONArray(rest("GET", "/rest/v1/pa_messages?select=id,from_admin,body,created_at&user_id=eq.$person&id=gt.$afterId&order=id.asc&limit=300", null))

    fun send(person: String, text: String, fromAdmin: Boolean) {
        val o = JSONObject().put("user_id", person).put("from_admin", fromAdmin).put("body", text.take(4000))
        rest("POST", "/rest/v1/pa_messages", o.toString(), "return=minimal")
    }

    /** Mark the other side's messages as read. */
    fun markRead(person: String, iAmAdmin: Boolean) {
        val theirs = if (iAmAdmin) "false" else "true"
        try {
            rest("PATCH", "/rest/v1/pa_messages?user_id=eq.$person&from_admin=eq.$theirs&read_at=is.null",
                JSONObject().put("read_at", now()).toString(), "return=minimal")
        } catch (_: Exception) {}
    }

    /** Unread count for the menu badge. */
    fun unread(): Int = try {
        val q = if (isAdmin) "from_admin=eq.false" else "user_id=eq.$userId&from_admin=eq.true"
        JSONArray(rest("GET", "/rest/v1/pa_messages?select=id&$q&read_at=is.null&limit=99", null)).length()
    } catch (e: Exception) { 0 }

    fun inbox(): JSONArray = JSONArray(rest("POST", "/rest/v1/rpc/pa_inbox", "{}"))

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())


}
