// Pocket Agent: a phone web app that shows a real browser running on this PC,
// with an AI that can read and drive that browser.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";
import { chromium } from "playwright";
import Anthropic from "@anthropic-ai/sdk";

const here = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8787);
// the private key in the app link; made once and kept in access-key.txt
const keyFile = path.join(here, "access-key.txt");
if (!process.env.ACCESS_KEY && !fs.existsSync(keyFile)) fs.writeFileSync(keyFile, crypto.randomBytes(9).toString("base64url"));
const KEY = process.env.ACCESS_KEY || fs.readFileSync(keyFile, "utf8").trim();
const MODEL = "claude-opus-5-5";
const VIEW = { width: 412, height: 780 };
const MAX_STEPS = 30;

let client = null; // the phone's live connection

// ---------------------------------------------------------------- browser
const context = await chromium.launchPersistentContext(path.join(here, "browser-profile"), {
  headless: true,
  viewport: VIEW,
  deviceScaleFactor: 1,
  isMobile: true,
  hasTouch: true,
  locale: "en-IN",
  userAgent:
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
  args: ["--disable-blink-features=AutomationControlled"],
});

let page = context.pages()[0] || (await context.newPage());
let cdp = null;

async function watch(p) {
  page = p;
  if (cdp) await cdp.detach().catch(() => {});
  cdp = await context.newCDPSession(p);
  cdp.on("Page.screencastFrame", ({ data, sessionId }) => {
    send({ t: "frame", data });
    cdp?.send("Page.screencastFrameAck", { sessionId }).catch(() => {});
  });
  await cdp.send("Page.startScreencast", { format: "jpeg", quality: 60, everyNthFrame: 1 });
  p.on("framenavigated", (f) => f === p.mainFrame() && send({ t: "url", url: p.url() }));
  p.on("close", () => {
    const rest = context.pages().filter((x) => x !== p);
    if (rest.length) watch(rest[rest.length - 1]);
  });
  send({ t: "url", url: p.url() });
}
context.on("page", (p) => watch(p)); // new tabs / pop-ups take over the screen
if (page.url() === "about:blank") await page.goto("https://www.google.com").catch(() => {});
await watch(page);

