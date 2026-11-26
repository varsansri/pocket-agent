package com.hanubees.pocketagent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Usage tracking, like fxabsolute.com: every event goes to our Vercel function (/api/track), which adds
 * country/city from the edge and stores it in Supabase. Events wait in a small queue on the phone and are
 * sent in batches, so nothing is lost when the phone is offline or the app is closed.
 */
object Track {
    private const val URL_TRACK = "https://pocket-agent-seven.vercel.app/api/track"
    private lateinit var prefs: SharedPreferences
    private var cloud: Cloud? = null
    private val lock = Any()
    private var queue = JSONArray()
    @Volatile private var sending = false
    lateinit var deviceId: String
        private set

    fun init(ctx: Context, p: SharedPreferences, c: Cloud) {
        prefs = p
        cloud = c
        deviceId = p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
        queue = try { JSONArray(p.getString("track_queue", "[]")) } catch (e: Exception) { JSONArray() }

        // a crash from last time is reported now
        p.getString("last_crash", null)?.let {
            event("error", JSONObject().put("error", "crash: " + it.take(400)))
            p.edit().remove("last_crash").apply()
        }
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val at = e.stackTrace.firstOrNull { it.className.contains("pocketagent") }
            p.edit().putString("last_crash", "${e.javaClass.simpleName}: ${e.message} ${at?.let { "(${it.fileName}:${it.lineNumber})" } ?: ""}").commit()
            before?.uncaughtException(t, e)
        }

        if (!p.getBoolean("first_open_sent", false)) {
            event("first_open", JSONObject().put("source", installSource(ctx)))
            p.edit().putBoolean("first_open_sent", true).apply()
        }
    }

    /** Which app the APK was opened from: WhatsApp, Telegram, Chrome download, Files... */
    private fun installSource(ctx: Context): String = try {
        val pkg = if (Build.VERSION.SDK_INT >= 30) {
            val info = ctx.packageManager.getInstallSourceInfo(ctx.packageName)
            info.initiatingPackageName ?: info.installingPackageName
        } else {
            @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }
        when {
            pkg == null -> "unknown"
            pkg == "com.android.vending" -> "Play Store"
            pkg.startsWith("com.whatsapp") -> "WhatsApp"
            pkg.startsWith("org.telegram") -> "Telegram"
            pkg == "com.android.chrome" -> "Chrome download"
            pkg.contains("files") || pkg.contains("filemanager") || pkg.contains("documentsui") -> "Files app"
            pkg.contains("packageinstaller") -> "Downloaded file"
            pkg.startsWith("com.instagram") -> "Instagram"
            pkg.startsWith("com.google.android.gm") -> "Gmail"
            else -> pkg
        }
    } catch (e: Exception) { "unknown" }

    fun event(name: String, props: JSONObject = JSONObject()) {
        if (!::prefs.isInitialized) return
        synchronized(lock) {
            queue.put(JSONObject().put("name", name).put("at", System.currentTimeMillis()).put("props", props))
            while (queue.length() > 400) queue.remove(0) // offline for ages: keep the newest
            prefs.edit().putString("track_queue", queue.toString()).apply()
        }
        if (queue.length() >= 20) flush()
    }

    fun flush() {
        if (!::prefs.isInitialized || sending) return
        sending = true
        Thread {
            try {
                while (true) {
                    val batch = JSONArray()
                    synchronized(lock) { for (i in 0 until minOf(60, queue.length())) batch.put(queue.get(i)) }
                    if (batch.length() == 0) break
                    val body = JSONObject()
                        .put("device_id", deviceId)
                        .put("app_version", BuildConfig.VERSION_NAME.removeSuffix("-personal"))
                        .put("device", JSONObject()
                            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                            .put("os", "Android ${Build.VERSION.RELEASE}")
                            .put("locale", Locale.getDefault().toLanguageTag())
                            .put("tz", TimeZone.getDefault().id)
                            .put("used_ms", prefs.getLong("usedMsTotal", 0)))
                        .put("events", batch)
                    cloud?.tokenOrNull()?.let { body.put("token", it) }
                    val c = URL(URL_TRACK).openConnection() as HttpURLConnection
                    val ok = try {
                        c.requestMethod = "POST"; c.doOutput = true
                        c.connectTimeout = 15_000; c.readTimeout = 20_000
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        c.responseCode in 200..299
                    } finally { c.disconnect() }
                    if (!ok) break
                    synchronized(lock) {
                        repeat(batch.length()) { queue.remove(0) }
                        prefs.edit().putString("track_queue", queue.toString()).apply()
                    }
                }
            } catch (_: Exception) {
                // offline: they stay queued and go next time
            } finally {
                sending = false
            }
        }.start()
    }
}
