// Small bridge: Claude Code starts this, and every browser tool call is
// passed on to the Pocket Agent server, which owns the real browser.
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const BASE = process.env.POCKET_URL; // e.g. http://127.0.0.1:8787
const KEY = process.env.POCKET_KEY;

async function call(path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-pocket-key": KEY },
    body: JSON.stringify(body ?? {}),
  });
  if (!res.ok) throw new Error(`Pocket server said ${res.status}`);
  return res.json();
}

const server = new Server({ name: "pocket", version: "1.0.0" }, { capabilities: { tools: {} } });

server.setRequestHandler(ListToolsRequestSchema, async () => {
  const { tools } = await call("/tools");
  return {
    tools: tools.map((t) => ({ name: t.name, description: t.description, inputSchema: t.input_schema })),
  };
});

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { result } = await call("/tool", { name: req.params.name, input: req.params.arguments ?? {} });
  if (Array.isArray(result)) {
    return {
      content: result.map((b) =>
        b.type === "image" ? { type: "image", data: b.source.data, mimeType: b.source.media_type } : b,
      ),
    };
  }
  return { content: [{ type: "text", text: String(result) }] };
});

await server.connect(new StdioServerTransport());