async function settle() {
  await page.waitForLoadState("domcontentloaded", { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(700);
}

function toUrl(text) {
  const s = text.trim();
  if (/^https?:\/\//i.test(s)) return s;
  if (/^[\w-]+(\.[\w-]+)+(\/\S*)?$/.test(s)) return "https://" + s;
  return "https://www.google.com/search?q=" + encodeURIComponent(s);
}

// ---------------------------------------------------------------- phone link
function send(msg) {
  if (client?.readyState === 1) client.send(JSON.stringify(msg));
}

const pendingAsks = new Map();
function askPhone(text) {
  const id = crypto.randomUUID();
  send({ t: "ask", id, text });
  return new Promise((resolve) => pendingAsks.set(id, resolve));
}

// ---------------------------------------------------------------- AI tools
const RISKY = /\b(send|pay|buy|purchase|order|checkout|place order|delete|remove|submit|post|publish|transfer|confirm|book|subscribe|unsubscribe|sign out|log out)\b/i;

const tools = [
  {
    name: "read_page",
    description:
      "Read the current page: its address, title, visible text, and a numbered list of things you can click or type into. Call this first, and again after the page changes.",
    input_schema: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    name: "screenshot",
    description: "Look at the screen as an image. Use when the text from read_page is not enough (pictures, layout, captchas).",
    input_schema: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    name: "click",
    description: "Tap an item by its number from read_page.",
    input_schema: {
      type: "object",
      properties: { id: { type: "integer" } },
      required: ["id"],
      additionalProperties: false,
    },
  },
  {
    name: "tap_at",
    description: "Tap a point on the screen (x, y in pixels of the screenshot). Use only when the item has no number.",
    input_schema: {
      type: "object",
      properties: { x: { type: "integer" }, y: { type: "integer" } },
      required: ["x", "y"],
      additionalProperties: false,
    },
  },
  {
    name: "type",
    description: "Type text into a box by its number from read_page. Set press_enter true to search or go.",
    input_schema: {
      type: "object",
      properties: { id: { type: "integer" }, text: { type: "string" }, press_enter: { type: "boolean" } },
      required: ["id", "text", "press_enter"],
      additionalProperties: false,
    },
  },
  {
    name: "scroll",
    description: "Scroll the page up or down by about one screen.",
    input_schema: {
      type: "object",
      properties: { direction: { type: "string", enum: ["up", "down"] } },
      required: ["direction"],
      additionalProperties: false,
    },
  },
  {
    name: "go_to",
    description: "Open a web address, or search Google if given words.",
    input_schema: {
      type: "object",
      properties: { url: { type: "string" } },
      required: ["url"],
      additionalProperties: false,
    },
  },
  {
    name: "go_back",
    description: "Go back to the previous page.",
    input_schema: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    name: "ask_user",
    description:
      "Ask the person a yes/no question and wait. You MUST use this before anything that sends, pays, buys, books, posts, deletes or submits, describing exactly what will happen.",
    input_schema: {
      type: "object",
      properties: { question: { type: "string" } },
      required: ["question"],
      additionalProperties: false,
    },
  },
];

async function readPage() {
  const info = await page.evaluate(() => {
    document.querySelectorAll("[data-pa]").forEach((e) => e.removeAttribute("data-pa"));
    const sel =
      'a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[role=option],[contenteditable=true],[onclick]';
    const items = [];
    let n = 0;
    for (const el of document.querySelectorAll(sel)) {
      const r = el.getBoundingClientRect();
      const st = getComputedStyle(el);
      if (r.width < 2 || r.height < 2 || st.visibility === "hidden" || st.display === "none") continue;
      if (r.bottom < -window.innerHeight || r.top > window.innerHeight * 2) continue;
      n += 1;
      el.setAttribute("data-pa", String(n));
      const label = (
        el.getAttribute("aria-label") ||
        el.innerText ||
        el.getAttribute("placeholder") ||
        el.getAttribute("title") ||
        el.getAttribute("alt") ||
        el.value ||
        el.getAttribute("name") ||
        ""
      )
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, 80);
      const kind = el.tagName.toLowerCase() + (el.type ? `[${el.type}]` : "");
      const where = r.top > window.innerHeight ? " (below, scroll)" : r.bottom < 0 ? " (above)" : "";
      items.push(`${n}. ${kind} "${label}"${where}`);
      if (n >= 150) break;
    }
    return {
      url: location.href,
      title: document.title,
      text: (document.body?.innerText || "").replace(/\n{3,}/g, "\n\n").slice(0, 6000),
      items,
      scroll: `${Math.round(scrollY)} of ${Math.max(0, document.documentElement.scrollHeight - innerHeight)}`,
    };
  });
  return `Address: ${info.url}\nTitle: ${info.title}\nScrolled: ${info.scroll} px\n\nThings to tap or type into:\n${
    info.items.join("\n") || "(none)"
  }\n\nPage text:\n${info.text}`;
}

async function labelOf(id) {
  return page
    .locator(`[data-pa="${id}"]`)
    .first()
    .evaluate((el) => (el.getAttribute("aria-label") || el.innerText || el.value || "").trim().slice(0, 80))
    .catch(() => null);
}

async function guard(label) {
  if (label && RISKY.test(label)) {
    const ok = await askPhone(`Tap "${label}"? This may send, pay, or change something.`);
    if (!ok) return "The person said NO. Do not do this. Ask them what they want instead.";
  }
  return null;
}

async function runTool(name, input) {
  switch (name) {
    case "read_page":
      return readPage();
    case "screenshot": {
      const buf = await page.screenshot({ type: "jpeg", quality: 60 });
      return [{ type: "image", source: { type: "base64", media_type: "image/jpeg", data: buf.toString("base64") } }];
    }
    case "click": {
      const label = await labelOf(input.id);
      if (label === null) return "That number is not on the page any more. Call read_page again.";
      send({ t: "status", text: `Tapping "${label || "item " + input.id}"` });
      const stop = await guard(label);
      if (stop) return stop;
      const el = page.locator(`[data-pa="${input.id}"]`).first();
      await el.scrollIntoViewIfNeeded({ timeout: 3000 }).catch(() => {});
      await el.click({ timeout: 5000 }).catch(async () => el.dispatchEvent("click"));
      await settle();
      return `Tapped. Now on: ${page.url()}`;
    }
    case "tap_at": {
      const label = await page.evaluate(
        ([x, y]) => (document.elementFromPoint(x, y)?.innerText || "").trim().slice(0, 80),
        [input.x, input.y],
      );
      send({ t: "status", text: `Tapping the screen` });
      const stop = await guard(label);
      if (stop) return stop;
      await page.touchscreen.tap(input.x, input.y);
      await settle();
      return `Tapped. Now on: ${page.url()}`;
    }
    case "type": {
      const el = page.locator(`[data-pa="${input.id}"]`).first();
      const kind = await el.getAttribute("type", { timeout: 3000 }).catch(() => null);
      if (kind === "password")
        return "Never type passwords. Tell the person to type it themselves on the screen, then say done.";
      send({ t: "status", text: `Typing "${input.text.slice(0, 40)}"` });
      await el.fill(input.text, { timeout: 5000 }).catch(async () => {
        await el.click({ timeout: 3000 });
        await page.keyboard.type(input.text);
      });
      if (input.press_enter) {
        await page.keyboard.press("Enter");
        await settle();
      }
      return "Typed.";
    }
    case "scroll":
      send({ t: "status", text: `Scrolling ${input.direction}` });
      await page.mouse.move(VIEW.width / 2, VIEW.height / 2);
      await page.mouse.wheel(0, input.direction === "down" ? VIEW.height * 0.8 : -VIEW.height * 0.8);
      await page.waitForTimeout(500);
      return "Scrolled.";
    case "go_to":
      send({ t: "status", text: `Opening ${input.url}` });
      await page.goto(toUrl(input.url), { timeout: 20000 }).catch(() => {});
      await settle();
      return `Now on: ${page.url()}`;
    case "go_back":
      await page.goBack({ timeout: 10000 }).catch(() => {});
      await settle();
      return `Now on: ${page.url()}`;
    case "ask_user": {
      const ok = await askPhone(input.question);
      return ok ? "The person said YES." : "The person said NO.";
    }
    default:
      return `Unknown tool ${name}`;
  }
}

// ---------------------------------------------------------------- AI loop
const SYSTEM = `You are Pocket Agent, a friendly helper inside a phone browser. The person is usually 35-45 and not technical.
You can see and control the browser on their screen with your tools.

How to work:
- Start with read_page. Do the task step by step. After the page changes, read it again.
- Before anything that sends, pays, buys, books, posts, deletes or submits, call ask_user and say exactly what will happen.
- Never type passwords, OTPs, card numbers or PINs. If a login is needed, tell the person to log in on the screen themselves and then tell you "done".
- If something blocks you (captcha, login, error), stop and say plainly what they need to do.

How to talk:
- Short, warm, plain words. No jargon, no technical terms, no long lists.
- Final answer: 1 to 4 short sentences, or a few simple bullet points if they asked you to summarise.
- Reply in the language the person writes in.`;

// Which brain: with an API key use the Claude API directly; without one,
// use Claude Code on this PC (runs on the PC owner's Claude login, no key).
const BRAIN = process.env.GEMINI_API_KEY ? "gemini" : process.env.ANTHROPIC_API_KEY ? "api" : "claude-code";
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-flash-latest";
const anthropic = BRAIN === "api" ? new Anthropic() : null;
const chatLog = []; // [{ user, ai }] earlier turns, kept as plain text
let running = null;

async function runAgent(userText) {
  const abort = new AbortController();
  running = abort;
  send({ t: "busy", on: true });
  const earlier = chatLog
    .slice(-6)
    .map((c) => `Person: ${c.user}\nYou: ${c.ai}`)
    .join("\n\n");
  const prompt = (earlier ? `Earlier in this chat:\n${earlier}\n\n---\n` : "") + `Now the person says: ${userText}`;
  let finalText = "";
  try {
    finalText =
      BRAIN === "gemini"
        ? await brainGemini(prompt, abort)
        : BRAIN === "api"
          ? await brainApi(prompt, abort)
          : await brainClaudeCode(prompt, abort);
  } catch (e) {
    if (!abort.signal.aborted) {
      console.error(e);
      finalText =
        e instanceof Anthropic.AuthenticationError
          ? "The AI key is wrong. Check ANTHROPIC_API_KEY in the .env file."
          : e instanceof Anthropic.RateLimitError
            ? "Too many requests right now. Try again in a minute."
            : e.userText || "Something went wrong talking to the AI. Try again.";
    }
  }
  if (abort.signal.aborted) finalText = "Stopped.";
  chatLog.push({ user: userText, ai: finalText });
  send({ t: "ai", text: finalText });
  send({ t: "busy", on: false });
  for (const [id, resolve] of pendingAsks) {
    resolve(false);
    pendingAsks.delete(id);
    send({ t: "ask-clear", id });
  }
  running = null;
}

const agentHome = path.join(here, "agent-home"); // empty folder: no project files or settings
fs.mkdirSync(agentHome, { recursive: true });

function brainClaudeCode(prompt, abort) {
  const mcp = {
    mcpServers: {
      pocket: {
        type: "stdio",
        command: process.execPath,
        args: [path.join(here, "mcp-tools.js")],
        env: { POCKET_URL: `http://127.0.0.1:${PORT}`, POCKET_KEY: KEY },
      },
    },
  };
  const args = [
    "-p",
    "--output-format", "stream-json",
    "--verbose",
    "--tools", "",
    "--mcp-config", JSON.stringify(mcp),
    "--strict-mcp-config",
    "--allowedTools", "mcp__pocket",
    "--permission-mode", "dontAsk",
    "--system-prompt", SYSTEM,
    "--no-session-persistence",
    "--disable-slash-commands",
    "--setting-sources", "project",
  ];
  if (process.env.CLAUDE_MODEL) args.push("--model", process.env.CLAUDE_MODEL);
  return new Promise((resolve, reject) => {
    const child = spawn(process.env.CLAUDE_BIN || "claude", args, {
      cwd: agentHome,
      env: { ...process.env, MCP_TOOL_TIMEOUT: "600000" },
      windowsHide: true,
    });
    abort.signal.addEventListener("abort", () => child.kill());
    let buf = "";
    let result = null;
    let err = "";
    child.stdout.on("data", (d) => {
      buf += d;
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        let ev;
        try {
          ev = JSON.parse(line);
        } catch {
          continue;
        }
        if (ev.type === "assistant") {
          const text = (ev.message?.content || []).filter((b) => b.type === "text").map((b) => b.text).join("\n").trim();
          if (text) send({ t: "note", text });
        } else if (ev.type === "result") {
          result = ev;
        }
      }
    });
    child.stderr.on("data", (d) => (err += d));
    child.on("error", (e) => {
      e.userText = "Could not start Claude Code on the PC. Is it installed and logged in?";
      reject(e);
    });
    child.on("close", (code) => {
      if (abort.signal.aborted) return resolve("Stopped.");
      if (result && !result.is_error) return resolve(String(result.result || "").trim() || "Done.");
      const e = new Error(`claude exited ${code}: ${err.slice(-500)} ${JSON.stringify(result)?.slice(0, 500)}`);
      e.userText = /log ?in|auth|credential/i.test(err + JSON.stringify(result))
        ? "Claude Code on the PC is not logged in. Open it once on the PC and log in."
        : /limit|usage/i.test(err + JSON.stringify(result))
          ? "The Claude plan's usage limit is reached for now. Try again later."
          : "Something went wrong with Claude Code. Try again.";
      reject(e);
    });
    child.stdin.end(prompt);
  });
}

// Gemini brain (Google, free key from aistudio.google.com)
let gemini = null;
async function brainGemini(prompt, abort) {
  if (!gemini) {
    const { GoogleGenAI } = await import("@google/genai");
    gemini = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
  }
  const declarations = tools.map((t) => ({
    name: t.name,
    description: t.description,
    parametersJsonSchema: t.input_schema,
  }));
  const contents = [{ role: "user", parts: [{ text: prompt }] }];
  for (let step = 0; step < MAX_STEPS; step++) {
    if (abort.signal.aborted) return "Stopped.";
    send({ t: "status", text: step === 0 ? "Thinking…" : "Working…" });
    let res;
    try {
      res = await gemini.models.generateContent({
        model: GEMINI_MODEL,
        contents,
        config: {
          systemInstruction: SYSTEM,
          tools: [{ functionDeclarations: declarations }],
          abortSignal: abort.signal,
        },
      });
    } catch (e) {
      const msg = String(e.message || e);
      e.userText = /API key|API_KEY_INVALID|permission/i.test(msg)
        ? "The Gemini key looks wrong. Check GEMINI_API_KEY in the .env file."
        : /429|quota|RESOURCE_EXHAUSTED/i.test(msg)
          ? "Gemini's free limit is used up for now. Wait a minute and try again."
          : /not found|404/i.test(msg)
            ? `Gemini model "${GEMINI_MODEL}" was not found. Set GEMINI_MODEL in .env.`
            : "Something went wrong talking to Gemini. Try again.";
      throw e;
    }
    const content = res.candidates?.[0]?.content;
    const calls = res.functionCalls || [];
    const text = (content?.parts || []).filter((p) => p.text && !p.thought).map((p) => p.text).join("\n").trim();
    if (!calls.length) return text || "Done.";
    if (text) send({ t: "note", text });
    contents.push(content); // keep the model's turn as-is (includes its thought signatures)
    const parts = [];
    const images = [];
    for (const call of calls) {
      if (abort.signal.aborted) return "Stopped.";
      let out;
      try {
        out = await runTool(call.name, call.args || {});
      } catch (e) {
        out = `That did not work: ${String(e.message || e).slice(0, 300)}`;
      }
      if (Array.isArray(out)) {
        images.push(...out.map((b) => ({ inlineData: { mimeType: b.source.media_type, data: b.source.data } })));
        out = "Screenshot is attached below.";
      }
      parts.push({ functionResponse: { id: call.id, name: call.name, response: { result: out } } });
    }
    contents.push({ role: "user", parts: [...parts, ...images] });
  }
  return "I took too many steps. Tell me what to try next.";
}

async function brainApi(prompt, abort) {
  const messages = [{ role: "user", content: prompt }];
  let finalText = "";
  {
    for (let step = 0; step < MAX_STEPS; step++) {
      send({ t: "status", text: step === 0 ? "Thinking…" : "Working…" });
      const res = await anthropic.beta.messages.create(
        {
          model: MODEL,
          max_tokens: 16000,
          betas: ["server-side-fallback-2026-07-01"],
          fallbacks: "default",
          output_config: { effort: "medium" },
          system: SYSTEM,
          tools,
          messages,
        },
        { signal: abort.signal },
      );
      const text = res.content.filter((b) => b.type === "text").map((b) => b.text).join("\n").trim();
      if (res.stop_reason === "refusal") {
        finalText = "Sorry, I can't help with that one.";
        break;
      }
      const calls = res.content.filter((b) => b.type === "tool_use");
      if (!calls.length) {
        finalText = text;
        break;
      }
      if (text) send({ t: "note", text });
      messages.push({ role: "assistant", content: res.content });
      const results = [];
      for (const call of calls) {
        if (abort.signal.aborted) break;
        let out;
        try {
          out = await runTool(call.name, call.input);
        } catch (e) {
          out = `That did not work: ${String(e.message || e).slice(0, 300)}`;
        }
        results.push({ type: "tool_result", tool_use_id: call.id, content: out });
      }
      if (abort.signal.aborted) break;
      messages.push({ role: "user", content: results });
    }
    if (!finalText && !abort.signal.aborted) finalText = "I took too many steps. Tell me what to try next.";
  }
  return finalText;
}

// ---------------------------------------------------------------- phone input
async function onMessage(m) {
  switch (m.t) {
    case "tap":
      await page.touchscreen.tap(m.x, m.y);
      break;
    case "scroll":
      await page.mouse.move(VIEW.width / 2, VIEW.height / 2);
      await page.mouse.wheel(0, m.dy);
      break;
    case "type":
      await page.keyboard.type(String(m.text).slice(0, 2000));
      break;
    case "key":
      if (["Enter", "Backspace", "Tab", "Escape"].includes(m.key)) await page.keyboard.press(m.key);
      break;
    case "go":
      await page.goto(toUrl(String(m.url)), { timeout: 20000 }).catch(() => {});
      break;
    case "back":
      await page.goBack({ timeout: 10000 }).catch(() => {});
      break;
    case "reload":
      await page.reload({ timeout: 20000 }).catch(() => {});
      break;
    case "chat":
      if (!running && String(m.text).trim()) runAgent(String(m.text).trim().slice(0, 4000));
      break;
    case "stop":
      running?.abort();
      for (const [id, resolve] of pendingAsks) {
        resolve(false);
        pendingAsks.delete(id);
      }
      break;
    case "answer": {
      const resolve = pendingAsks.get(m.id);
      if (resolve) {
        pendingAsks.delete(m.id);
        resolve(Boolean(m.ok));
      }
      break;
    }
  }
}

// ---------------------------------------------------------------- server
function readBody(req) {
  return new Promise((resolve) => {
    let b = "";
    req.on("data", (d) => (b += d));
    req.on("end", () => {
      try {
        resolve(JSON.parse(b || "{}"));
      } catch {
        resolve({});
      }
    });
  });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, "http://x");
  // tool bridge for Claude Code (this PC only)
  if (req.method === "POST" && (u.pathname === "/tools" || u.pathname === "/tool")) {
    const local = ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(req.socket.remoteAddress);
    if (!local || req.headers["x-pocket-key"] !== KEY) return res.writeHead(403).end();
    res.writeHead(200, { "content-type": "application/json" });
    if (u.pathname === "/tools") return res.end(JSON.stringify({ tools }));
    const { name, input } = await readBody(req);
    let result;
    try {
      result = await runTool(name, input || {});
    } catch (e) {
      result = `That did not work: ${String(e.message || e).slice(0, 300)}`;
    }
    return res.end(JSON.stringify({ result }));
  }
  // the app itself: only with the key
  if (u.pathname === "/app") {
    if (u.searchParams.get("k") !== KEY) {
      res.writeHead(302, { location: "/" });
      return res.end();
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex" });
    return res.end(fs.readFileSync(path.join(here, "public", "index.html")));
  }
  // public home page
  if (u.pathname === "/") {
    if (u.searchParams.get("k") === KEY) {
      res.writeHead(302, { location: `/app?k=${KEY}` });
      return res.end();
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" });
    return res.end(fs.readFileSync(path.join(here, "public", "home.html")));
  }
  if (u.pathname === "/robots.txt") {
    res.writeHead(200, { "content-type": "text/plain" });
    return res.end("User-agent: *\nDisallow: /app\n");
  }
  // Google Search Console verification file for hanubees.com
  if (u.pathname === "/096c2b2c0a8269e60c017beed2d55fec.txt") {
    res.writeHead(200, { "content-type": "text/plain" });
    return res.end(fs.readFileSync(path.join(here, "public", "096c2b2c0a8269e60c017beed2d55fec.txt")));
  }
  // Pulse analytics still lives on the old project: pass it through
  if (u.pathname === "/hb.js" || u.pathname === "/api/hb") {
    try {
      const body = req.method === "POST" ? await new Promise((r) => { let b = ""; req.on("data", (d) => (b += d)); req.on("end", () => r(b)); }) : undefined;
      const up = await fetch(`https://hanubees-commerce.vercel.app${u.pathname}${u.search}`, {
        method: req.method,
        headers: {
          "content-type": req.headers["content-type"] || "text/plain",
          "user-agent": req.headers["user-agent"] || "",
          "x-forwarded-for": req.headers["cf-connecting-ip"] || req.headers["x-forwarded-for"] || "",
        },
        body,
      });
      res.writeHead(up.status, {
        "content-type": up.headers.get("content-type") || "text/plain",
        "access-control-allow-origin": "*",
        "cache-control": up.headers.get("cache-control") || "no-store",
      });
      return res.end(Buffer.from(await up.arrayBuffer()));
    } catch {
      return res.writeHead(502).end();
    }
  }
  // on hanubees.com, every old address keeps forwarding to the same page on ridkey.com
  if (/^(www\.)?hanubees\.com$/i.test(String(req.headers.host || "").split(":")[0])) {
    res.writeHead(308, { location: `https://ridkey.com${u.pathname}${u.search}` });
    return res.end();
  }
  res.writeHead(302, { location: "/" });
  res.end();
});

const wss = new WebSocketServer({ noServer: true });
server.on("upgrade", (req, socket, head) => {
  const u = new URL(req.url, "http://x");
  if (u.pathname !== "/ws" || u.searchParams.get("k") !== KEY) return socket.destroy();
  wss.handleUpgrade(req, socket, head, (ws) => {
    client?.close();
    client = ws;
    ws.send(JSON.stringify({ t: "hello", w: VIEW.width, h: VIEW.height, url: page.url(), busy: Boolean(running) }));
    cdp?.send("Page.stopScreencast").then(() =>
      cdp.send("Page.startScreencast", { format: "jpeg", quality: 60, everyNthFrame: 1 }),
    );
    ws.on("message", (raw) => {
      let m;
      try {
        m = JSON.parse(raw);
      } catch {
        return;
      }
      onMessage(m).catch((e) => console.error("input error:", e.message));
    });
  });
});

server.listen(PORT, "0.0.0.0", () => {
  const ips = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === "IPv4" && !i.internal)
    .map((i) => i.address);
  console.log("\nPocket Agent is running.");
  console.log(`  Online:         https://agent.ademvnion.com/app?k=${KEY}`);
  console.log(`  On this PC:     http://localhost:${PORT}/app?k=${KEY}`);
  for (const ip of ips) console.log(`  On your phone:  http://${ip}:${PORT}/app?k=${KEY}   (same Wi-Fi)`);
  const brainName = {
    gemini: `Gemini (${GEMINI_MODEL})`,
    api: "Claude API",
    "claude-code": "Claude Code on this PC (your Claude login, no key)",
  }[BRAIN];
  console.log(`\n  AI brain: ${brainName}`);
  console.log("");
});
