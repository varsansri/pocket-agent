package com.hanubees.pocketagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * One task: the person's words -> Gemini -> tools on the in-app browser -> a short spoken answer.
 * Runs on a background thread; every WebView touch goes through a.ui { } / a.js().
 * Same tools and rules as the PC server (server.js), so both behave alike.
 */
class Agent(private val a: MainActivity, private val gemini: Gemini) {
    @Volatile var stopped = false
        private set
    var steps = 0
        private set
    val toolCounts = linkedMapOf<String, Int>()
    val sites = linkedSetOf<String>()
    val model get() = gemini.modelUsed

    private fun noteSite(url: String?) {
        val h = try { android.net.Uri.parse(url ?: "").host?.removePrefix("www.") } catch (e: Exception) { null }
        if (!h.isNullOrBlank() && sites.size < 15) sites.add(h)
    }

    fun stop() {
        stopped = true
        gemini.cancel()
        a.cancelAsk()
    }

    // ------------------------------------------------------------ the rules
    private val systemBase = """You are Hanubees, a friendly helper inside a phone browser app. The person is usually 35-45 and not technical.
You can see and control the browser on their screen with your tools.

How to work:
- Start with read_page. Do the task step by step. After the page changes, read it again.
- Before anything that sends, pays, buys, books, posts, deletes or submits, call ask_user and say exactly what will happen.
- Never type passwords, OTPs, card numbers or PINs. If a login is needed, tell the person to log in on the screen themselves and then tell you "done".
- "Sign in with Google" buttons do not work inside this app. If a site offers another way to log in (email, phone number), suggest that.
- If something blocks you (captcha, login, error), stop and say plainly what they need to do.

Playing games:
- You CAN play web games for the person. They like to watch you play, so say yes and play.
- If they don't name a game, pick a free one that needs no login, like 2048 (play2048.co) or tic-tac-toe (search Google for "tic tac toe" and it shows a board).
- Most games are one big picture with no numbered items. Use screenshot to see the board, tap_at to tap a spot on it, and press_key for arrow keys, space or enter. Take a new screenshot after every move or two to see what changed.
- You are slow (a few seconds per move), so turn-based games work (2048, tic-tac-toe, chess, Wordle, sudoku, cards). For fast action games, say kindly that you are too slow for those and offer a turn-based one.
- Keep playing until the game ends or they say stop, then tell them in one sentence how it went.

How to talk:
- Short, warm, plain words. No jargon, no technical terms, no long lists.
- Final answer: 1 to 4 short sentences, or a few simple bullet points if they asked you to summarise.
- Reply in the language the person writes in.
- When the person says "save", "remember", "note this" or "keep this", call save_info. When they say "use my saved details", "you already have it", "my address", "my usual...", use the saved info below. Filling a form: use saved info first and only ask for what is missing.
- Never save passwords, PINs, OTPs or card numbers.
- Your final answer and your ask_user questions are read out loud by the phone. Write them as natural speech: whole sentences, no bullet symbols, no markdown, no web addresses, no emojis. Put the most important thing first, so it still makes sense if they say "stop" halfway."""

    private fun systemPrompt() = "$systemBase\n\nThe person's saved info (theirs, use it when it helps):\n${a.notes.asText()}"

