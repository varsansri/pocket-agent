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

// After the key is used once, a cookie remembers the phone, so plain /app works.
function hasKeyCookie(req) {
  return String(req.headers.cookie || "").split(/;\s*/).some((c) => c === `pa_k=${KEY}`);
}
function keyCookie(req) {
  const https = String(req.headers["x-forwarded-proto"] || req.headers["cf-visitor"] || "").includes("https");
  return `pa_k=${KEY}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax${https ? "; Secure" : ""}`;
}
const MODEL = "claude-opus-5-5";
// the browser's screen size; the phone tells us its real size and sharpness
const VIEW = { width: 412, height: 780, scale: 2 };
const MAX_STEPS = 30;

const clients = new Set(); // every open screen (phone, PC tab...) sees the same browser

// ---------------------------------------------------------------- browser
const context = await chromium.launchPersistentContext(path.join(here, "browser-profile"), {
  headless: true,
  viewport: VIEW,
  deviceScaleFactor: 2,
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
  await applyMetrics();
  cdp.on("Page.screencastFrame", ({ data, sessionId }) => {
    send({ t: "frame", data });
    scheduleFieldScan();
    cdp?.send("Page.screencastFrameAck", { sessionId }).catch(() => {});
  });
  await cdp.send("Page.startScreencast", streamOpts()).catch(() => {});
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

// The live picture is sent smaller than the page is drawn: 1.5x sharpness and
// quality 60 is about a quarter of the data of full 3x / 82, so it keeps up.
function streamOpts() {
  return { format: "jpeg", quality: 60, everyNthFrame: 1, maxWidth: Math.round(VIEW.width * 1.5), maxHeight: Math.round(VIEW.height * 1.5) };
}

// make the browser exactly the phone's screen size and sharpness
async function applyMetrics() {
  await cdp
    ?.send("Emulation.setDeviceMetricsOverride", {
      width: VIEW.width,
      height: VIEW.height,
      deviceScaleFactor: VIEW.scale,
      mobile: true,
    })
    .catch(() => {});
}

// restart the screen stream so a newly connected phone gets a fresh frame;
// calls are chained so two quick connections can't overlap
let screencastChain = Promise.resolve();
function restartScreencast() {
  screencastChain = screencastChain
    .then(async () => {
      await cdp?.send("Page.stopScreencast").catch(() => {});
      await cdp?.send("Page.startScreencast", streamOpts()).catch(() => {});
    })
    .catch(() => {});
}

// never let one failed browser call take the whole server down
process.on("unhandledRejection", (e) => console.error("unhandled:", e?.message || e));
process.on("uncaughtException", (e) => console.error("uncaught:", e?.message || e));

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
  const data = JSON.stringify(msg);
  for (const c of clients) if (c.readyState === 1) c.send(data);
}

const pendingAsks = new Map();
function askPhone(text) {
  const id = crypto.randomUUID();
  send({ t: "ask", id, text });
  return new Promise((resolve) => pendingAsks.set(id, resolve));
}

// ---------------------------------------------------------------- AI tools
const RISKY = /\b(send|pay|buy|purchase|order|checkout|place order|delete|remove|submit|post|publish|transfer|confirm|book|subscribe|unsubscribe|sign out|log out)\b/i;

// ---------------------------------------------------------------- saved info (the person's notebook)
// "Save my address", "remember my bus stop is..." -> kept in saved-info.json and shown to the AI
// at the start of every task, so "use my saved details" just works.
const infoFile = path.join(here, "saved-info.json");
let savedInfo = [];
try { savedInfo = JSON.parse(fs.readFileSync(infoFile, "utf8")); } catch {}
function writeInfo() {
  fs.writeFileSync(infoFile, JSON.stringify(savedInfo, null, 2));
  send({ t: "notes", list: savedInfo });
}
// things that must never be stored, even if asked
const SECRET = /\b(password|passcode|pass code|pwd|otp|one[- ]time|cvv|cvc|upi pin|atm pin|pin|security code)\b/i;
function looksLikeCard(text) {
  for (const m of String(text).matchAll(/\b(?:\d[ -]?){13,19}\b/g)) {
    const d = m[0].replace(/\D/g, "");
    let sum = 0;
    for (let i = 0; i < d.length; i++) { let n = +d[d.length - 1 - i]; if (i % 2) { n *= 2; if (n > 9) n -= 9; } sum += n; }
    if (sum % 10 === 0) return true;
  }
  return false;
}
function saveInfo(label, text) {
  label = String(label || "").trim().slice(0, 80);
  text = String(text || "").trim().slice(0, 2000);
  if (!label || !text) return "Nothing saved: say what to call it and what to save.";
  if (SECRET.test(label) || SECRET.test(text) || looksLikeCard(text))
    return "Not saved. Passwords, PINs, OTPs and card numbers are never stored. Tell the person kindly that they type those themselves.";
  const old = savedInfo.find((x) => x.label.toLowerCase() === label.toLowerCase());
  if (old) Object.assign(old, { text, updated: new Date().toISOString() });
  else savedInfo.push({ label, text, updated: new Date().toISOString() });
  if (savedInfo.length > 200) savedInfo.shift();
  writeInfo();
  return `Saved "${label}".`;
}
function forgetInfo(label) {
  const before = savedInfo.length;
  savedInfo = savedInfo.filter((x) => x.label.toLowerCase() !== String(label || "").trim().toLowerCase());
  if (savedInfo.length === before) return `Nothing called "${label}" is saved.`;
  writeInfo();
  return `Forgot "${label}".`;
}
function savedInfoText() {
  if (!savedInfo.length) return "Nothing saved yet.";
  return savedInfo.map((x) => `- ${x.label}: ${x.text}`).join("\n").slice(0, 12000);
}

const tools = [
  {
    name: "save_info",
    description:
      "Save something the person asks you to remember for later (address, name, email, phone, ID numbers, bus stop, favourite shop, notes, text from a page...). Same label again = replace it. Never use for passwords, PINs, OTPs or card numbers.",
    input_schema: {
      type: "object",
      properties: { label: { type: "string", description: "Short name, e.g. 'Home address'" }, text: { type: "string" } },
      required: ["label", "text"],
      additionalProperties: false,
    },
  },
  {
    name: "forget_info",
    description: "Delete one saved item when the person asks you to forget it.",
    input_schema: { type: "object", properties: { label: { type: "string" } }, required: ["label"], additionalProperties: false },
  },
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
    case "save_info":
      return saveInfo(input.label, input.text);
    case "forget_info":
      return forgetInfo(input.label);
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
const SYSTEM_BASE = `You are Pocket Agent, a friendly helper inside a phone browser. The person is usually 35-45 and not technical.
You can see and control the browser on their screen with your tools.

How to work:
- Start with read_page. Do the task step by step. After the page changes, read it again.
- Before anything that sends, pays, buys, books, posts, deletes or submits, call ask_user and say exactly what will happen.
- Never type passwords, OTPs, card numbers or PINs. If a login is needed, tell the person to log in on the screen themselves and then tell you "done".
- If something blocks you (captcha, login, error), stop and say plainly what they need to do.

How to talk:
- Short, warm, plain words. No jargon, no technical terms, no long lists.
- Final answer: 1 to 4 short sentences, or a few simple bullet points if they asked you to summarise.
- Reply in the language the person writes in.
- When the person says "save", "remember", "note this" or "keep this", call save_info. When they say "use my saved details", "you already have it", "my address", "my usual...", use the saved info below. Filling a form: use saved info first and only ask for what is missing.
- Never save passwords, PINs, OTPs or card numbers.
- Your final answer and your ask_user questions are read out loud by the phone. Write them as natural speech: whole sentences, no bullet symbols, no markdown, no web addresses, no emojis. Put the most important thing first, so it still makes sense if they say "stop" halfway.`;
// the saved info goes in fresh at the start of every task
const systemPrompt = () => `${SYSTEM_BASE}

The person's saved info (theirs, use it when it helps):
${savedInfoText()}`;

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
    "--system-prompt", systemPrompt(),
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
          systemInstruction: systemPrompt(),
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
          system: systemPrompt(),
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

// ---------------------------------------------------------------- where the text boxes are
// The phone needs this BEFORE a tap: a phone only opens its keyboard inside the tap itself,
// so it must already know whether the finger is on a text box.
let scanTimer = null, lastFields = "";
function scheduleFieldScan() {
  if (scanTimer) return;
  scanTimer = setTimeout(async () => {
    scanTimer = null;
    const list = await page
      .evaluate(() => {
        const out = [];
        const skip = ["button", "submit", "reset", "checkbox", "radio", "range", "color", "file", "image", "hidden"];
        for (const e of document.querySelectorAll("input,textarea,select,[contenteditable=''],[contenteditable=true]")) {
          if (e.disabled || e.readOnly) continue;
          const t = (e.type || "").toLowerCase();
          if (e.tagName === "INPUT" && skip.includes(t)) continue;
          const r = e.getBoundingClientRect();
          if (r.width < 4 || r.height < 4 || r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue;
          const st = getComputedStyle(e);
          if (st.visibility === "hidden" || st.display === "none") continue;
          const kind = e.tagName === "SELECT" ? "select" : e.tagName === "TEXTAREA" || e.isContentEditable ? "text" : t || "text";
          out.push([Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height), kind]);
          if (out.length > 120) break;
        }
        return out;
      })
      .catch(() => null);
    if (!list) return;
    const j = JSON.stringify(list);
    if (j !== lastFields) { lastFields = j; send({ t: "fields", list }); }
  }, 350);
}

// a <select> opened on the PC draws its list off-screen, so the phone shows the choices itself
async function offerSelect() {
  const info = await page
    .evaluate(() => {
      const e = document.activeElement;
      if (!e || e.tagName !== "SELECT") return null;
      return { options: [...e.options].slice(0, 400).map((o, i) => ({ i, label: o.label || o.text, disabled: o.disabled })), selected: e.selectedIndex };
    })
    .catch(() => null);
  if (info) send({ t: "select", ...info });
  return Boolean(info);
}

// ---------------------------------------------------------------- phone input
let inputChain = Promise.resolve();
async function onMessage(m) {
  switch (m.t) {
    case "tap": {
      await page.touchscreen.tap(m.x, m.y);
      // if the tap landed in a text box, tell the phone to open its keyboard
      await page.waitForTimeout(150);
      if (await offerSelect()) { send({ t: "kbd", on: false }); break; }
      const box = await page
        .evaluate(() => {
          const e = document.activeElement;
          if (!e || e === document.body) return null;
          const t = (e.type || "").toLowerCase();
          if (e.isContentEditable || e.tagName === "TEXTAREA") return "text";
          if (e.tagName === "INPUT" && !["button", "submit", "reset", "checkbox", "radio", "range", "color", "file", "image"].includes(t)) return t || "text";
          return null;
        })
        .catch(() => null);
      send({ t: "kbd", on: Boolean(box), kind: box });
      break;
    }
    case "text":
      await page.keyboard.insertText(String(m.text).slice(0, 2000));
      break;
    case "forget":
      forgetInfo(m.label);
      break;
    case "pick": {
      // choose an option in the <select> the phone showed
      const i = Number(m.i);
      await page
        .evaluate((i) => {
          const e = document.activeElement;
          if (!e || e.tagName !== "SELECT" || !e.options[i]) return;
          e.selectedIndex = i;
          e.dispatchEvent(new Event("input", { bubbles: true }));
          e.dispatchEvent(new Event("change", { bubbles: true }));
        }, i)
        .catch(() => {});
      await page.keyboard.press("Escape").catch(() => {});
      break;
    }
    case "size": {
      const w = Math.round(Math.min(Math.max(Number(m.w) || 412, 300), 600));
      const h = Math.round(Math.min(Math.max(Number(m.h) || 780, 300), 1200));
      const scale = Math.min(Math.max(Number(m.dpr) || 1, 1), 2);
      if (w === VIEW.width && h === VIEW.height && scale === VIEW.scale) break;
      Object.assign(VIEW, { width: w, height: h, scale });
      await applyMetrics();
      send({ t: "vp", w, h });
      restartScreencast();
      break;
    }
    case "scroll":
      await page.mouse.move(VIEW.width / 2, VIEW.height / 2);
      await page.mouse.wheel(0, m.dy);
      break;
    case "type":
      await page.keyboard.type(String(m.text).slice(0, 2000));
      break;
    case "key":
      if (["Enter", "Backspace", "Delete", "Tab", "Escape", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End"].includes(m.key))
        await page.keyboard.press(m.key);
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
    {
      const k = u.searchParams.get("k");
      console.log(`[app] host=${req.headers.host} key=${k === null ? "none" : k === KEY ? "ok" : `WRONG(len ${k.length})`} remembered=${hasKeyCookie(req)} raw=${JSON.stringify(req.url.replace(KEY, "<KEY>")).slice(0, 80)} ua=${String(req.headers["user-agent"] || "").slice(0, 70)}`);
    }
    // opened once with the key: remember this phone and clean the address
    if (u.searchParams.get("k") === KEY) {
      res.writeHead(302, { location: "/app", "set-cookie": keyCookie(req), "cache-control": "no-store" });
      return res.end();
    }
    if (!hasKeyCookie(req)) {
      res.writeHead(302, { location: "/" });
      return res.end();
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex" });
    return res.end(fs.readFileSync(path.join(here, "public", "index.html")));
  }
  // public home page
  if (u.pathname === "/") {
    if (u.searchParams.get("k") === KEY) {
      res.writeHead(302, { location: "/app", "set-cookie": keyCookie(req), "cache-control": "no-store" });
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
  if (u.pathname !== "/ws" || (u.searchParams.get("k") !== KEY && !hasKeyCookie(req))) return socket.destroy();
  wss.handleUpgrade(req, socket, head, (ws) => {
    clients.add(ws);
    ws.on("close", () => clients.delete(ws));
    ws.send(JSON.stringify({ t: "hello", w: VIEW.width, h: VIEW.height, url: page.url(), busy: Boolean(running) }));
    restartScreencast();
    ws.send(JSON.stringify({ t: "notes", list: savedInfo }));
    lastFields = ""; // make the next scan reach this new screen too
    scheduleFieldScan();
    ws.on("message", (raw) => {
      let m;
      try {
        m = JSON.parse(raw);
      } catch {
        return;
      }
      // taps and keys run one after another, in the order they were sent;
      // chat / stop / answers go straight through so Stop always works
      if (["chat", "stop", "answer"].includes(m.t)) {
        onMessage(m).catch((e) => console.error("input error:", e.message));
      } else {
        inputChain = inputChain.then(() => onMessage(m)).catch((e) => console.error("input error:", e.message));
      }
    });
  });
});

server.listen(PORT, "0.0.0.0", () => {
  const ips = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === "IPv4" && !i.internal)
    .map((i) => i.address);
  console.log("\nPocket Agent is running.");
  console.log(`  Online:         https://hanubees.com/app?k=${KEY}   (first time; after that just hanubees.com/app)`);
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
