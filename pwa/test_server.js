// Run: node pwa/test_server.js   (no network: a fake store, a fake sender and a fixed clock)
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const { myt, teaching, due } = require("./server/schedule.js");
const { makeApp, validate } = require("./server/app.js");
const { memory } = require("./server/store.js");
const CALENDAR = require("./server/calendar.json");

const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, "../app/src/main/assets/catalog.json"), "utf8")); // the real UM list
const at = (iso) => Date.parse(iso); // iso with +08:00: Malaysia time

// ---- the web calendar is the Android app's calendar: read AcademicCalendar.kt and compare
{
  const kt = fs.readFileSync(path.join(__dirname, "../app/src/main/java/my/spectrum/notify/AcademicCalendar.kt"), "utf8");
  const block = kt.slice(kt.indexOf("fun bundled()"), kt.indexOf("fun defaults()"));
  const rows = [...block.matchAll(/Dates\("([^"]+)",\s*d\("([^"]+)"\),\s*d\("([^"]+)"\),\s*(d\("([^"]+)"\)|null),\s*(d\("([^"]+)"\)|null)/g)];
  assert.strictEqual(rows.length, CALENDAR.length, "same number of semesters as the app");
  rows.forEach((m, i) => {
    const w = CALENDAR[i];
    assert.deepStrictEqual([w.label, w.teachStart, w.teachEnd, w.breakStart, w.breakEnd], [m[1], m[2], m[3], m[5] || null, m[7] || null], "semester " + m[1] + " differs from AcademicCalendar.kt");
  });
  const extras = [...block.matchAll(/Pair\(d\("([^"]+)"\), d\("([^"]+)"\)\)/g)].map((m) => [m[1], m[2]]);
  assert.deepStrictEqual(CALENDAR.flatMap((s) => s.extra), extras, "other no-class periods differ from AcademicCalendar.kt");
}

// ---- time and teaching days
assert.deepStrictEqual(myt(at("2026-11-12T09:50:00+08:00")), { date: "2026-11-12", day: 4, minute: 590 }); // Thursday
assert.deepStrictEqual(myt(at("2026-11-12T00:30:00+08:00")), { date: "2026-11-12", day: 4, minute: 30 });  // after midnight is still that day in Malaysia
assert.strictEqual(teaching("2026-11-12"), true);
assert.strictEqual(teaching("2026-12-01"), false);  // mid-semester break
assert.strictEqual(teaching("2026-10-04"), false);  // before lectures
assert.strictEqual(teaching("2027-01-18"), false);  // revision week
assert.strictEqual(teaching("2027-06-09"), false);  // an extra break inside Sem 2

// ---- what is due: WIX1003 Occ 6 has the Thursday 12:00 tutorial in MM4 (see the real catalog)
const mine = { picks: { WIX1003: "6" }, classes: true, lead: 10, morning: "off" };
let d = due(mine, catalog, at("2026-11-12T11:50:00+08:00"));
assert.strictEqual(d.length, 1);
assert.strictEqual(d[0].title, "WIX1003 Tutorial in 10 min");
assert.ok(d[0].body.startsWith("12:00–13:00 · MM4"), d[0].body);
assert.deepStrictEqual(due(mine, catalog, at("2026-11-12T11:44:00+08:00")), [], "16 minutes before: too early");
assert.strictEqual(due(mine, catalog, at("2026-11-12T11:55:00+08:00")).length, 1, "a tick five minutes late still sends");
assert.deepStrictEqual(due(mine, catalog, at("2026-11-12T11:57:00+08:00")), [], "three minutes before: the moment has passed");
assert.strictEqual(due(mine, catalog, at("2026-11-12T11:50:00+08:00"))[0].key, due(mine, catalog, at("2026-11-12T11:54:00+08:00"))[0].key, "same event, same key: sent once");
assert.deepStrictEqual(due(mine, catalog, at("2026-12-03T11:50:00+08:00")), [], "Thursday in the mid-semester break");
assert.deepStrictEqual(due({ ...mine, classes: false }, catalog, at("2026-11-12T11:50:00+08:00")), [], "class reminders switched off");
assert.strictEqual(due({ ...mine, lead: 30 }, catalog, at("2026-11-12T11:30:00+08:00")).length, 1, "a 30-minute lead");
assert.deepStrictEqual(due({ picks: { WIX1003: "6" }, lead: 10 }, catalog, at("2026-11-14T11:50:00+08:00")), [], "no class on Saturday");

const morning = { ...mine, classes: false, morning: "07:05" };
d = due(morning, catalog, at("2026-11-12T07:05:00+08:00"));
assert.strictEqual(d.length, 1);
assert.strictEqual(d[0].title, "Today: 1 class");
assert.ok(d[0].body.includes("WIX1003 12:00 MM4"), d[0].body);
assert.deepStrictEqual(due(morning, catalog, at("2026-11-12T07:20:00+08:00")), [], "the morning summary is a once-a-day moment");
assert.deepStrictEqual(due(morning, catalog, at("2026-11-14T07:05:00+08:00")), [], "nothing to summarise on a day without classes");
assert.deepStrictEqual(due({ ...morning, morning: "off" }, catalog, at("2026-11-12T07:05:00+08:00")), []);

// ---- what the server accepts
const sub = { endpoint: "https://fcm.googleapis.com/fcm/send/abc", keys: { p256dh: "p", auth: "a" } };
assert.ok(validate({ subscription: sub, picks: { WIX1003: "6" } }, catalog).record);
assert.strictEqual(validate({ subscription: { ...sub, endpoint: "https://evil.example/x" }, picks: {} }, catalog).error, "unknown push service");
assert.strictEqual(validate({ subscription: { ...sub, endpoint: "http://fcm.googleapis.com/x" }, picks: {} }, catalog).error, "unknown push service");
assert.strictEqual(validate({ subscription: { ...sub, endpoint: "https://fcm.googleapis.com.evil.example/x" }, picks: {} }, catalog).error, "unknown push service");
assert.ok(validate({ subscription: { ...sub, endpoint: "https://web.push.apple.com/Qabc" }, picks: {} }, catalog).record);
assert.ok(validate({ subscription: { ...sub, endpoint: "https://updates.push.services.mozilla.com/wpush/v2/x" }, picks: {} }, catalog).record);
assert.ok(validate({ subscription: { ...sub, endpoint: "https://wns2-par02p.notify.windows.com/w/?token=x" }, picks: {} }, catalog).record);
assert.deepStrictEqual(validate({ subscription: sub, picks: { WIX1003: "99", NOPE9999: "1", WIX1001: "2" } }, catalog).record.picks, { WIX1001: "2" }, "unknown course and Occ are dropped");
const clean = validate({ subscription: sub, picks: {}, lead: 7, morning: "25:00" }, catalog).record;
assert.deepStrictEqual([clean.lead, clean.morning], [10, "07:05"]);
assert.strictEqual(validate({ subscription: sub, picks: {}, pad: "x".repeat(7000) }, catalog).error, "too big");

// ---- the app: subscribe, tick (secret, once only, expired subscriptions), unsubscribe
(async () => {
  const store = memory();
  const sent = [];
  let clock = at("2026-11-12T11:50:00+08:00");
  let reply = "ok";
  const app = makeApp({ store, getCatalog: async () => catalog, now: () => clock, tickSecret: "s3cret", send: async (s, p) => { sent.push(p.title); return reply; } });

  let r = await app.subscribe({ subscription: sub, picks: { WIX1003: "6" }, lead: 10, morning: "off" });
  assert.deepStrictEqual([r.status, r.body.courses], [200, 1]);
  assert.strictEqual((await app.subscribe({ subscription: { ...sub, endpoint: "https://evil.example/x" }, picks: {} })).status, 400);

  assert.strictEqual((await app.tick("wrong")).status, 401);
  assert.strictEqual((await app.tick("")).status, 401);
  assert.strictEqual((await makeApp({ store, getCatalog: async () => catalog, send: async () => "ok" }).tick("anything")).status, 401, "no secret configured: nobody gets in");

  r = await app.tick("s3cret");
  assert.deepStrictEqual(r.body, { subscriptions: 1, sent: 1, removed: 0, failed: 0 });
  clock += 60000;
  r = await app.tick("s3cret"); // the next minute's tick: already sent
  assert.strictEqual(r.body.sent, 0);
  assert.deepStrictEqual(sent, ["WIX1003 Tutorial in 10 min"]);

  // the phone uninstalled the app: the push service answers 410 and the subscription is forgotten
  reply = "gone"; clock = at("2026-11-19T11:50:00+08:00"); // next Thursday
  r = await app.tick("s3cret");
  assert.deepStrictEqual([r.body.removed, await store.count()], [1, 0]);

  // "send me a test notice": only for a known device, once a minute
  await app.subscribe({ subscription: sub, picks: {} });
  reply = "ok"; sent.length = 0;
  assert.strictEqual((await app.test({ endpoint: "https://fcm.googleapis.com/fcm/send/unknown" })).status, 404);
  assert.strictEqual((await app.test({})).status, 400);
  assert.strictEqual((await app.test({ endpoint: sub.endpoint })).status, 200);
  assert.strictEqual((await app.test({ endpoint: sub.endpoint })).status, 429);
  clock += 60000;
  assert.strictEqual((await app.test({ endpoint: sub.endpoint })).status, 200);
  assert.deepStrictEqual(sent, ["Test: reminders work", "Test: reminders work"]);
  assert.strictEqual(await store.count(), 1);
  await app.unsubscribe({ endpoint: sub.endpoint });
  assert.strictEqual(await store.count(), 0);
  console.log("pwa server: all checks passed");
})().catch((e) => { console.error(e); process.exit(1); });
