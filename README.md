# Spectrum Notify (Android)

Android app for Moodle: UM Spectrum first, and any other university's Moodle (e.g. USM). Runs entirely on the phone with your own token. No server, no ntfy.

**Download:** the newest APK is always at [Releases](https://github.com/Ystan35/SpectrumNotify/releases/latest).

> Version history and known limitations live in [CHANGELOG.md](CHANGELOG.md).

## What it does

| Function | Where | How it works |
|---|---|---|
| **Background checks** | automatic | Polls Spectrum every 10, 15, 30 or 60 min (Settings > Background checks) for Moodle notifications, new materials, announcements, deadlines. During quiet hours nothing pops up (checks, class alerts, daily messages, event reminders); alerts still land in the bell list. |
| **Class reminders** | notifications | Exact alarm ~10 min before each timetable class, with the meeting link (or "no link yet"). |
| **Morning summary / afternoon check-in** | notifications | One digest in the morning, one check-in prompt in the afternoon (both configurable). |
| **Home** | bottom bar | Greeting and sync status in a header that stays put, refresh button, "Next class" card (swipe or ‹ › through the coming classes) with Join class (meeting links only, never for in-person courses), Attendance and Course page, "Due soon" strip (tap to mark done or hide; hiding takes effect at once). |
| **Courses** | bottom bar | One card per enrolled course with your Occ as a pill on the code line, weekly class times, the attendance page in use, and a "Course home page" button. **Materials** lists every file, folder, link and activity by Spectrum page; files download in the app with your login and open in the matching app. Per-course **notification pages** switches (page list learned from checks). |
| **Timetable** | bottom bar | Day, Week and Month views. Month: tap a day for its timeline, then swipe it or use ‹ › for the days around it. Slots learned from Spectrum attendance events (remembered 6 weeks), plus your own classes, one colour per course, lecture/tutorial/lab tags. Duplicates collapse: Spectrum > UM-list pick > hand-made. The round pencil inside Attendance picks the attendance page (lectures and tutorials separately), listed in Spectrum's order. |
| **Pick from UM timetable** | Timetable > + | Search 2 000+ UM courses, pick your Occ, and the lecture/tutorial/lab slots are added and My group is set automatically. |
| **Notifications history** | bottom bar (bell) | Filter chips (All/Classes/Deadlines/Materials/News) with counts, tap to expand, swipe to dismiss (with Undo), Clear all (confirmed). Unread red dot. |
| **Other groups hidden** | automatic | Posts naming another Occ/tutorial group of your course are not notified (Settings > My group, learned from attendance or set by the picker). |
| **Deadlines** | notifications + home | Alerts at your chosen hours before due (default 24 h / 3 h); "Due soon" on home. |
| **Settings** | bottom bar | Accordion sections with search: Profile, Quiet hours, Daily messages, Your classes, Deadlines (hidden tasks listed one by one, each with Restore; past-due ones cleared), Background checks, Appearance & accessibility (light by default), App & maintenance (Show the guide again). Times use a clock picker, hours a list, reminder hours and check frequency chips; every change saves at once. |
| **Refreshable UM timetable data** | automatic | Each check downloads the newest `catalog.json` from this repo; the bundled copy is the offline fallback. The picker shows "Data as of <date>". |
| **Get set up** | Home (new installs) | Checklist card: allow notifications (reason shown first), log in, exact alarms, battery, confirm Occ, check class times, short names (optional). Hide with Undo. |
| **First-run guide** | new installs | Dims the screen except one control with a short note. First points at Log in; after logging in it walks through each tab (switching to it) and its gestures. Skip says where to replay it: Settings → App & maintenance → Show the guide again. Updating from an older version does not show it. |
| **Swipe between tabs** | every tab | A long sideways swipe opens the next or previous tab; cards, pagers and scrolling rows keep their own swipes. |
| **Undo** | Home, bell, Timetable | Hiding a deadline, swiping away a notification or removing a class shows an Undo bar for 6 s instead of a confirm dialog. |
| **Semesters** | automatic + calendar | Bundled UM 2026/27 calendar (Sem I, Sem II, Special). Classes hide outside teaching weeks; each timetable entry belongs to one semester, so next semester's subjects start clean. Past courses drop out of Courses and checks. |
| **Online or in person** | Settings → Online or in person, Timetable shortcut | Mark courses as in person in one go when online classes end: Join class and meeting links are hidden for them and the room is shown; a newly posted meeting link asks whether the course is online again. |
| **UM shuttle** | toolbox (Aa), Home card, Settings → UM shuttle | Routes AB, BA, C, D, E and 13 (stops and times in `assets/bus.json`). The Shuttle screen plans a trip from any place to any other (today, tomorrow or any day; leave now, leave at or arrive by; at most one change of bus; faculties and colleges included, searchable by short name such as KK12 or FSKTM/FCSIT) from a place or your current location, on an OpenStreetMap map with bus lines along the roads (Navigate tab); the Explore tab shows the next buses from a stop and each route's stops, today's times and map. Home's class card says which bus to take from your home stop or previous class, or to walk when that is quicker or no bus fits (tap for Google Maps walking directions); a leave-by alert comes 5–15 min before you must set off. Stops per course are guessed from the course code and can be changed in the class editor. **Your own places:** search by name (Android's map search), share a place from Google Maps to the app, or drop a pin / use I'm here; rename, move or delete by holding it. Trips more than 3 km away that the shuttle does not serve offer Google Maps directions instead. |
| **Links** | toolbox (Aa), widget, Settings → Links | Shortcuts with a line icon each (19 to choose from), drag to reorder, add course pages (one bordered card per course, those already added left out), reset to defaults (with Undo). |
| **Oli (assistant)** | round button on every tab | On-device chat (TensorFlow Lite model trained in `tools/assistant`): next class, classes on a day, where a class is and the shuttle there, deadlines, free time, reminders added after a confirm, weather. Answers only from the app's own data; emergency numbers first, no schoolwork, passwords refused; unsure questions can be sent to the developer. |
| **Send feedback** | Settings → Help & feedback | Bug, Idea or Question with up to 3 screenshots, filed as a private ticket (`tools/feedback`); My feedback shows its status and the developer's reply. |
| **Login** | Settings > Profile | In-app WebView sign-in (Microsoft) or browser redirect (`spectrumnotify://`); token in Keystore-encrypted storage. |

