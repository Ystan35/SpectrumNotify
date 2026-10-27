// SpectrumNotify cloud poller.
// Cron every 10 min: for each registered user (KV prefix user:), poll Moodle
// with their token, push new notifications / deadlines to their device via FCM.
// ntfy is kept as a debug channel.

let env = {};

// ---------------- small utils ----------------

async function sha256Hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function b64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function importPrivateKey(pem) {
  const der = atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""));
  const raw = Uint8Array.from(der, c => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8", raw,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false, ["sign"],
  );
}

// ---------------- FCM HTTP v1 (JWT -> access token -> send) ----------------

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

async function getAccessToken(fcm) {
  const cacheKey = `fcm_token:${fcm.client_email}`;
  const cached = await env.STATE.get(cacheKey);
  if (cached) return cached;

  const now = Math.floor(Date.now() / 1000);
  const header = b64url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(new TextEncoder().encode(JSON.stringify({
    iss: fcm.client_email,
    scope: FCM_SCOPE,
    aud: fcm.token_uri,
    iat: now,
    exp: now + 3600,
  })));
  const key = await importPrivateKey(fcm.private_key);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const assertion = `${header}.${claims}.${b64url(sig)}`;

  const res = await fetch(fcm.token_uri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const data = await res.json();
  if (!data.access_token) throw new Error(`FCM auth failed: ${JSON.stringify(data).slice(0, 200)}`);
  // cache for the token's life minus 5 min
  await env.STATE.put(cacheKey, data.access_token, { expirationTtl: Math.max(60, (data.expires_in || 3600) - 300) });
  return data.access_token;
}

// Returns: "ok" | "UNREGISTERED" (device gone, drop user) | throws
async function pushFcm(fcm, deviceToken, title, body, data = {}) {
  const token = await getAccessToken(fcm);
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${fcm.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: {
        token: deviceToken,
        notification: { title, body },
        data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v)])),
      },
    }),
  });
  if (res.ok) return "ok";
  const err = await res.text();
  if (res.status === 404 || err.includes("UNREGISTERED") || err.includes("INVALID_ARGUMENT")) return "UNREGISTERED";
  throw new Error(`FCM send ${res.status}: ${err.slice(0, 200)}`);
}

async function pushNtfy(title, body) {
  const url = (env.NTFY_URL || "https://ntfy.sh").replace(/\/+$/, "");
  const topic = env.NTFY_TOPIC || "";
  if (!topic || topic.includes("CHANGE-ME")) return;
  await fetch(`${url}/${topic}`, { method: "POST", headers: { Title: title, Tags: "bell" }, body: body || "" });
}

// ---------------- Moodle client (mirrors the app's Moodle.kt) ----------------

