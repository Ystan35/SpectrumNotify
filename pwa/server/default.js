// The real wiring: Upstash store, web-push with our VAPID keys, the catalog from GitHub (kept for an hour in this process).
const webpush = require("web-push");
const store = require("./store.js").create();
const { makeApp } = require("./app.js");

const CATALOG_URL = process.env.CATALOG_URL || "https://raw.githubusercontent.com/Ystan35/SpectrumNotify/main/app/src/main/assets/catalog.json";
let cached = null, cachedAt = 0;
async function getCatalog() {
  if (cached && Date.now() - cachedAt < 3600 * 1000) return cached;
  const r = await fetch(CATALOG_URL);
  if (!r.ok) { if (cached) return cached; throw new Error("catalog " + r.status); }
  cached = await r.json(); cachedAt = Date.now();
  return cached;
}

let ready = false;
async function send(subscription, payload) {
  if (!ready) { webpush.setVapidDetails(process.env.VAPID_SUBJECT, process.env.VAPID_PUBLIC, process.env.VAPID_PRIVATE); ready = true; }
  try {
    // five minutes: "class in 10 min" is useless an hour later, so the push service drops it if the phone stays unreachable
    await webpush.sendNotification(subscription, JSON.stringify(payload), { TTL: 300, urgency: "high" });
    return "ok";
  } catch (e) {
    return e.statusCode === 404 || e.statusCode === 410 ? "gone" : "failed";
  }
}

module.exports = makeApp({ store, send, getCatalog, tickSecret: process.env.TICK_SECRET });
