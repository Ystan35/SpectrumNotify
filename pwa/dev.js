// Local test server: node pwa/dev.js   ->  http://localhost:8765  (the page and /api/*, with the keys from pwa/.env.local).
// Uses the in-memory store, so subscriptions vanish when it stops. Only for trying things out; Vercel runs api/*.js itself.
const http = require("http");
const fs = require("fs");
const path = require("path");

for (const line of fs.existsSync(path.join(__dirname, ".env.local")) ? fs.readFileSync(path.join(__dirname, ".env.local"), "utf8").split("\n") : []) {
  const m = line.match(/^([A-Z_]+)=(.*)$/);
  if (m && !(m[1] in process.env)) process.env[m[1]] = m[2];
}
const app = require("./server/default.js");

const TYPES = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".png": "image/png", ".webmanifest": "application/manifest+json" };
const readBody = (req) => new Promise((ok) => { let s = ""; req.on("data", (d) => { s += d; if (s.length > 20000) req.destroy(); }); req.on("end", () => { try { ok(JSON.parse(s || "{}")); } catch (_) { ok(null); } }); });

http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const reply = (status, body, type = "application/json") => { res.writeHead(status, { "Content-Type": type }); res.end(typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body)); };
  try {
    if (url.pathname === "/api/subscribe" && req.method === "POST") { const r = await app.subscribe(await readBody(req)); return reply(r.status, r.body); }
    if (url.pathname === "/api/unsubscribe" && req.method === "POST") { const r = await app.unsubscribe(await readBody(req)); return reply(r.status, r.body); }
    if (url.pathname === "/api/test" && req.method === "POST") { const r = await app.test(await readBody(req)); return reply(r.status, r.body); }
    if (url.pathname === "/api/tick") {
      const bearer = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
      const r = await app.tick(bearer || url.searchParams.get("key") || ""); return reply(r.status, r.body);
    }
    const file = path.join(__dirname, url.pathname === "/" ? "index.html" : decodeURIComponent(url.pathname));
    if (!file.startsWith(__dirname) || /node_modules|\.env|server/.test(path.relative(__dirname, file)) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) return reply(404, "not found", "text/plain");
    reply(200, fs.readFileSync(file), TYPES[path.extname(file)] || "application/octet-stream");
  } catch (e) { reply(500, { error: String(e.message || e) }); }
}).listen(8765, "127.0.0.1", () => console.log("http://localhost:8765"));
