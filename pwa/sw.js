// Offline: every file tries the network first and falls back to the last saved copy, so online users never see a stale page.
// Bump the version whenever a file below changes: pages are served from this cache first, so an old name keeps the old page.
const CACHE = "sn-pwa-v7";
const SHELL = ["./", "index.html", "core.js", "config.js", "manifest.webmanifest", "icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png"];
const CATALOG = "https://raw.githubusercontent.com/Ystan35/SpectrumNotify/main/app/src/main/assets/catalog.json";

self.addEventListener("install", (e) => e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())));

self.addEventListener("activate", (e) =>
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())));

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  // the catalog and the page's own files: network first (always the newest when online), the saved copy when offline
  if (req.url === CATALOG || new URL(req.url).origin === location.origin) {
    e.respondWith(
      fetch(req).then((res) => { if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); } return res; })
        .catch(() => caches.match(req)));
  }
});

// ---- reminders: the server pushes { title, body, tag }; every push MUST show a notice (iPhone and Chrome cancel the subscription otherwise)
self.addEventListener("push", (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (_) { d = { body: e.data ? e.data.text() : "" }; }
  e.waitUntil(self.registration.showNotification(d.title || "Spectrum Notify", { body: d.body || "", tag: d.tag, icon: "icon-192.png", badge: "icon-192.png" }));
});

// tapping the notice opens the page, or brings an open one to the front
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
    for (const c of list) if ("focus" in c) return c.focus();
    return self.clients.openWindow("./");
  }));
});
