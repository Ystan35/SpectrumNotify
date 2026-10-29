# Spectrum Notify (Android)

Android port of spectrum_notify.py. Runs entirely on the phone with your own Spectrum token. No server, no ntfy.

> Version history and known limitations live in [CHANGELOG.md](CHANGELOG.md).

## What it does

| Function | Where | How it works |
|---|---|---|
| **Background checks** | automatic | Polls Spectrum every 10, 15, 30 or 60 min (Settings > Background checks) for Moodle notifications, new materials, announcements, deadlines. During quiet hours nothing pops up (checks, class alerts, daily messages, event reminders); alerts still land in the bell list. |
| **Class reminders** | notifications | Exact alarm ~10 min before each timetable class, with the meeting link (or "no link yet"). |
| **Morning summary / afternoon check-in** | notifications | One digest in the morning, one check-in prompt in the afternoon (both configurable). |
| **Home (reminders)** | bottom bar | Greeting, sync status, refresh button, "Up next" class card with countdown and Join button, "Due soon" strip. |
| **Courses** | bottom bar | One card per enrolled course with weekly class times and a "Course home page" button. Per-course **notification pages** switches (page list learned from checks). |
| **Timetable** | bottom bar | Day view (cards with NOW/NEXT) and week grid. Slots learned from Spectrum attendance events (remembered 6 weeks), plus your own classes, one colour per course, lecture/tutorial/lab tags. Duplicates collapse: Spectrum > UM-list pick > hand-made. |
| **Pick from UM timetable** | Timetable > + | Search 2 000+ UM courses, pick your Occ, and the lecture/tutorial/lab slots are added and My group is set automatically. |
| **Notifications history** | bottom bar (bell) | Filter chips (All/Classes/Deadlines/Materials/News) with counts, tap to expand, swipe to dismiss (with Undo), Clear all (confirmed). Unread red dot. |
| **Other groups hidden** | automatic | Posts naming another Occ/tutorial group of your course are not notified (Settings > My group, learned from attendance or set by the picker). |
| **Deadlines** | notifications + home | Alerts at your chosen hours before due (default 24 h / 3 h); "Due soon" on home. |
| **Settings** | bottom bar | Accordion sections with search: Profile, Quiet hours, Daily messages, Your classes, Deadlines, Background checks, Appearance & accessibility, App & maintenance. Times use a clock picker, hours a list, reminder hours and check frequency chips; every change saves at once. |
| **Refreshable UM timetable data** | automatic | Each check downloads the newest `catalog.json` from this repo; the bundled copy is the offline fallback. The picker shows "Data as of <date>". |
| **Get set up** | Home (new installs) | Checklist card: allow notifications (reason shown first), log in, exact alarms, battery, confirm Occ, check class times, short names (optional). Hide with Undo. |
| **Undo** | Home, bell, Timetable | Hiding a deadline, swiping away a notification or removing a class shows an Undo bar for 6 s instead of a confirm dialog. |
| **Login** | Settings > Profile | In-app WebView sign-in (Microsoft) or browser redirect (`spectrumnotify://`); token in Keystore-encrypted storage. |

## The app in one line
Log in once → checks run in the background → you get class reminders, deadline alerts and material/news notifications; Courses, Timetable and the bell page show everything on demand.

## Update the UM timetable data (catalog)

The catalog is a snapshot of UM TimeEdit; lecturers/rooms can change, so refresh it a few times a semester:

1. Open https://cloud.timeedit.net/my_um/web/students/ and **log in**
2. Paste `tools/collect_timetable.js` into the browser console (F12) and wait — it downloads `lecturer_data.json` then `course_events_with_details.json` (the course part takes a while)
3. Double-click `tools/update_catalog.bat` — it finds the newest JSONs in Downloads (even `name (1).json` duplicates), runs `cleaner.js` + `make_catalog.js`, and pushes `catalog.json` to this repo
4. Phones pick it up on their next background check — no new APK needed

See `tools/collect_timetable.js` and `tools/update_catalog.ps1` headers for details.

## Build

- **CI (easiest):** push to GitHub → Actions > Build APK → download `SpectrumNotify-debug-apk`
- **Local:** open the folder in Android Studio and Run, or `gradle assembleDebug` (Gradle 8.9, JDK 17; a `local.properties` with `sdk.dir` is needed outside Android Studio)
- **Output:** `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, installable)

## Install

Allow "install unknown apps" for your file manager/browser, open the APK, then in the app:

1. Allow notifications
2. Set site URL (default https://spectrum.um.edu.my) and press Log in
3. Allow exact alarms (Android 12 only shows this prompt) and set battery to **Unrestricted**

## Repo layout

| Path | What |
|---|---|
| `app/src/main/java/my/spectrum/notify/` | All app code (Kotlin, procedural views, no Compose) |
| `app/src/main/assets/catalog.json` | Bundled UM course catalog (generated, do not edit by hand) |
| `tools/collect_timetable.js` | Browser-console script: fetches TimeEdit lecturer + course data in one paste |
| `tools/update_catalog.bat` / `.ps1` | One-click: newest raw JSONs → cleaner → catalog → commit → push |
| `tools/make_catalog.js` | Converts `timetable_data.json` into the bundled `catalog.json` |
| `tools/cleaner.js` | Patched copy of the SDK's cleaner (tolerates reservations with missing details) |
| `CHANGELOG.md` | Version history and known limitations |

## Update app

The app checks GitHub for a newer version on every background check. When one is available you get a notification — tap **Get the update** to download and install it (two taps, data preserved).
