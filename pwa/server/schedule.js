// What to notify, decided from plain data (no network, no clock): so `node pwa/test.js` can check it.
// Times are Malaysia time (UTC+8, no daylight saving): UM's timetable is in it, whatever the server's time zone.
const { classesFor, hm, DAYS } = require("../core.js");
const CALENDAR = require("./calendar.json");

const MYT_MS = 8 * 3600 * 1000;

/** { date: "2026-11-12", day: 4 (Mon = 1), minute: 590 } for a UTC instant, in Malaysia time. */
function myt(nowMs) {
  const t = new Date(nowMs + MYT_MS);
  return { date: t.toISOString().slice(0, 10), day: (t.getUTCDay() + 6) % 7 + 1, minute: t.getUTCHours() * 60 + t.getUTCMinutes() };
}

/** Is [date] a teaching day: inside a semester's lectures, not in its mid-semester or other break. Same dates as the app's AcademicCalendar. */
function teaching(date, calendar = CALENDAR) {
  const within = (a, b) => a && b && date >= a && date <= b;
  return calendar.some((s) => within(s.teachStart, s.teachEnd) && !within(s.breakStart, s.breakEnd) && !(s.extra || []).some(([a, b]) => within(a, b)));
}

const WINDOW = 6; // minutes after the due moment in which a late tick still sends (the pinger is not exact); once only, by the key

/**
 * sub = { picks: {CODE: occ}, classes: bool, lead: minutes, morning: "07:05" | "off" }.
 * Returns [{ key, title, body, tag }]: each key is unique per day and event, so a repeated tick never sends twice.
 */
function due(sub, catalog, nowMs) {
  const now = myt(nowMs);
  if (!teaching(now.date)) return [];
  const today = classesFor(catalog, sub.picks || {}).filter((c) => c.day === now.day);
  const out = [];

  if (sub.classes !== false) {
    for (const c of today) {
      const untilStart = c.start - now.minute;
      if (untilStart <= sub.lead && untilStart > sub.lead - WINDOW && untilStart > 0) {
        out.push({
          key: [now.date, "class", c.code, c.kind, c.start].join("|"),
          title: c.code + " " + c.kind + " in " + untilStart + " min",
          body: hm(c.start) + "–" + hm(c.end) + (c.room ? " · " + c.room : "") + (c.lecturer ? " · " + c.lecturer : ""),
          tag: "class-" + c.code + "-" + c.start,
        });
      }
    }
  }

  if (sub.morning && sub.morning !== "off" && today.length) {
    const [h, m] = sub.morning.split(":").map(Number);
    const at = h * 60 + m, since = now.minute - at;
    if (since >= 0 && since < WINDOW) {
      out.push({
        key: [now.date, "morning"].join("|"),
        title: "Today: " + today.length + " class" + (today.length === 1 ? "" : "es"),
        body: today.map((c) => c.code + " " + hm(c.start) + (c.room ? " " + c.room : "")).join(" · "),
        tag: "morning",
      });
    }
  }
  return out;
}

module.exports = { myt, teaching, due, DAYS, WINDOW };
