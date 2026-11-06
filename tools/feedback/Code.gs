/**
 * Spectrum Notify feedback: tickets from the app become issues in a private GitHub repo.
 *
 *   POST  {ticket, kind, message, app, device, install}  -> creates the issue (once per ticket), emails you a copy
 *   GET   ?ids=SN-AB12,SN-CD34                            -> {"SN-AB12": {status, version, reply}, ...}
 *
 * Status comes from the issue: open = received, label "in-progress" = working, closed with label
 * "fixed-in:0.33.0" = fixed (version 0.33.0), closed as not planned = closed. A comment starting with
 * "Reply:" is sent to the app as your answer.
 *
 * Script properties (Project Settings): GH_TOKEN (fine-grained, Issues read/write on the feedback repo only),
 * GH_REPO ("owner/name"), optional NOTIFY_EMAIL (defaults to the account running the script).
 */

var KINDS = ["bug", "idea", "question", "assistant"];
var PER_DAY = 10;           // tickets per phone per day
var MAX_LEN = 2000;
var MAX_IMAGES = 3;
var MAX_IMAGE = 2 * 1024 * 1024;  // bytes, after decoding

function doPost(e) {
  try {
    var b = JSON.parse((e && e.postData && e.postData.contents) || "{}");
    var bad = validate(b);
    if (bad) return json({ ok: false, error: bad });
    var props = PropertiesService.getScriptProperties();
    // one ticket at a time: saving screenshots takes seconds, and a second copy of the same ticket arriving
    // meanwhile passed the check below and became a duplicate issue (SN-TG4DM, #15 and #16)
    var lock = LockService.getScriptLock();
    lock.waitLock(60000);
    try {
      return createTicket(b, props);
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    console.error(err);
    return json({ ok: false, error: "Could not send now. It will be sent again later." });
  }
}

function createTicket(b, props) {
    if (props.getProperty("t:" + b.ticket) || findIssue(b.ticket)) return json({ ok: true, ticket: b.ticket });  // a retry from the offline queue
    // shortcut: the daily count lives in the cache (kept up to 6 h), so a phone could send 10 more after that; use properties if abused
    var cache = CacheService.getScriptCache();
    var key = "n:" + b.install + ":" + Utilities.formatDate(new Date(), "Asia/Kuala_Lumpur", "yyyyMMdd");
    var n = Number(cache.get(key) || 0);
    if (n >= PER_DAY) return json({ ok: false, error: "Too many tickets today. Try again tomorrow." });

    var msg = redact(b.message);
    var title = "[" + b.ticket + "] " + cap(b.kind) + ": " + msg.replace(/\s+/g, " ").slice(0, 60);
    var body = msg + "\n\n---\nTicket: " + b.ticket + "\nApp: " + clean(b.app) + "\nDevice: " + clean(b.device);
    // screenshots: checked to be real images, saved in the repo, shown in the issue and attached to the email
    var blobs = [];
    (b.images || []).forEach(function (s, i) {
      var bytes = Utilities.base64Decode(s);
      var type = imageType(bytes);
      if (!type || bytes.length > MAX_IMAGE) return;
      var name = b.ticket + "-" + (i + 1) + (type === "image/png" ? ".png" : ".jpg");
      body += "\n\n![Screenshot " + (i + 1) + "](" + saveImage(name, s) + ")";
      blobs.push(Utilities.newBlob(bytes, type, name));
    });
    var issue = gh("post", "/issues", { title: title, body: body, labels: [b.kind] });
    props.setProperty("t:" + b.ticket, String(issue.number));
    cache.put(key, String(n + 1), 21600);

    var to = props.getProperty("NOTIFY_EMAIL") || Session.getEffectiveUser().getEmail();
    MailApp.sendEmail(to, "Spectrum Notify " + title, body + "\n\n" + issue.html_url, { attachments: blobs });
    return json({ ok: true, ticket: b.ticket });
}

function doGet(e) {
  var ids = String((e && e.parameter && e.parameter.ids) || "").split(",")
    .filter(function (t) { return /^SN-[A-Z0-9]{4,8}$/.test(t); }).slice(0, 20);
  var props = PropertiesService.getScriptProperties();
  var cache = CacheService.getScriptCache();
  var out = {};
  ids.forEach(function (t) {
    var hit = cache.get("s:" + t);
    if (hit) { out[t] = JSON.parse(hit); return; }
    var num = props.getProperty("t:" + t) || findIssue(t);
    if (!num) { out[t] = { status: "unknown" }; return; }
    props.setProperty("t:" + t, String(num));
    var issue = gh("get", "/issues/" + num);
    var comments = issue.state === "closed" && issue.comments > 0 ? gh("get", "/issues/" + num + "/comments?per_page=100") : [];
    out[t] = statusOf(issue, comments);
    console.log(t + " -> issue #" + num + " " + issue.state + " (" + (issue.state_reason || "") + "), labels " + (issue.labels || []).map(function (l) { return l.name; }).join(","));
    cache.put("s:" + t, JSON.stringify(out[t]), 120);  // GitHub is asked at most every 2 min per ticket
  });
  return json(out);
}

// ---------------------------------------------------------------- pure parts (tested in test_code.js)

function validate(b) {
  if (!/^SN-[A-Z0-9]{4,8}$/.test(b.ticket || "")) return "bad ticket";
  if (KINDS.indexOf(b.kind) < 0) return "bad kind";
  if (typeof b.message !== "string" || !b.message.trim() || b.message.length > MAX_LEN) return "message empty or too long";
  if (!/^[a-f0-9]{16,64}$/.test(b.install || "")) return "bad install id";
  if (b.images !== undefined && (!Array.isArray(b.images) || b.images.length > MAX_IMAGES ||
      b.images.some(function (s) { return typeof s !== "string" || s.length > MAX_IMAGE * 4 / 3 + 4 || !/^[A-Za-z0-9+/=]+$/.test(s); })))
    return "bad images";
  return null;
}

/** The app checks too; this catches anything that looks like a password, token or key before it reaches GitHub. */
function redact(s) {
  return s
    .replace(/\b(password|pass|pwd|token|key|secret)\s*[:=]\s*\S+/gi, "$1: [removed]")
    .replace(/\b[A-Za-z0-9_\-+\/=]{32,}\b/g, "[removed]");
}

function statusOf(issue, comments) {
  var labels = (issue.labels || []).map(function (l) { return typeof l === "string" ? l : l.name; });
  var reply = null;
  (comments || []).forEach(function (c) { var m = /^Reply:\s*([\s\S]+)/.exec((c.body || "").trim()); if (m) reply = m[1].trim(); });
  if (issue.state !== "closed") return { status: labels.indexOf("in-progress") >= 0 ? "working" : "received" };
  if (issue.state_reason === "not_planned") return { status: "closed", reply: reply };
  var v = null;
  labels.forEach(function (l) { var m = /^fixed-in:(\S+)$/.exec(l); if (m) v = m[1]; });
  return { status: "fixed", version: v, reply: reply };
}

/** "image/jpeg" or "image/png" from the file's first bytes (not from what the sender claims), else null. */
function imageType(bytes) {
  var b = function (i) { return bytes[i] & 0xff; };  // Apps Script bytes are signed
  if (bytes.length > 3 && b(0) === 0xff && b(1) === 0xd8 && b(2) === 0xff) return "image/jpeg";
  if (bytes.length > 8 && b(0) === 0x89 && b(1) === 0x50 && b(2) === 0x4e && b(3) === 0x47) return "image/png";
  return null;
}

function clean(s) { return String(s || "").replace(/[^\w .,()\-\/:]/g, "").slice(0, 80); }
function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

// ---------------------------------------------------------------- GitHub and output

/**
 * The issue number for a ticket from its title ("[SN-AB12] ..."), when the script's own record is gone
 * (saving the Script properties page replaces the whole list). Reads the repo's issues, newest first, and
 * records every ticket it passes, so one lookup restores them all. Null when there is none.
 */
function findIssue(ticket) {
  var props = PropertiesService.getScriptProperties();
  for (var page = 1; page <= 10; page++) {
    var list = gh("get", "/issues?state=all&per_page=100&sort=created&direction=desc&page=" + page);
    var found = null;
    list.forEach(function (i) {
      var m = /^\[(SN-[A-Z0-9]{4,8})\]/.exec(i.title || "");
      if (!m || i.pull_request) return;
      props.setProperty("t:" + m[1], String(i.number));
      if (m[1] === ticket) found = i.number;
    });
    if (found || list.length < 100) return found;
  }
  return null;
}

function gh(method, path, payload) {
  var p = PropertiesService.getScriptProperties();
  var res = UrlFetchApp.fetch("https://api.github.com/repos/" + p.getProperty("GH_REPO") + path, {
    method: method,
    contentType: "application/json",
    headers: { Authorization: "Bearer " + p.getProperty("GH_TOKEN"), Accept: "application/vnd.github+json" },
    payload: payload ? JSON.stringify(payload) : undefined,
    muteHttpExceptions: true,
  });
  if (res.getResponseCode() >= 300) throw new Error("GitHub " + res.getResponseCode() + ": " + res.getContentText().slice(0, 200));
  return JSON.parse(res.getContentText());
}

/** Saves a screenshot as attachments/<name> in the feedback repo; returns its link (shown in the issue to you only). */
function saveImage(name, base64) {
  var path = "/contents/attachments/" + name;
  try {
    return gh("put", path, { message: "Screenshot " + name, content: base64 }).content.html_url + "?raw=true";
  } catch (err) {
    // a retry after a failure part-way: the file is already there
    return gh("get", path).html_url + "?raw=true";
  }
}

function json(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }

if (typeof module !== "undefined") module.exports = { validate: validate, redact: redact, statusOf: statusOf, imageType: imageType };
