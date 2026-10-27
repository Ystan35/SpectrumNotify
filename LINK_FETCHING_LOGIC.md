# Spectrum Notify — Link Fetching & Resolution Logic
(Reference document, generated 2026-10-27)

## 1. CAPTURE — where meeting/attendance links come from

Every ~10 minutes, `Checker.checkCourses` walks each enrolled course via Moodle's
`core_course_get_contents`. Four sources are captured:

| Source | Links found by | Stored label | Tokens (for matching) |
|---|---|---|---|
| Section summary (HTML description at the top of each Moodle section) | regex `MEETING_RE` (teams/zoom/meet/webex/bbb…) | `section: <section name>` | groupTokens(section name) — e.g. "Week 3 Occ 2" → {occ2} |
| URL-type modules ("Join Lecture" resources) | module `url` + links in its description | module name, e.g. "Lecture Occ 3" | groupTokens(module name) |
| Forum announcements | links in the post | `announcement: <post name>` | groupTokens(post name) |
| Attendance modules | module id (cmid) for `mod/attendance/view.php` | — | groupTokens("Attendance Occ 3" + intro) → {occ3} |

All raw links are **deduped by URL**: same URL seen again merges its tokens with the earlier
entry. Result stored per course id in state JSON key `"links"`.

Attendance modules are stored **per occ**: every attendance cmid with its tokens goes into
state key `"attcmids"` (format `"courseId:cmid"` → token array). Spectrum lists one attendance
module per occ, so first-wins was wrong — this is why the wrong-occ bug happened.

## 2. RESOLUTION — which link a card shows

### Spectrum-event classes (hero card slides)
`linksForEvent(event, cal, links)`:
1. Links found in the **event's own description** score 100 (highest — always win).
2. Stored course links score by `link.tokens ∩ event tokens` (the event name, e.g.
   "Lecture Occ 3", gives {occ3}; learned group tokens from your attendance history are added).
3. Ties keep the newest. Top-score group only — lower scores are dropped.

This path was always correct — it's why the hero card topic line showed the right link.

### Manual/catalog slots + widget + course sheet + Attendance pill
`linksForCourse(code, courseId, extraTokens)`:
1. **Attendance**: pasted link (Settings → your class → paste) wins; else `attendanceUrl` —
   which now **scores every attendance cmid** by `module tokens ∩ user's occ tokens`
   (from myGroups / learned tokens), best match wins. Previously first-captured = wrong occ.
2. **Meeting**: pasted link wins; else stored links scored by
   `event-token hits ×10 + user-occ-token hits ×10 + recency`. The `extraTokens` come from
   the class card's own name (e.g. "Lecture Occ 3" → {occ3}) — this is the fix for section
   links that carry no occ token of their own.
3. Fallback when nothing scores: first meeting link captured (arbitrary occ — this is the
   known limitation when a link has no identifying text at all).

## 3. KNOWN LIMITATION
A meeting link whose section/label contains **no occ information at all** ("section: Week 1")
and whose description doesn't name the occ cannot be matched to your occ by scoring — it falls
back to first-captured. If Spectrum's page layout lets us find occ markers inside the link
description itself, that data would make these matches exact.

## 4. CURRENT FIX STATE (v0.23.0-dev)
- `linksForCourse(ctx, code, courseId, extraTokens)` — event-token scoring added.
- Callers updated: hero attendance pill, course sheet, widget — all pass
  `Checker.groupTokens(class name)` as extraTokens.
- Compiles clean.
