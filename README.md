# Pocket Agent

A phone browser with an AI inside. The phone opens a web page that shows a real
browser running on the PC; you tap and swipe it like normal, and the AI bar can
read pages, tap, type and fill forms for you. It always asks Yes/No before
anything that sends, pays or deletes, and never types passwords.

## Run

```
npm install
npm start
```

Open the "On your phone" link it prints (same Wi-Fi).

## AI brain (picked automatically from `.env`)

| In `.env`            | Brain                                   |
|----------------------|-----------------------------------------|
| `GEMINI_API_KEY=...` | Gemini (`GEMINI_MODEL`, default `gemini-flash-latest`) |
| `ANTHROPIC_API_KEY=...` | Claude API (Opus 5.5)                |
| nothing              | Claude Code on this PC (your Claude login) |

The private link key is made once and kept in `access-key.txt` (not in git).

## Files

- `server.js` – browser, screen stream, phone input, AI loop, routes
- `public/index.html` – the phone app
- `public/home.html` – public home page
- `mcp-tools.js` – bridge so Claude Code can use the browser tools
