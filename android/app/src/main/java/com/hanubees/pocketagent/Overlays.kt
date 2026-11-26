package com.hanubees.pocketagent

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Full-screen layers drawn over the browser: the sign-up wall (forced after 2 minutes, like fxabsolute.com),
 * the "Talk to us" chat, and the owner's inbox. Plus the ☰ menu.
 */
class Overlays(private val a: MainActivity, private val layer: FrameLayout, private val cloud: Cloud) {
    private val main = Handler(Looper.getMainLooper())
    private var wallView: View? = null
    private var chatView: View? = null
    private var inboxView: View? = null
    var wallForced = false
        private set
    val wallUp get() = wallView != null

    private fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        Thread { val r = runCatching(work); main.post { done(r) } }.start()
    }

    private fun text(s: String, size: Float = 15f, color: Int = a.ink, bold: Boolean = false) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun field(hintText: String, type: Int) = EditText(a).apply {
        hint = hintText
        inputType = type
        setSingleLine()
        textSize = 16f
        background = a.round(a.paper, 12)
        setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
    }

    private fun primary(label: String, onClick: () -> Unit) = TextView(a).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = a.round(a.blue, 14)
        setPadding(0, a.dp(14), 0, a.dp(14))
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = a.dp(top) }

    private fun screen(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        isClickable = true // nothing underneath can be tapped
    }

    private fun show(v: View) { layer.addView(v, FrameLayout.LayoutParams(-1, -1)); layer.visibility = View.VISIBLE }
    private fun remove(v: View?) {
        if (v != null) layer.removeView(v)
        if (layer.childCount == 0) layer.visibility = View.GONE
    }

    private fun hideKeyboard(v: View) =
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)

    // ------------------------------------------------------------ the wall (sign up / sign in)
    /** forced = the 2-minute wall: no way past it except an account. Otherwise it has a Not now. */
    fun showWall(forced: Boolean, reason: String? = null, after: (() -> Unit)? = null) {
        if (wallView != null) { if (forced) wallForced = true; return }
        wallForced = forced
        var signUp = true
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24), a.dp(28), a.dp(24), a.dp(24)) }
        val title = text("", 24f, bold = true)
        val sub = text("", 15f, a.grey)
        val name = field("Your name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val email = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = field("Password (6 or more letters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val mobile = field("Mobile number (optional)", InputType.TYPE_CLASS_PHONE)
        val error = text("", 14f, a.red).apply { visibility = View.GONE }
        val go = primary("") {}
        val swap = text("", 15f, a.blue, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(16), 0, a.dp(8)) }
        val later = text("Not now", 15f, a.grey).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(8), 0, a.dp(8)) }

        fun paint() {
            title.text = if (signUp) "Create your free account" else "Welcome back"
            sub.text = when {
                !signUp -> "Sign in to carry on."
                reason != null -> reason
                forced -> "You've tried Pocket Agent for 2 minutes. Make a free account to keep using it. It takes 20 seconds, and it lets you message us any time."
                else -> "It takes 20 seconds."
            }
            name.visibility = if (signUp) View.VISIBLE else View.GONE
            mobile.visibility = if (signUp) View.VISIBLE else View.GONE
            go.text = if (signUp) "Create account" else "Sign in"
            swap.text = if (signUp) "I have an account" else "New here? Create an account"
            later.visibility = if (forced) View.GONE else View.VISIBLE
            error.visibility = View.GONE
        }
        swap.setOnClickListener { signUp = !signUp; paint() }
        later.setOnClickListener { hideKeyboard(box); remove(wallView); wallView = null }
        go.setOnClickListener {
            val n = name.text.toString().trim()
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            val m = mobile.text.toString().trim()
            val problem = when {
                signUp && n.isEmpty() -> "Please type your name."
                !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e) -> "Please type a real email address."
                p.length < 6 -> "The password needs 6 or more letters."
                else -> null
            }
            if (problem != null) { error.text = problem; error.visibility = View.VISIBLE; return@setOnClickListener }
            hideKeyboard(box)
            go.isEnabled = false; go.alpha = 0.5f; go.text = "One moment…"
            bg({ if (signUp) cloud.signUp(n, e, p, m) else cloud.signIn(e, p) }) { r ->
                go.isEnabled = true; go.alpha = 1f
                r.onSuccess {
                    remove(wallView); wallView = null; wallForced = false
                    a.signedInChanged()
                    after?.invoke()
                }.onFailure {
                    paint()
                    error.text = it.message ?: "Something went wrong. Try again."
                    error.visibility = View.VISIBLE
                }
            }
        }
        box.addView(text("✦ Pocket Agent", 15f, a.blue, bold = true))
        box.addView(title, lp(18))
        box.addView(sub, lp(8))
        box.addView(name, lp(22)); box.addView(email, lp(10)); box.addView(pass, lp(10)); box.addView(mobile, lp(10))
        box.addView(error, lp(10))
        box.addView(go, lp(18))
        box.addView(swap, lp(6))
        box.addView(later, lp(0))
        box.addView(text("Free. We never sell your details. Your saved info stays on this phone.", 12f, a.grey).apply { gravity = Gravity.CENTER }, lp(14))
        paint()
        val scroll = ScrollView(a).apply { setBackgroundColor(Color.WHITE); isClickable = true; isFillViewport = true; addView(box) }
        wallView = scroll
        show(scroll)
    }

    // ------------------------------------------------------------ chat (one person <-> us)
    /** person = whose conversation; null = my own. */
    fun openChat(person: String? = null, title: String = "Talk to us") {
        if (!cloud.signedIn) {
            showWall(false, "Make a free account so we can reply to you.") { openChat(person, title) }
            return
        }
        val iAmAdmin = person != null && person != cloud.userId
        val conv = person ?: cloud.userId
        closeChat()
        val root = screen()
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(4), a.dp(6), a.dp(12), a.dp(6)) }
        head.addView(text("←", 24f).apply { setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4)); setOnClickListener { closeChat() } })
        val headText = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        headText.addView(text(title, 18f, bold = true))
        headText.addView(text(if (iAmAdmin) "You are replying as Pocket Agent" else "We usually reply within a few hours", 12f, a.grey))
        head.addView(headText)
        root.addView(head)
        root.addView(View(a).apply { setBackgroundColor(Color.parseColor("#ececea")) }, LinearLayout.LayoutParams(-1, a.dp(1)))

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8)) }
        val scroll = ScrollView(a).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!iAmAdmin) list.addView(bubble("Hi ${cloud.name.ifEmpty { "there" }}! Tell us anything: a problem, an idea, or what you'd like Pocket Agent to do. A real person reads every message.", false, null))

        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(10)) }
        val input = EditText(a).apply {
            hint = "Type a message…"; textSize = 16f; maxLines = 4
            background = a.round(a.paper, 22); setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        var lastId = 0L
        var polling = true
        fun load() {
            bg({ cloud.messages(conv, lastId) }) { r ->
                r.onSuccess { arr ->
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        lastId = maxOf(lastId, m.getLong("id"))
                        val mine = m.optBoolean("from_admin") == iAmAdmin
                        list.addView(bubble(m.optString("body"), mine, m.optString("created_at")))
                    }
                    if (arr.length() > 0) {
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        bg({ cloud.markRead(conv, iAmAdmin) }) { a.refreshBadge() }
                    }
                }
            }
        }
        val send = TextView(a).apply {
            text = "➤"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = a.round(a.blue, 22)
            setOnClickListener {
                val msg = input.text.toString().trim()
                if (msg.isEmpty()) return@setOnClickListener
                input.setText("")
                isEnabled = false
                bg({ cloud.send(conv, msg, iAmAdmin) }) { r ->
                    isEnabled = true
                    r.onSuccess { load() }.onFailure {
                        input.setText(msg)
                        list.addView(text(it.message ?: "Not sent. Try again.", 13f, a.red))
                    }
                }
            }
        }
        row.addView(send, LinearLayout.LayoutParams(a.dp(48), a.dp(44)).apply { leftMargin = a.dp(6) })
        root.addView(row)

        chatView = root
        root.tag = Runnable { polling = false }
        show(root)
        load()
        // new replies show up by themselves while the chat is open
        main.postDelayed(object : Runnable {
            override fun run() { if (!polling || chatView !== root) return; load(); main.postDelayed(this, 4000) }
        }, 4000)
    }

    fun closeChat(): Boolean {
        val v = chatView ?: return false
        (v.tag as? Runnable)?.run()
        hideKeyboard(v)
        remove(v); chatView = null
        a.refreshBadge()
        return true
    }

    private val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun whenText(iso: String?): String = try { timeFmt.format(isoFmt.parse(iso!!.take(19))!!) } catch (e: Exception) { "" }

    private fun bubble(body: String, mine: Boolean, iso: String?): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = if (mine) Gravity.END else Gravity.START }
        box.addView(TextView(a).apply {
            text = body; textSize = 15f; setTextIsSelectable(true)
            setTextColor(if (mine) Color.WHITE else a.ink)
            background = a.round(if (mine) a.blue else a.paper, 16)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
        }, LinearLayout.LayoutParams(-2, -2).apply { if (mine) leftMargin = a.dp(48) else rightMargin = a.dp(48) })
        if (iso != null) box.addView(text(whenText(iso), 11f, a.grey).apply { setPadding(a.dp(6), a.dp(2), a.dp(6), 0) })
        return box.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) } }
    }

    // ------------------------------------------------------------ owner's inbox
    fun openInbox() {
        closeInbox()
        val root = screen()
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(4), a.dp(6), a.dp(12), a.dp(6)) }
        head.addView(text("←", 24f).apply { setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4)); setOnClickListener { closeInbox() } })
        head.addView(text("Inbox", 18f, bold = true))
        root.addView(head)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        inboxView = root
        show(root)
        fun load() {
            bg({ cloud.inbox() }) { r ->
                list.removeAllViews()
                r.onFailure { list.addView(text(it.message ?: "Couldn't load.", 14f, a.red).apply { setPadding(a.dp(16), a.dp(16), a.dp(16), 0) }) }
                r.onSuccess { arr -> fill(list, arr) }
            }
        }
        root.tag = Runnable { load() }
        load()
    }

    private fun fill(list: LinearLayout, arr: JSONArray) {
        if (arr.length() == 0) list.addView(text("No messages yet. When people write from \"Talk to us\", they show up here.", 15f, a.grey).apply { setPadding(a.dp(16), a.dp(16), a.dp(16), 0) })
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            val who = o.optString("name").ifEmpty { o.optString("email").ifEmpty { "Someone" } }
            val unread = o.optInt("unread")
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
                setOnClickListener { openChat(o.getString("user_id"), who) }
            }
            row.addView(text(who + if (unread > 0) "   ● $unread new" else "", 16f, if (unread > 0) a.blue else a.ink, bold = true))
            val contact = listOf(o.optString("email"), o.optString("mobile")).filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
            if (contact.isNotEmpty()) row.addView(text(contact, 12f, a.grey))
            row.addView(text((if (o.optBoolean("last_from_admin")) "You: " else "") + o.optString("last_body").replace('\n', ' ').take(90), 14f, a.grey))
            row.addView(text(whenText(o.optString("last_at")), 11f, a.grey))
            list.addView(row)
            list.addView(View(a).apply { setBackgroundColor(Color.parseColor("#ececea")) }, LinearLayout.LayoutParams(-1, a.dp(1)))
        }
    }

    fun closeInbox(): Boolean {
        val v = inboxView ?: return false
        remove(v); inboxView = null
        return true
    }

    /** Back button: close the top layer. The forced wall can't be closed. Returns true if it handled it. */
    fun back(): Boolean {
        if (chatView != null) { closeChat(); (inboxView?.tag as? Runnable)?.run(); return true }
        if (inboxView != null) return closeInbox()
        if (wallView != null) {
            if (wallForced) { a.finish(); return true }
            remove(wallView); wallView = null; return true
        }
        return false
    }

    // ------------------------------------------------------------ ☰ menu
    fun showMenu(anchor: View, unread: Int) {
        val m = PopupMenu(a, anchor)
        m.menu.add(0, 1, 0, if (unread > 0 && !cloud.isAdmin) "💬  Talk to us  ($unread new)" else "💬  Talk to us")
        if (cloud.isAdmin) m.menu.add(0, 2, 1, if (unread > 0) "📥  Inbox  ($unread new)" else "📥  Inbox")
        m.menu.add(0, 3, 2, "📒  Saved info")
        m.menu.add(0, 4, 3, "⚙  Settings")
        m.menu.add(0, 5, 4, if (cloud.signedIn) "👤  ${cloud.name.ifEmpty { cloud.email }} · Sign out" else "👤  Sign in")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openChat()
                2 -> openInbox()
                3 -> a.showNotes()
                4 -> a.showSettings(false)
                5 -> if (cloud.signedIn) {
                    android.app.AlertDialog.Builder(a).setMessage("Sign out of Pocket Agent?")
                        .setPositiveButton("Sign out") { _, _ -> cloud.signOut(); a.signedInChanged() }
                        .setNegativeButton("Cancel", null).show()
                } else showWall(false)
            }
            true
        }
        m.show()
    }
}
