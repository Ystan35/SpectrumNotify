// Pure rules of the PWA (no browser APIs), so `node pwa/test.js` can check them.
// Data shape = app/src/main/assets/catalog.json: courses[code] = { n: name, LEC|TUT|LAB: [[day 1=Mon, startMin, endMin, room, [occs], lecturer], ...] }
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const KIND = { LEC: "Lecture", TUT: "Tutorial", LAB: "Lab" };

const hm = (m) => String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");

/** Every Occ a course has, numbers first. */
function occsOf(course) {
  const all = new Set();
  for (const k of Object.keys(KIND)) for (const s of course[k] || []) s[4].forEach((o) => all.add(o));
  return [...all].sort((a, b) => (parseInt(a, 10) || 1e9) - (parseInt(b, 10) || 1e9) || a.localeCompare(b));
}

/** picks = { CODE: occ }. Your weekly classes, by day then start time. */
function classesFor(catalog, picks) {
  const out = [];
  for (const [code, occ] of Object.entries(picks)) {
    const c = catalog.courses[code];
    if (!c) continue; // a course the newer catalog no longer lists
    for (const k of Object.keys(KIND))
      for (const [day, start, end, room, occs, lecturer] of c[k] || [])
        if (occs.includes(occ)) out.push({ code, name: c.n, kind: KIND[k], day, start, end, room, lecturer });
  }
  return out.sort((a, b) => a.day - b.day || a.start - b.start);
}

/** The class running now or starting next: { cls, daysAhead } (0 = today), or null when there are no classes. */
function nextClass(classes, day, minute) {
  let best = null;
  for (const c of classes) {
    const ahead = (c.day - day + 7) % 7;
    if (ahead === 0 && c.end <= minute) continue; // already over today: next time is a week away
    const score = ahead * 1440 + (ahead === 0 ? 0 : c.start);
    if (!best || score < best.score) best = { cls: c, daysAhead: ahead, score };
  }
  if (!best) {
    // everything today is over: the earliest class of the coming week
    const c = [...classes].sort((a, b) => ((a.day - day + 7) % 7 || 7) - ((b.day - day + 7) % 7 || 7) || a.start - b.start)[0];
    return c ? { cls: c, daysAhead: (c.day - day + 7) % 7 || 7 } : null;
  }
  return { cls: best.cls, daysAhead: best.daysAhead };
}

/** Courses whose code or name contains every word of the search. */
function search(catalog, text, limit = 8) {
  const words = text.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const out = [];
  for (const [code, c] of Object.entries(catalog.courses)) {
    const hay = (code + " " + c.n).toLowerCase();
    if (words.every((w) => hay.includes(w))) out.push(code);
    if (out.length >= limit) break;
  }
  return out;
}

/**
 * Side-by-side placement for one day's classes (sorted by start): classes that overlap in time share the column in lanes,
 * the same rule as the app's Week view. Returns [{ cls, lane, lanes }].
 */
function lanes(list) {
  const out = [];
  let i = 0;
  while (i < list.length) {
    let j = i, end = list[i].end;
    const ends = [], placed = [];
    while (j < list.length && list[j].start < end) {
      const c = list[j];
      let lane = ends.findIndex((e) => e <= c.start);
      if (lane < 0) { ends.push(c.end); lane = ends.length - 1; } else ends[lane] = c.end;
      placed.push({ cls: c, lane });
      end = Math.max(end, c.end);
      j++;
    }
    for (const p of placed) out.push({ cls: p.cls, lane: p.lane, lanes: ends.length });
    i = j;
  }
  return out;
}

/** The hours the week grid shows: at least 08:00-17:00, wider when a class falls outside. { from, to } in whole hours. */
function hourRange(classes) {
  let from = 8, to = 17;
  for (const c of classes) { from = Math.min(from, Math.floor(c.start / 60)); to = Math.max(to, Math.ceil(c.end / 60)); }
  return { from, to };
}

/** The days the grid shows: Mon-Fri always, Saturday and Sunday only when they have a class. */
function visibleDays(classes) {
  return [1, 2, 3, 4, 5, 6, 7].filter((d) => d <= 5 || classes.some((c) => c.day === d));
}

/**
 * One colour per course, spread so no two of your courses look alike: the golden angle (137.5 degrees) between neighbours.
 * Courses are taken in code order, so a course keeps its colour while the set stays the same.
 */
function hueMap(codes) {
  const map = {};
  [...new Set(codes)].sort().forEach((code, i) => { map[code] = Math.round((i * 137.508 + 150) % 360); });
  return map;
}

if (typeof module !== "undefined") module.exports = { DAYS, KIND, hm, occsOf, classesFor, nextClass, search, lanes, hourRange, visibleDays, hueMap };