    // ------------------------------------------------------------ tool list (Gemini schema)
    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String>) = JSONObject()
        .put("type", "OBJECT")
        .put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
        .put("required", JSONArray(required))

    private fun t(type: String, desc: String? = null) = JSONObject().put("type", type).apply { if (desc != null) put("description", desc) }

    private fun decl(name: String, desc: String, params: JSONObject? = null) =
        JSONObject().put("name", name).put("description", desc).apply { if (params != null) put("parameters", params) }

    private val tools = JSONArray().apply {
        put(decl("save_info", "Save something the person asks you to remember for later (address, name, email, phone, ID numbers, bus stop, favourite shop, notes, text from a page...). Same label again = replace it. Never use for passwords, PINs, OTPs or card numbers.",
            obj("label" to t("STRING", "Short name, e.g. 'Home address'"), "text" to t("STRING"), required = listOf("label", "text"))))
        put(decl("forget_info", "Delete one saved item when the person asks you to forget it.",
            obj("label" to t("STRING"), required = listOf("label"))))
        put(decl("read_page", "Read the current page: its address, title, visible text, and a numbered list of things you can click or type into. Call this first, and again after the page changes."))
        put(decl("screenshot", "Look at the screen as an image. Use when the text from read_page is not enough (pictures, layout, captchas)."))
        put(decl("click", "Tap an item by its number from read_page.", obj("id" to t("INTEGER"), required = listOf("id"))))
        put(decl("tap_at", "Tap a point on the screen (x, y in pixels of the last screenshot). Use only when the item has no number.",
            obj("x" to t("INTEGER"), "y" to t("INTEGER"), required = listOf("x", "y"))))
        put(decl("type", "Type text into a box by its number from read_page (for a drop-down list, give the choice to pick). Set press_enter true to search or go.",
            obj("id" to t("INTEGER"), "text" to t("STRING"), "press_enter" to t("BOOLEAN"), required = listOf("id", "text", "press_enter"))))
        put(decl("scroll", "Scroll the page up or down by about one screen.",
            obj("direction" to t("STRING").put("enum", JSONArray(listOf("up", "down"))).put("format", "enum"), required = listOf("direction"))))
        put(decl("go_to", "Open a web address, or search Google if given words.", obj("url" to t("STRING"), required = listOf("url"))))
        put(decl("press_key", "Press a keyboard key on the page, e.g. for games: up, down, left, right, space, enter, backspace, escape, tab. Set times to press it several times in a row.",
            obj("key" to t("STRING").put("enum", JSONArray(KEYS.keys.toList())).put("format", "enum"), "times" to t("INTEGER", "1 to 10, default 1"), required = listOf("key"))))
        put(decl("go_back", "Go back to the previous page."))
        put(decl("ask_user", "Ask the person a yes/no question and wait. You MUST use this before anything that sends, pays, buys, books, posts, deletes or submits, describing exactly what will happen.",
            obj("question" to t("STRING"), required = listOf("question"))))
    }

    // ------------------------------------------------------------ the loop
    fun run(prompt: String): String {
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        for (step in 0 until 80) { // games need many moves; Stop ends it any time
            if (stopped) return "Stopped."
            steps = step + 1
            a.status(if (step == 0) "Thinking…" else "Working…")
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            val res = gemini.generate(body)
            if (stopped) return "Stopped."
            val cand = res.optJSONArray("candidates")?.optJSONObject(0)
            val content = cand?.optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optJSONObject("functionCall")?.let { calls.add(it) }
                if (p.has("text") && !p.optBoolean("thought")) text.append(p.getString("text")).append('\n')
            }
            val said = text.toString().trim()
            if (content == null) return "I couldn't answer that one. Try saying it another way."
            if (calls.isEmpty()) return said.ifEmpty { "Done." }
            if (said.isNotEmpty()) a.note(said)
            contents.put(content) // keep the model's turn as-is (includes its thought signatures)

            val replies = JSONArray()
            val images = mutableListOf<JSONObject>()
            for (call in calls) {
                if (stopped) return "Stopped."
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                toolCounts[name] = (toolCounts[name] ?: 0) + 1
                var out: Any = try {
                    runTool(name, args)
                } catch (e: Exception) {
                    "That did not work: ${(e.message ?: e.toString()).take(300)}"
                }
                if (out is Shot) {
                    images.add(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", out.base64)))
                    out = "Screenshot is attached below (${out.w} x ${out.h} pixels)."
                }
                val fr = JSONObject().put("name", name).put("response", JSONObject().put("result", out))
                if (call.optString("id").isNotEmpty()) fr.put("id", call.optString("id"))
                replies.put(JSONObject().put("functionResponse", fr))
            }
            images.forEach { replies.put(it) }
            contents.put(JSONObject().put("role", "user").put("parts", replies))
        }
        return "I took too many steps. Tell me what to try next."
    }

    // ------------------------------------------------------------ tools
    class Shot(val base64: String, val w: Int, val h: Int)

    private val risky = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|place order|delete|remove|submit|post|publish|transfer|confirm|book|subscribe|unsubscribe|sign out|log out)\\b",
        RegexOption.IGNORE_CASE,
    )
    @Volatile private var shotScale = 1f // phone pixels per screenshot pixel

    private fun guard(label: String?): String? {
        if (!label.isNullOrBlank() && risky.containsMatchIn(label)) {
            val yes = a.ask("Tap \"$label\"? This may send, pay, or change something.")
            Track.event("ask_user", JSONObject().put("text", "Tap \"$label\"?").put("answer", if (yes) "yes" else "no").put("auto", true))
            if (!yes)
                return "The person said NO. Do not do this. Ask them what they want instead."
        }
        return null
    }

    private fun runTool(name: String, i: JSONObject): Any = when (name) {
        "save_info" -> a.notes.save(i.optString("label"), i.optString("text")).also { a.notesChanged() }
        "forget_info" -> a.notes.forget(i.optString("label")).also { a.notesChanged() }
        "read_page" -> readPage()
        "screenshot" -> screenshot()
        "click" -> click(i.optInt("id"))
        "tap_at" -> tapAt(i.optInt("x"), i.optInt("y"))
        "type" -> type(i.optInt("id"), i.optString("text"), i.optBoolean("press_enter"))
        "scroll" -> scroll(i.optString("direction") != "up")
        "go_to" -> {
            val url = toUrl(i.optString("url"))
            a.status("Opening ${i.optString("url").take(60)}")
            a.ui { a.web.loadUrl(url) }
            settle(); "Now on: ${currentUrl()}"
        }
        "press_key" -> pressKey(i.optString("key"), i.optInt("times", 1))
        "go_back" -> {
            a.ui { if (a.web.canGoBack()) a.web.goBack() }
            settle(); "Now on: ${currentUrl()}"
        }
        "ask_user" -> {
            val yes = a.ask(i.optString("question"))
            Track.event("ask_user", JSONObject().put("text", i.optString("question").take(200)).put("answer", if (yes) "yes" else "no"))
            if (yes) "The person said YES." else "The person said NO."
        }
        else -> "Unknown tool $name"
    }

    private fun currentUrl() = a.ui { a.web.url ?: "" }.also { noteSite(it) }

    private fun settle() {
        Thread.sleep(600)
        val end = SystemClock.uptimeMillis() + 8000
        while (!stopped && a.pageLoading && SystemClock.uptimeMillis() < end) Thread.sleep(150)
        Thread.sleep(400)
    }

    private fun toUrl(text: String): String {
        val s = text.trim()
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }

    private fun readPage(): String {
        val raw = a.jsValue(a.js(READ_JS)) as? String ?: return "The page is not ready yet. Try read_page again."
        val o = JSONObject(raw)
        noteSite(o.optString("url"))
        val items = o.optJSONArray("items") ?: JSONArray()
        val list = (0 until items.length()).joinToString("\n") { items.getString(it) }.ifEmpty { "(none)" }
        return "Address: ${o.optString("url")}\nTitle: ${o.optString("title")}\nScrolled: ${o.optString("scroll")} px\n\n" +
            "Things to tap or type into:\n$list\n\nPage text:\n${o.optString("text")}"
    }

    private fun pressKey(name: String, timesIn: Int): String {
        val code = KEYS[name.lowercase()] ?: return "Unknown key. Use one of: ${KEYS.keys.joinToString()}"
        val times = timesIn.coerceIn(1, 10)
        a.status("Pressing $name" + if (times > 1) " ×$times" else "")
        a.ui { if (!a.web.hasFocus()) a.web.requestFocus() }
        repeat(times) {
            if (stopped) return "Stopped."
            a.ui {
                a.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                a.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            Thread.sleep(250)
        }
        Thread.sleep(300)
        return "Pressed $name" + if (times > 1) " $times times." else "."
    }

    /** What is really on the screen, including game canvases and videos (PixelCopy), with a fallback. */
    private fun screenshot(): Shot {
        val (w, h) = a.ui { a.web.width.coerceAtLeast(1) to a.web.height.coerceAtLeast(1) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        a.ui {
            val loc = IntArray(2)
            a.web.getLocationInWindow(loc)
            try {
                android.view.PixelCopy.request(a.window, android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), bmp, { r ->
                    ok = r == android.view.PixelCopy.SUCCESS; latch.countDown()
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok) a.ui { a.web.draw(Canvas(bmp)) }
        return a.ui { shrink(bmp, w, h) }
    }

    private fun shrink(bmp: Bitmap, w: Int, h: Int): Shot {
        val tw = minOf(540, w)
        val th = h * tw / w
        val small = Bitmap.createScaledBitmap(bmp, tw, th, true)
        shotScale = w.toFloat() / tw
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, bos)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return Shot(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), tw, th)
    }

    /** A real finger tap on the WebView, in phone pixels. */
    private fun tapPhysical(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        a.ui {
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN; a.web.dispatchTouchEvent(this); recycle()
            }
        }
        Thread.sleep(70)
        a.ui {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN; a.web.dispatchTouchEvent(this); recycle()
            }
        }
    }

    private fun cssToPhysical(): Float {
        val vw = (a.jsValue(a.js("innerWidth")) as? Number)?.toFloat() ?: return 1f
        return a.ui { a.web.width.toFloat() } / vw
    }

    private fun click(id: Int): String {
        val label = a.jsValue(a.js("""(function(){var el=document.querySelector('[data-pa="$id"]');if(!el)return null;
            el.scrollIntoView({block:'center',behavior:'instant'});
            return (el.getAttribute('aria-label')||el.innerText||el.value||'').trim().slice(0,80);})()""")) as? String
            ?: return "That number is not on the page any more. Call read_page again."
        a.status("Tapping \"${label.ifEmpty { "item $id" }.take(50)}\"")
        guard(label)?.let { return it }
        Thread.sleep(250)
        val raw = a.jsValue(a.js("""(function(){var el=document.querySelector('[data-pa="$id"]');if(!el)return null;
            var r=el.getBoundingClientRect();var x=r.left+r.width/2,y=r.top+r.height/2;
            var hit=document.elementFromPoint(x,y);
            if(!hit||!(hit===el||el.contains(hit)||hit.contains(el))){el.click();return JSON.stringify({direct:true});}
            return JSON.stringify({x:x,y:y});})()""")) as? String
            ?: return "That number is not on the page any more. Call read_page again."
        val p = JSONObject(raw)
        if (!p.optBoolean("direct")) {
            val k = cssToPhysical()
            tapPhysical((p.getDouble("x") * k).toFloat(), (p.getDouble("y") * k).toFloat())
        }
        settle()
        return "Tapped. Now on: ${currentUrl()}"
    }

    private fun tapAt(x: Int, y: Int): String {
        val px = x * shotScale
        val py = y * shotScale
        val k = cssToPhysical()
        val label = a.jsValue(a.js("""(function(){var e=document.elementFromPoint(${px / k},${py / k});
            return e?(e.innerText||e.getAttribute('aria-label')||'').trim().slice(0,80):'';})()""")) as? String
        a.status("Tapping the screen")
        guard(label)?.let { return it }
        tapPhysical(px, py)
        settle()
        return "Tapped. Now on: ${currentUrl()}"
    }

    private fun type(id: Int, text: String, enter: Boolean): String {
        a.status("Typing \"${text.take(40)}\"")
        val r = a.jsValue(a.js(TYPE_JS.replace("__ID__", id.toString()).replace("__TEXT__", JSONObject.quote(text)))) as? String ?: "gone"
        when {
            r == "gone" -> return "That number is not on the page any more. Call read_page again."
            r == "password" -> return "Never type passwords. Tell the person to type it themselves on the screen, then say done."
            r.startsWith("nooption:") -> return "That choice is not in the list. The choices are: ${r.removePrefix("nooption:")}"
        }
        if (enter) {
            a.ui {
                a.web.requestFocus()
                a.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                a.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            settle()
            return "Typed and pressed Enter. Now on: ${currentUrl()}"
        }
        return "Typed."
    }

    private fun scroll(down: Boolean): String {
        a.status(if (down) "Scrolling down" else "Scrolling up")
        a.js("""(function(){var d=${if (down) 1 else -1}*innerHeight*0.8;var y=scrollY;window.scrollBy(0,d);
            if(scrollY!==y)return;var best=null,area=0;
            document.querySelectorAll('*').forEach(function(e){var s=getComputedStyle(e).overflowY;
              if((s==='auto'||s==='scroll')&&e.scrollHeight>e.clientHeight+10){var a=e.clientWidth*e.clientHeight;if(a>area){area=a;best=e;}}});
            if(best)best.scrollBy(0,d);})()""")
        Thread.sleep(500)
        return "Scrolled."
    }

    companion object {
        private val KEYS = linkedMapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "space" to KeyEvent.KEYCODE_SPACE, "enter" to KeyEvent.KEYCODE_ENTER,
            "backspace" to KeyEvent.KEYCODE_DEL, "escape" to KeyEvent.KEYCODE_ESCAPE, "tab" to KeyEvent.KEYCODE_TAB,
        )

        private val READ_JS = """(function(){
  document.querySelectorAll('[data-pa]').forEach(function(e){e.removeAttribute('data-pa')});
  var sel='a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[role=option],[contenteditable=true],[onclick]';
  var items=[],n=0,all=document.querySelectorAll(sel);
  for(var i=0;i<all.length;i++){var el=all[i];var r=el.getBoundingClientRect();var st=getComputedStyle(el);
    if(r.width<2||r.height<2||st.visibility==='hidden'||st.display==='none')continue;
    if(r.bottom<-innerHeight||r.top>innerHeight*2)continue;
    n++;el.setAttribute('data-pa',String(n));
    var label=(el.getAttribute('aria-label')||el.innerText||el.getAttribute('placeholder')||el.getAttribute('title')||el.getAttribute('alt')||el.value||el.getAttribute('name')||'').replace(/\s+/g,' ').trim().slice(0,80);
    var kind=el.tagName.toLowerCase()+(el.type?'['+el.type+']':'');
    var where=r.top>innerHeight?' (below, scroll)':r.bottom<0?' (above)':'';
    items.push(n+'. '+kind+' "'+label+'"'+where);
    if(n>=150)break;}
  return JSON.stringify({url:location.href,title:document.title,
    text:((document.body&&document.body.innerText)||'').replace(/\n{3,}/g,'\n\n').slice(0,6000),
    items:items,scroll:Math.round(scrollY)+' of '+Math.max(0,document.documentElement.scrollHeight-innerHeight)});
})()"""

        private val TYPE_JS = """(function(id,text){
  var el=document.querySelector('[data-pa="'+id+'"]');if(!el)return 'gone';
  if((el.type||'').toLowerCase()==='password')return 'password';
  el.scrollIntoView({block:'center',behavior:'instant'});
  if(el.tagName==='SELECT'){var t=text.trim().toLowerCase(),o=null;
    for(var i=0;i<el.options.length;i++){var x=el.options[i];if(x.text.trim().toLowerCase()===t||x.value===text){o=x;break;}}
    if(!o)for(var j=0;j<el.options.length;j++){if(el.options[j].text.toLowerCase().indexOf(t)>=0){o=el.options[j];break;}}
    if(!o)return 'nooption:'+[].map.call(el.options,function(x){return x.text.trim()}).join(' | ').slice(0,500);
    el.value=o.value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}
  el.focus();
  if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){
    var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(el,text);else el.value=text;
    el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}
  if(!el.isContentEditable){el.click();}
  var target=document.activeElement||el;
  if(target.tagName==='INPUT'||target.tagName==='TEXTAREA'){
    var p2=target.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(p2,'value').set.call(target,text);
    target.dispatchEvent(new Event('input',{bubbles:true}));return 'ok';}
  document.execCommand('selectAll',false,null);document.execCommand('insertText',false,text);return 'ok';
})(__ID__,__TEXT__)"""
    }
}
