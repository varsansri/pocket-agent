package com.hanubees.pocketagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    lateinit var web: WebView
    lateinit var notes: Notes
    private lateinit var prefs: SharedPreferences
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var logScroll: ScrollView
    private lateinit var logBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var header: TextView

    @Volatile var pageLoading = false
    private var agent: Agent? = null
    private val chat = mutableListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var askDialog: AlertDialog? = null
    private var askLatch: CountDownLatch? = null

    private val blue = Color.parseColor("#2f6fed")
    private val ink = Color.parseColor("#1a1a1a")
    private val grey = Color.parseColor("#6b6b6b")
    private val paper = Color.parseColor("#f0f0ee")
    private val red = Color.parseColor("#c0392b")

    // ------------------------------------------------------------ helpers the Agent uses
    fun <T> ui(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var r: Result<T>? = null
        main.post { r = runCatching(block); latch.countDown() }
        latch.await()
        return r!!.getOrThrow()
    }

    /** Run JavaScript in the page and wait for the answer (raw JSON text, or null). */
    fun js(script: String): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        main.post { web.evaluateJavascript(script) { out = it; latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        return out
    }

    fun jsValue(raw: String?): Any? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        return try { JSONArray("[$raw]").get(0) } catch (e: Exception) { null }
    }

    fun status(text: String) = main.post { statusView.text = text; statusView.visibility = View.VISIBLE }
    fun note(text: String) = main.post { bubble(text, "note") }
    fun notesChanged() = main.post { }

    /** Yes/No on the phone. Blocks the agent thread until answered. */
    fun ask(question: String): Boolean {
        val latch = CountDownLatch(1)
        var yes = false
        main.post {
            askLatch = latch
            speak(question)
            askDialog = AlertDialog.Builder(this)
                .setTitle("Pocket Agent asks")
                .setMessage(question)
                .setCancelable(false)
                .setPositiveButton("Yes") { _, _ -> yes = true; latch.countDown() }
                .setNegativeButton("No") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        main.post { askDialog = null; askLatch = null }
        return yes
    }

    fun cancelAsk() = main.post { askDialog?.dismiss(); askLatch?.countDown() }

    // ------------------------------------------------------------ screen
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(ink)
        minWidth = dp(44); minHeight = dp(44)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pocket-agent", Context.MODE_PRIVATE)
        notes = Notes(prefs)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // top bar: back, address, saved info, settings
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bar.addView(button("‹") { if (web.canGoBack()) web.goBack() }.apply { textSize = 26f })
        address = EditText(this).apply {
            setSingleLine()
            textSize = 14f
            hint = "Search or type a web address"
            background = round(paper, 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ ->
                web.loadUrl(toUrl(text.toString())); hideKeyboard(this); web.requestFocus(); true
            }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(button("📒") { showNotes() })
        bar.addView(button("⚙") { showSettings(false) })
        root.addView(bar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        web = WebView(this)
        setupWeb()
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // agent panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#ececea")) }
        }
        header = TextView(this).apply {
            text = "▾  Pocket Agent"
            textSize = 13f
            setTextColor(grey)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(2), dp(4), dp(4))
            visibility = View.GONE
            setOnClickListener {
                val show = logScroll.visibility != View.VISIBLE
                logScroll.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "▾  Pocket Agent" else "▸  Pocket Agent (tap to show)"
            }
        }
        panel.addView(header)
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll = ScrollView(this).apply { addView(logBox); visibility = View.GONE }
        panel.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        statusView = TextView(this).apply {
            textSize = 13f; setTextColor(blue); visibility = View.GONE; setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        panel.addView(statusView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Tell me what to do…"
            textSize = 16f
            maxLines = 3
            background = round(paper, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { onSend(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        micBtn = button("🎤") { listen() }
        row.addView(micBtn)
        sendBtn = button("➤") { if (agent != null) stopTask() else onSend() }.apply {
            setTextColor(Color.WHITE); background = round(blue, 22)
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(44)).apply { leftMargin = dp(4) })
        panel.addView(row)
        root.addView(panel)

        // keep clear of the status bar, nav bar and keyboard
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(s.left, s.top, s.right, s.bottom)
                WindowInsets.CONSUMED
            }
        } else {
            root.fitsSystemWindows = true
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { goBackOrLeave() }
        }

        tts = TextToSpeech(this) { }
        web.loadUrl(prefs.getString("lastUrl", null) ?: "https://www.google.com")
        if (prefs.getString("key", "").isNullOrEmpty()) main.postDelayed({ showSettings(true) }, 400)
    }

    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false) // "open in new tab" links stay in this one screen
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // upi:, tel:, mailto:, intent:// -> hand to the right app (GPay, PhonePe, dialler...)
                try {
                    val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, req.url)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    startActivity(intent)
                } catch (e: Exception) {
                    if (url.startsWith("intent:")) {
                        val fallback = Regex("S\\.browser_fallback_url=([^;]+)").find(url)?.groupValues?.get(1)
                        if (fallback != null) view.loadUrl(Uri.decode(fallback))
                    } else Toast.makeText(this@MainActivity, "No app on this phone can open that.", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoading = true
                if (!address.hasFocus()) address.setText(url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                pageLoading = false
                if (!address.hasFocus()) address.setText(url)
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { startActivityForResult(params.createIntent(), REQ_FILE); true } catch (e: ActivityNotFoundException) {
                    fileCallback = null; false
                }
            }
        }
        web.setDownloadListener { url, _, _, _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------ chat
    private fun bubble(text: String, who: String) {
        header.visibility = View.VISIBLE
        if (header.text.startsWith("▾")) logScroll.visibility = View.VISIBLE
        val tv = TextView(this).apply {
            this.text = text
            textSize = if (who == "note") 13f else 15f
            setTextColor(if (who == "note") grey else ink)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = round(
                when (who) { "me" -> Color.parseColor("#eef2fd"); "note" -> Color.WHITE; else -> paper }, 16,
            )
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            gravity = if (who == "me") Gravity.END else Gravity.START
            if (who == "me") leftMargin = dp(40) else rightMargin = dp(24)
        }
        logBox.addView(tv, lp)
        while (logBox.childCount > 80) logBox.removeViewAt(0)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(on: Boolean) {
        sendBtn.text = if (on) "■" else "➤"
        sendBtn.background = round(if (on) red else blue, 22)
        micBtn.alpha = if (on) 0.3f else 1f
        micBtn.isEnabled = !on
        if (!on) statusView.visibility = View.GONE
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || agent != null) return
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) { showSettings(true); return }
        input.setText("")
        hideKeyboard(input)
        tts?.stop()
        bubble(text, "me")
        val earlier = chat.takeLast(6).joinToString("\n\n") { "Person: ${it.first}\nYou: ${it.second}" }
        val prompt = (if (earlier.isNotEmpty()) "Earlier in this chat:\n$earlier\n\n---\n" else "") +
            "The browser is now on: ${web.url ?: "(nothing)"}\nNow the person says: $text"
        val ag = Agent(this, Gemini(key, prefs.getString("model", null)?.ifBlank { null } ?: DEFAULT_MODEL))
        agent = ag
        setBusy(true)
        Thread {
            var detail = ""
            val answer = try {
                ag.run(prompt)
            } catch (e: Gemini.Failed) {
                detail = e.detail
                if (ag.stopped) "Stopped." else e.userText
            } catch (e: Exception) {
                val at = e.stackTrace.firstOrNull { it.className.contains("pocketagent") }
                detail = "${e.javaClass.simpleName}: ${e.message?.take(300)}" + (if (at != null) " (at ${at.fileName}:${at.lineNumber})" else "")
                if (ag.stopped) "Stopped." else "Something broke inside the app."
            }
            main.post {
                agent = null
                setBusy(false)
                chat.add(text to answer)
                bubble(answer, "ai")
                if (detail.isNotEmpty() && answer != "Stopped.") bubble("Details: $detail", "note")
                if (answer != "Stopped.") speak(answer)
            }
        }.start()
    }

    private fun stopTask() {
        agent?.stop()
        tts?.stop()
        status("Stopping…")
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*_#`>]"), "").replace(Regex("https?://\\S+"), "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "pa")
    }

    private fun listen() {
        tts?.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell me what to do")
        try { startActivityForResult(i, REQ_MIC) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Voice typing is not available on this phone. Use the keyboard's mic instead.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_MIC -> if (resultCode == RESULT_OK) {
                val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!said.isNullOrBlank()) { input.setText(said); onSend() }
            }
            REQ_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
        }
    }

    // ------------------------------------------------------------ settings + saved info
    private fun showSettings(welcome: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            text = (if (welcome) "Welcome! Pocket Agent uses Google's Gemini AI. It is free: you need your own key.\n\n" else "") +
                "1. Tap \"Get my free key\" and sign in with Google.\n2. Tap \"Create API key\" and copy it.\n3. Come back and paste it below."
            textSize = 15f; setTextColor(ink)
        })
        box.addView(TextView(this).apply {
            text = "Get my free key →"
            textSize = 16f; setTextColor(blue); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) } catch (_: Exception) {}
            }
        })
        val keyBox = EditText(this).apply {
            hint = "Paste your Gemini key here"
            setText(prefs.getString("key", ""))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        box.addView(keyBox)
        val modelBox = EditText(this).apply {
            hint = "AI model (leave empty for $DEFAULT_MODEL)"
            setText(prefs.getString("model", ""))
            setSingleLine(); textSize = 13f
        }
        box.addView(modelBox)
        AlertDialog.Builder(this)
            .setTitle(if (welcome) "Pocket Agent" else "Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val key = keyBox.text.toString().trim().filterNot { it.isWhitespace() }
                val model = modelBox.text.toString().trim()
                prefs.edit().putString("key", key).putString("model", model).apply()
                if (key.isEmpty()) return@setPositiveButton
                Toast.makeText(this, "Checking your key…", Toast.LENGTH_SHORT).show()
                Thread {
                    val problem = Gemini(key, model.ifBlank { DEFAULT_MODEL }).ping()
                    main.post {
                        AlertDialog.Builder(this)
                            .setTitle(if (problem == null) "✓ Your key works" else "The key didn't work")
                            .setMessage(problem ?: "All set. Tell me what to do in the box at the bottom.")
                            .setPositiveButton("OK", null).show()
                    }
                }.start()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showNotes() {
        val items = notes.list()
        val b = AlertDialog.Builder(this).setTitle("📒 Saved info")
        if (items.isEmpty()) {
            b.setMessage("Nothing saved yet.\n\nSay things like \"remember my home address is …\" and I will keep it here, on this phone only.")
        } else {
            b.setItems(items.map { "${it.label}: ${it.text}" }.toTypedArray()) { _, which ->
                val it = items[which]
                AlertDialog.Builder(this)
                    .setTitle(it.label).setMessage(it.text)
                    .setPositiveButton("Delete") { _, _ -> notes.forget(it.label); showNotes() }
                    .setNegativeButton("Close", null).show()
            }
        }
        b.setNegativeButton("Close", null).show()
    }

    // ------------------------------------------------------------ misc
    private fun toUrl(text: String): String {
        val s = text.trim()
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        return "https://www.google.com/search?q=" + Uri.encode(s)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun goBackOrLeave() {
        if (agent != null) return stopTask()
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() = goBackOrLeave()

    override fun onPause() {
        super.onPause()
        web.url?.let { prefs.edit().putString("lastUrl", it).apply() }
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        agent?.stop()
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private const val REQ_MIC = 1
        private const val REQ_FILE = 2
    }
}
