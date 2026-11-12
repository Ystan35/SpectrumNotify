// Run: node pwa/test.js  (uses a small catalog in the same shape as app/src/main/assets/catalog.json)
const assert = require("assert");
const { occsOf, classesFor, nextClass, search, hm } = require("./core.js");

const catalog = {
  courses: {
    WIX1003: { n: "Computer Systems And Organization", LEC: [[3, 480, 600, "DK2", ["4", "5", "6"], "A"]], TUT: [[4, 720, 780, "MM4", ["6"], "B"], [4, 600, 660, "MM4", ["5"], "B"]] },
    GIG1017: { n: "Philosophy And Current Affairs", LEC: [[1, 1020, 1140, "X", ["1", "1M"], ""]] },
  },
};

assert.deepStrictEqual(occsOf(catalog.courses.WIX1003), ["4", "5", "6"]);
assert.deepStrictEqual(occsOf(catalog.courses.GIG1017), ["1", "1M"]);

// only my Occ's slots: Occ 6 gets the 12:00 tutorial, not the 10:00 one
const mine = classesFor(catalog, { WIX1003: "6", GIG1017: "1" });
assert.deepStrictEqual(mine.map((c) => [c.code, c.kind, c.day, hm(c.start)]), [
  ["GIG1017", "Lecture", 1, "17:00"], ["WIX1003", "Lecture", 3, "08:00"], ["WIX1003", "Tutorial", 4, "12:00"],
]);
assert.strictEqual(classesFor(catalog, { GONE1234: "1" }).length, 0); // a course the catalog dropped

// next class: Wednesday 09:00 is inside the 08:00-10:00 lecture; Wednesday 11:00 -> Thursday tutorial
assert.strictEqual(nextClass(mine, 3, 540).cls.kind, "Lecture");
assert.strictEqual(nextClass(mine, 3, 660).cls.kind, "Tutorial");
assert.strictEqual(nextClass(mine, 3, 660).daysAhead, 1);
// Friday after everything: wraps to Monday's lecture, 3 days ahead
const n = nextClass(mine, 5, 600);
assert.strictEqual(n.cls.code, "GIG1017");
assert.strictEqual(n.daysAhead, 3);
assert.strictEqual(nextClass([], 1, 0), null);

assert.deepStrictEqual(search(catalog, "wix1003"), ["WIX1003"]);
assert.deepStrictEqual(search(catalog, "philosophy affairs"), ["GIG1017"]);
assert.deepStrictEqual(search(catalog, "   "), []);
// laptop week grid: overlapping classes share the column; touching ones do not
const { lanes, hourRange, visibleDays, hueMap } = require("./core.js");
const two = lanes([{ start: 600, end: 720 }, { start: 660, end: 780 }, { start: 780, end: 840 }]);
assert.deepStrictEqual(two.map((p) => [p.lane, p.lanes]), [[0, 2], [1, 2], [0, 1]]); // 11:00 overlaps 10:00; 13:00 only touches 13:00
assert.deepStrictEqual(lanes([]), []);
assert.deepStrictEqual(lanes([{ start: 0, end: 60 }, { start: 0, end: 60 }, { start: 0, end: 60 }]).map((p) => p.lane), [0, 1, 2]);

assert.deepStrictEqual(hourRange([]), { from: 8, to: 17 });
assert.deepStrictEqual(hourRange([{ start: 450, end: 600 }, { start: 1020, end: 1230 }]), { from: 7, to: 21 }); // 07:30 and 20:30 widen it
assert.deepStrictEqual(visibleDays([{ day: 2 }]), [1, 2, 3, 4, 5]);
assert.deepStrictEqual(visibleDays([{ day: 6 }]), [1, 2, 3, 4, 5, 6]);
// colours: every course differs by at least 30 degrees (checked round the colour circle), and the order of the list does not matter
const codes = ["WIX1003", "WIX1001", "WIA2010", "GIG1017", "GUL1001", "WIX1002", "GIG1003", "WIX2001"];
const hues = Object.values(hueMap(codes));
for (let i = 0; i < hues.length; i++) for (let j = i + 1; j < hues.length; j++) {
  const d = Math.abs(hues[i] - hues[j]); assert.ok(Math.min(d, 360 - d) >= 30, "colours too close: " + hues[i] + " / " + hues[j]);
}
assert.deepStrictEqual(hueMap(["B", "A"]), hueMap(["A", "B", "A"]));
console.log("pwa core: all checks passed");