## The app in one line
Log in once → checks run in the background → you get class reminders, deadline alerts and material/news notifications; Courses, Timetable and the bell page show everything on demand.

## Update the UM timetable data (catalog)

The catalog is a snapshot of UM TimeEdit for one semester; lecturers/rooms can change, so refresh it a few times a semester — and always before a new semester starts, since the subjects change:

1. Open https://cloud.timeedit.net/my_um/web/students/ and **log in**
2. Paste `tools/collect_timetable.js` into the browser console (F12) and wait — it downloads `lecturer_data.json` then `course_events_with_details.json` (the course part takes a while)
3. Double-click `tools/update_catalog.bat` — it finds the newest JSONs in Downloads (even `name (1).json` duplicates), runs `cleaner.js` + `make_catalog.js`, and pushes `catalog.json` to this repo
4. Phones pick it up on their next background check — no new APK needed

See `tools/collect_timetable.js` and `tools/update_catalog.ps1` headers for details.

## Update the shuttle timetable (bus.json)

`app/src/main/assets/bus.json` holds the stops (`places`), each route's stops with minutes from its first stop, and the trip times (`schedule`: every N minutes from/to plus extra `then` times, Friday-prayer gap). To change times or stops, edit it, bump `generatedAt` and push: phones download the newer copy on their next background check, no new APK needed. Optional per place: `lat`/`lng` (enables walking minutes), `near` (the stop a building uses), `q` (what Google Maps searches for). `faculties` maps a course-code prefix (e.g. `WI`) to a place.

Rapid KL routes come from Prasarana's GTFS feed: `curl -L -o rapid.zip "https://api.data.gov.my/gtfs-static/prasarana?category=rapid-bus-kl"` then `python3 tools/gtfs_to_bus.py rapid.zip T789` (more route numbers can follow). It writes the stops (with coordinates), minutes between them and the "every N min" bands into `bus.json`.

## Build

- **CI (easiest):** push to `main` → Actions > Build APK builds the APKs, publishes a GitHub Release `v<versionName>` with `SpectrumNotify.apk` (once per version; bump `versionName`/`versionCode` in `app/build.gradle.kts` for a new one), then updates `latest.json`. The APKs are also in the run's `SpectrumNotify-apks` artifact.
- **Local:** open the folder in Android Studio and Run, or `gradle assembleDebug` (Gradle 8.9, JDK 17; a `local.properties` with `sdk.dir` is needed outside Android Studio)
- **Output:** `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, installable)

## Install

Download `SpectrumNotify.apk` from [the latest release](https://github.com/Ystan35/SpectrumNotify/releases/latest), allow "install unknown apps" for your browser or file manager, open the APK, then in the app:

1. Allow notifications
2. Set site URL (default https://spectrum.um.edu.my) and press Log in
3. Allow exact alarms (Android 12 only shows this prompt) and set battery to **Unrestricted**

## Repo layout

| Path | What |
|---|---|
| `app/src/main/java/my/spectrum/notify/` | All app code (Kotlin, procedural views, no Compose) |
| `app/src/main/assets/catalog.json` | Bundled UM course catalog (generated, do not edit by hand) |
| `app/src/main/assets/bus.json` | UM shuttle routes, stops and times (edit by hand; see above) |
| `tools/collect_timetable.js` | Browser-console script: fetches TimeEdit lecturer + course data in one paste |
| `tools/update_catalog.bat` / `.ps1` | One-click: newest raw JSONs → cleaner → catalog → commit → push |
| `tools/gtfs_to_bus.py` | Adds/refreshes Rapid KL routes in `bus.json` from Prasarana's GTFS feed |
| `tools/make_catalog.js` | Converts `timetable_data.json` into the bundled `catalog.json` |
| `tools/cleaner.js` | Patched copy of the SDK's cleaner (tolerates reservations with missing details) |
| `CHANGELOG.md` | Version history and known limitations |

## Update app

The app reads `latest.json` from this repo on every background check. When a newer version is listed you get a notification — tap **Get the update** to download the latest release's APK and install it (two taps, data preserved). Versions up to 0.31.0 download from `apk/SpectrumNotify.apk` in the repo, which CI still keeps current for them.