async function moodleCall(site, token, fn, params = {}) {
  const body = new URLSearchParams({ wstoken: token, wsfunction: fn, moodlewsrestformat: "json" });
  for (const [k, v] of Object.entries(params)) {
    if (Array.isArray(v)) v.forEach((x, i) => body.append(`${k}[${i}]`, x ?? ""));
    else body.append(k, v ?? "");
  }
  const res = await fetch(`${site}/webservice/rest/server.php`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch (_) {
    // e.g. Cloudflare 522 HTML/error text from an unreachable origin
    throw new Error(`${fn}: HTTP ${res.status}: ${text.slice(0, 120).replace(/\s+/g, " ")}`);
  }
  if (data && data.exception) throw new Error(`${fn}: ${data.errorcode}: ${data.message}`);
  return data;
}

// ---------------- per-user poll ----------------

const MAX_SEEN = 300;

async function pollUser(id, u, fcm, log) {
  let st = (await env.STATE.get(`state:${id}`, "json")) || { seen: [], due: {}, fails: 0, outageNotified: false };
  st.fails = st.fails || 0;
  st.outageNotified = st.outageNotified || false;
  let pushed = 0;
  let siteError = null;

  // 1. popup notifications — failure here must not block the deadline check
  try {
    const notes = await moodleCall(u.site, u.token, "message_popup_get_popup_notifications", { limitnum: 20 });
    for (const n of (Array.isArray(notes) ? notes : [])) {
      if (st.seen.includes(n.id)) continue;
      st.seen.push(n.id);
      if (u.fcmToken) {
        const r = await pushFcm(fcm, u.fcmToken, n.subject || "Spectrum", (n.fullmessage || n.smallmessage || "").replace(/<[^>]+>/g, " ").slice(0, 300));
        if (r === "UNREGISTERED") return "drop";
        pushed++;
      }
    }
    if (st.seen.length > MAX_SEEN) st.seen = st.seen.slice(-MAX_SEEN);
  } catch (e) {
    siteError = `notifications: ${e.message}`;
  }

  // 2. deadlines in the next 48h (push once per event time)
  let events = null;
  try {
    const now = Math.floor(Date.now() / 1000);
    events = await moodleCall(u.site, u.token, "core_calendar_get_action_events_by_timesort", { timesortnow: now, limitnum: 50 });
    for (const e of (Array.isArray(events) ? events : [])) {
      if (!(e.timestart > now && e.timestart - now < 48 * 3600)) continue;
      const key = String(e.timestart);
      if (st.due[e.id] === key) continue;
      st.due[e.id] = key;
      const hours = Math.round((e.timestart - now) / 360) / 10;
      if (u.fcmToken) {
        const r = await pushFcm(fcm, u.fcmToken, `Due in ${hours}h: ${e.name}`, e.course ? e.course.fullname || "" : "", { eventId: String(e.id) });
        if (r === "UNREGISTERED") return "drop";
        pushed++;
      }
    }
    for (const k of Object.keys(st.due)) if (!events?.some?.(e => String(e.id) === k)) delete st.due[k];
  } catch (e) {
    siteError = siteError ? `${siteError}; deadlines: ${e.message}` : `deadlines: ${e.message}`;
  }

  // 3. outage handling: count consecutive failures, notify once, reset on success
  if (siteError) {
    st.fails++;
    if (st.fails >= 3 && !st.outageNotified) {
      if (u.fcmToken) {
        try {
          await pushFcm(fcm, u.fcmToken, "Spectrum unreachable", "Cloud push is paused: the Spectrum site has been unreachable for 30+ minutes. Local polling in the app is unaffected.", {});
        } catch (_) { /* best effort */ }
      }
      st.outageNotified = true;
    }
    await env.STATE.put(`state:${id}`, JSON.stringify(st), { expirationTtl: 30 * 24 * 3600 });
    log(`user ${id.slice(0, 8)}: pushed ${pushed}; ERROR ${siteError} (fail ${st.fails})`);
    return pushed;
  }
  if (st.fails > 0 || st.outageNotified) {
    st.fails = 0;
    st.outageNotified = false;
  }

  await env.STATE.put(`state:${id}`, JSON.stringify(st), { expirationTtl: 30 * 24 * 3600 });
  log(`user ${id.slice(0, 8)}: pushed ${pushed}`);
  return pushed;
}

// ---------------- cron ----------------

async function runPollAll() {
  const fcm = await env.STATE.get("fcm", "json");
  if (!fcm) return "FCM not configured";
  const users = await env.STATE.list({ prefix: "user:" });
  let summary = "";
  for (const key of users.keys) {
    const name = key.name;
    const u = await env.STATE.get(name, "json");
    if (!u) continue;
    try {
      await pollUser(name.slice(5), u, fcm, s => { summary += s + "; "; });
    } catch (e) {
      summary += `user ${name.slice(5, 13)} error: ${e.message}; `;
      if (e.message.includes("invalidtoken") || e.message.includes("accessexception")) {
        await env.STATE.delete(name); // dead Moodle token, drop the user
        summary += "(dropped) ";
      }
    }
  }
  return summary || "no users";
}

// ---------------- HTTP API (admin key on every route) ----------------

async function checkKey(req, url) {
  const adminKey = await env.STATE.get("adminKey");
  return adminKey && (req.headers.get("X-Admin-Key") === adminKey || url.searchParams.get("key") === adminKey);
}

async function handleRequest(req) {
  const url = new URL(req.url);
  const path = url.pathname;

  // /register is called by the app with the user's own admin-shared key
  if (path === "/register" && req.method === "POST") {
    if (!(await checkKey(req, url))) {
      console.log(`register: bad key (got "${(req.headers.get("X-Admin-Key") || url.searchParams.get("key") || "").slice(0, 8)}...")`);
      return new Response("unauthorized", { status: 401 });
    }
    const b = await req.json().catch(e => { console.log(`register: bad json: ${e.message}`); return {}; });
    if (!b.site || !b.moodleToken || !b.fcmToken) {
      console.log(`register: missing fields (site=${!!b.site} moodleToken=${!!b.moodleToken} fcmToken=${!!b.fcmToken})`);
      return new Response("need site, moodleToken, fcmToken", { status: 400 });
    }
    const id = await sha256Hex(b.moodleToken);
    await env.STATE.put(`user:${id}`, JSON.stringify({
      site: String(b.site).trim().replace(/\/+$/, ""),
      token: String(b.moodleToken).trim(),
      fcmToken: String(b.fcmToken).trim(),
    }));
    return new Response("registered");
  }

  if (!(await checkKey(req, url))) return new Response("unauthorized", { status: 401 });

  if (path === "/fcm" && req.method === "POST") {
    const b = await req.json();
    // accepts the service-account JSON fields directly
    await env.STATE.put("fcm", JSON.stringify({
      project_id: b.project_id, client_email: b.client_email,
      private_key: b.private_key, token_uri: b.token_uri || "https://oauth2.googleapis.com/token",
    }));
    return new Response("fcm configured");
  }
  if (path === "/admin" && req.method === "POST") {
    const b = await req.json();
    if (!b.adminKey) return new Response("need adminKey", { status: 400 });
    await env.STATE.put("adminKey", String(b.adminKey).trim());
    return new Response("admin key set");
  }
  if (path === "/poll" && req.method === "POST") return new Response(await runPollAll());
  if (path === "/probe" && req.method === "POST") {
    const u = await env.STATE.get("user:" + url.searchParams.get("user"), "json");
    const site = u?.site || url.searchParams.get("site");
    if (!site) return new Response("no site", { status: 400 });
    const out = [];
    for (const attempt of [1, 2, 3]) {
      const t0 = Date.now();
      try {
        const r = await fetch(`${site}/login/index.php`, { method: "GET", redirect: "manual" });
        out.push(`try${attempt}: HTTP ${r.status} in ${Date.now() - t0}ms`);
      } catch (e) {
        out.push(`try${attempt}: fetch threw after ${Date.now() - t0}ms: ${e.message}`);
      }
    }
    return new Response(out.join("\n"));
  }
  if (path === "/status") {
    const users = await env.STATE.list({ prefix: "user:" });
    return new Response(JSON.stringify({
      fcm: !!(await env.STATE.get("fcm")),
      adminKey: !!(await env.STATE.get("adminKey")),
      users: users.keys.length,
    }));
  }
  return new Response("routes: POST /register, POST /fcm, POST /admin, POST /poll, GET /status");
}

export default {
  async scheduled(e, eenv) {
    env = eenv;
    console.log(await runPollAll());
  },
  async fetch(req, eenv) {
    env = eenv;
    try {
      return await handleRequest(req);
    } catch (e) {
      return new Response(`error: ${e.message}`, { status: 500 });
    }
  },
};
