// Where subscriptions live. Upstash Redis over plain HTTPS when UPSTASH_REDIS_REST_URL / _TOKEN are set (Vercel's and Netlify's
// free Redis both speak it); an in-memory copy otherwise, which is only right for local tests: a serverless function forgets it.
const URL_ = process.env.UPSTASH_REDIS_REST_URL;
const TOKEN = process.env.UPSTASH_REDIS_REST_TOKEN;

async function redis(...cmd) {
  const r = await fetch(URL_, { method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" }, body: JSON.stringify(cmd) });
  if (!r.ok) throw new Error("store " + r.status);
  return (await r.json()).result;
}

function memory() {
  const kv = new Map(), set = new Set(), sent = new Set();
  return {
    kind: "memory",
    async put(id, sub) { kv.set(id, JSON.stringify(sub)); set.add(id); },
    async get(id) { const v = kv.get(id); return v ? JSON.parse(v) : null; },
    async del(id) { kv.delete(id); set.delete(id); },
    async ids() { return [...set]; },
    async count() { return set.size; },
    async firstTime(key) { if (sent.has(key)) return false; sent.add(key); return true; },
  };
}

function upstash() {
  return {
    kind: "upstash",
    async put(id, sub) { await redis("SET", "sub:" + id, JSON.stringify(sub)); await redis("SADD", "subs", id); },
    async get(id) { const v = await redis("GET", "sub:" + id); return v ? JSON.parse(v) : null; },
    async del(id) { await redis("DEL", "sub:" + id); await redis("SREM", "subs", id); },
    async ids() { return (await redis("SMEMBERS", "subs")) || []; },
    async count() { return await redis("SCARD", "subs"); },
    // "set if absent, forget after two days": true only for the first caller, so two overlapping ticks cannot both send
    async firstTime(key) { return (await redis("SET", "sent:" + key, "1", "NX", "EX", 172800)) === "OK"; },
  };
}

const create = () => (URL_ && TOKEN ? upstash() : memory());
module.exports = { create, memory };
