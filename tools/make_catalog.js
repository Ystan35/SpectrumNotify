// Makes app/src/main/assets/catalog.json (what the app bundles) from cleaner.js's timetable_data.json.
// Usage: node tools/make_catalog.js <timetable_data.json> [yearPeriod=2026/S1] [output=app/src/main/assets/catalog.json]
// Keeps LEC / TUT / LAB only. Drops lecturer emails, ids and job titles (name only).
const fs = require('fs');
const DAY = { Monday: 1, Tuesday: 2, Wednesday: 3, Thursday: 4, Friday: 5, Saturday: 6, Sunday: 7 };
const mins = (s) => { const [h, m] = s.split(':'); return parseInt(h, 10) * 60 + parseInt(m, 10); };
const title = (s) => (s || '').toLowerCase().replace(/(^|[\s(/-])([a-z])/g, (_, a, b) => a + b.toUpperCase());

const [input, period = '2026/S1', output = 'app/src/main/assets/catalog.json'] = process.argv.slice(2);
if (!input) { console.error('Usage: node tools/make_catalog.js <timetable_data.json> [yearPeriod] [output]'); process.exit(1); }

const data = JSON.parse(fs.readFileSync(input, 'utf8'));
const courses = {};
let skipped = 0;
for (const m of data) {
  if (m.yearPeriod !== period) continue;
  const code = (m.moduleCode || '').trim().toUpperCase();
  if (!code) continue;
  const c = courses[code] || (courses[code] = { n: title(m.moduleName), LEC: [], TUT: [], LAB: [] });
  for (const k of ['LEC', 'TUT', 'LAB']) {
    for (const a of (m.activities && m.activities[k]) || []) {
      if (!DAY[a.dayOfWeek] || !a.occurrences || !a.occurrences.length || !a.startTime || !a.endTime) { skipped++; continue; }
      c[k].push([DAY[a.dayOfWeek], mins(a.startTime), mins(a.endTime), a.room || '', a.occurrences.map(String), title(a.lecturer && a.lecturer.fullName)]);
    }
  }
}
for (const code of Object.keys(courses)) {
  const c = courses[code];
  for (const k of ['LEC', 'TUT', 'LAB']) if (!c[k].length) delete c[k];
  if (!c.LEC && !c.TUT && !c.LAB) delete courses[code];
}
fs.writeFileSync(output, JSON.stringify({ semester: period, courses }));
console.log(`${Object.keys(courses).length} courses (${period}), ${skipped} incomplete slots skipped -> ${output}`);
