// The three things the server does: remember a subscription, forget it, and (every minute, from a pinger) send what is due.
// makeApp takes its dependencies as arguments so the tests can run it with a fake store, a fake sender and a fixed clock.
const crypto = require("crypto");
const { occsOf } = require("../core.js");
const { due } = require("./schedule.js");

// Only real browser push services: the server POSTs to the endpoint it is given, so an arbitrary address would be an open relay.
const PUSH_HOSTS = /^(fcm\.googleapis\.com|updates(-autopush)?\.push\.services\.mozilla\.com|([a-z0-9-]+\.)*push\.apple\.com|([a-z0-9-]+\.)+notify\.windows\.com)$/;
const LEADS = [5, 10, 15, 20, 30];
const MAX_COURSES = 20, MAX_BODY = 6000, MAX_SUBS = 5000;

const idOf = (endpoint) => crypto.createHash("sha256").update(endpoint).digest("hex").slice(0, 32);
const same = (a, b) => { const x = Buffer.from(String(a)), y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };

/** Clean record from what the page sent, or { error }. Course codes and Occs must exist in the catalog. */
function validate(body, catalog) {
  if (!body || JSON.stringify(body).length > MAX_BODY) return { error: "too big" };
  const s = body.subscription;
  let host;
  try { host = new URL(s.endpoint); } catch (_) { return { error: "bad subscription" }; }
  if (host.protocol !== "https:" || !PUSH_HOSTS.test(host.hostname)) return { error: "unknown push service" };
  if (!s.keys || typeof s.keys.p256dh !== "string" || typeof s.keys.auth !== "string") return { error: "bad keys" };

  const picks = {};
  for (const [code, occ] of Object.entries(body.picks || {})) {
    const c = catalog.courses[code];
    if (c && occsOf(c).includes(String(occ))) picks[code] = String(occ);
  }
  if (Object.keys(picks).length > MAX_COURSES) return { error: "too many courses" };
  const lead = LEADS.includes(body.lead) ? body.lead : 10;
  const morning = body.morning === "off" || /^([01]\d|2[0-3]):[0-5]\d$/.test(body.morning || "") ? body.morning : "07:05";
  return { record: { subscription: { endpoint: s.endpoint, keys: { p256dh: s.keys.p256dh, auth: s.keys.auth } }, picks, classes: body.classes !== false, lead, morning } };
}

function makeApp({ store, send, getCatalog, now = Date.now, tickSecret }) {
  return {
    async subscribe(body) {
      const v = validate(body, await getCatalog());
      if (v.error) return { status: 400, body: { error: v.error } };
      const id = idOf(v.record.subscription.endpoint);
      if (!(await store.get(id)) && (await store.count()) >= MAX_SUBS) return { status: 503, body: { error: "full" } };
      await store.put(id, v.record);
      return { status: 200, body: { ok: true, courses: Object.keys(v.record.picks).length } };
    },

    async unsubscribe(body) {
      const endpoint = body && body.endpoint;
      if (typeof endpoint !== "string") return { status: 400, body: { error: "bad request" } };
      await store.del(idOf(endpoint));
      return { status: 200, body: { ok: true } };
    },

    /** "Send me a test notice": one push to the device that asks, at most once a minute. Knowing the endpoint is the proof it is yours. */
    async test(body) {
      const endpoint = body && body.endpoint;
      if (typeof endpoint !== "string") return { status: 400, body: { error: "bad request" } };
      const id = idOf(endpoint), sub = await store.get(id);
      if (!sub) return { status: 404, body: { error: "not subscribed" } };
      if (!(await store.firstTime("test|" + id + "|" + Math.floor(now() / 60000)))) return { status: 429, body: { error: "wait a minute" } };
      const r = await send(sub.subscription, { title: "Test: reminders work", body: "This is what a class reminder looks like.", tag: "test" });
      if (r === "gone") { await store.del(id); return { status: 410, body: { error: "expired" } }; }
      return { status: r === "ok" ? 200 : 502, body: { ok: r === "ok" } };
    },

    /** Called every minute by a pinger with the shared secret. Sends each due notification once. */
    async tick(secret) {
      if (!tickSecret || !secret || !same(secret, tickSecret)) return { status: 401, body: { error: "no" } };
      const catalog = await getCatalog();
      const ids = await store.ids();
      const t = now();
      const stat = { subscriptions: ids.length, sent: 0, removed: 0, failed: 0 };
      const one = async (id) => {
        const sub = await store.get(id);
        if (!sub) return;
        for (const n of due(sub, catalog, t)) {
          if (!(await store.firstTime(id + "|" + n.key))) continue;
          const r = await send(sub.subscription, { title: n.title, body: n.body, tag: n.tag });
          if (r === "gone") { await store.del(id); stat.removed++; return; }
          if (r === "ok") stat.sent++; else stat.failed++;
        }
      };
      // ten at a time: a serverless function on a free plan has about ten seconds
      for (let i = 0; i < ids.length; i += 10) await Promise.all(ids.slice(i, i + 10).map(one));
      return { status: 200, body: stat };
    },
  };
}

module.exports = { makeApp, validate, idOf, PUSH_HOSTS };
