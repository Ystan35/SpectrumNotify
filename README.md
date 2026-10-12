# Spectrum Notify (Android)

Android port of spectrum_notify.py. Runs entirely on the phone with your own Spectrum token. No server, no ntfy.

## Done
- Project skeleton (Kotlin, no Compose), GitHub Actions workflow that builds a debug APK
- Login via Spectrum browser redirect (custom scheme `spectrumnotify://`) + manual token fallback
- Token stored in Android Keystore-backed encrypted storage
- Slow background poll (15 min) for: Moodle notifications, new materials, announcements, deadlines (24h/3h)
- Exact alarms ~10 min before each class (class link or "no link yet"), morning summary, afternoon check-in
- Settings screen: quiet hours, times, Teams-record courses, deadline hours, poll interval
- v0.2.0 UI: launcher icon (bell), account (login) at bottom-left, settings at bottom-right
- v0.2.1: in-app sign-in page (WebView) that re-requests the token after Microsoft sign-in; browser login kept as fallback
- Fixed debug signing key (app/debug.keystore) so new builds update over the old install

- v0.2.2: main page is now a list of notification record cards (newest first, tap to expand, link buttons inside); tapping a notification opens the app on that record; "Classes to ignore" setting (default `WIX1003@Mon`, since the Monday WIX1003 class is another group; yours is Thursday)

- v0.2.3: links in notification text are tappable (plus link chips under each card); bottom-left shows your Spectrum name; Settings redesigned in sections with plain-language help under every box

- v0.3.0 student home screen: greeting + sync status + refresh button, "Up next" class card (countdown, Join class), "Due soon" strip, filter chips (All/Classes/Deadlines/Materials/News) with counts, category icons, unread dots, swipe to dismiss, tap-to-expand, friendly empty/logged-out states, follows phone light/dark mode

- v0.4.0 Timetable (calendar icon, bottom bar between account and settings): weekly class slots learned from the Spectrum calendar (attendance events grouped by course + weekday + time, remembered for 6 weeks), your own classes (+ button: course, type, days, start/end time picker, room/note), one colour per course (changeable), lecture / tutorial / lab tag read from the class name (English and Malay words, K1 = lecture, T1 = tutorial), "Other" when Spectrum does not say. Day view (cards, NOW / NEXT) and Week view (time grid). Tap a Spectrum class to set its type, change the course colour or remove it (restorable).

- v0.4.1 Other groups' posts hidden: materials, announcements and Spectrum notifications that name a different Occ / tutorial group of the same course are not notified. Your group is learned from your attendance event names (or Settings > My group: `WIX1003:occ2, WIX1003:t3`, `occ2` for all courses, `off` to disable). Posts that name no group still notify; skipped ones are logged in the console. Deadlines are not filtered.

- v0.5.0 Courses page (book icon, bottom bar left of the calendar): one card per enrolled course with its weekly class times (from the timetable) and a "Course home page" button that opens the course on Spectrum. Course list is remembered from each check; before the first check it falls back to courses in the cached calendar.

- v0.6.0 UM timetable picker (data from damnitjoshua/um-timetable-sdk, 2026/S1, 2209 courses, `app/src/main/assets/catalog.json`, bundled so every user gets it): Timetable > + > "Pick from UM timetable". Type a course code or name, then choose **Lecture Occ** / **Tutorial Occ** (/ Lab Occ) from drop-down lists showing day, time and room (lecturer under it). Saving adds the slots as your own classes (re-picking replaces them) and sets Settings > My group (e.g. `WIX1003:occ2, WIX1003:t3`) so other groups' posts are hidden.

## In progress
- v0.6.0 not compiled or run on a device (no Android SDK in the build sandbox); the CI Build APK run is the first compile. New semester: on your PC run the SDK scripts, `node cleaner.js course_events_with_details.json lecturer_data.json`, then `node tools/make_catalog.js timetable_data.json 2026/S1` (rewrites app/src/main/assets/catalog.json; keeps LEC/TUT/LAB, no emails). The 77 MB raw file never has to leave your PC
- v0.5.0 Courses page not yet run on a device; the course home page address is `<site>/course/view.php?id=<id>`
- v0.4.0 not yet run on a device. Compiles against android.jar and the timetable data logic passes JVM tests; layout and the Spectrum event names are untested for real
- v0.4.1 group filter only works if your attendance event names contain Occ / T numbers; otherwise set My group by hand
- Type detection depends on what Spectrum puts in the attendance event name/description; if it never says lecture/tutorial/lab, slots show no tag until you set one

## Not ported
- Auto-download of files
- "Attendance OPEN" alerts (needs private_token, which the login redirect does not return)

## Next / next session
1. Push to GitHub, run Build APK, install; open Timetable after one Settings > Check now and see which slots/types it learned
2. First install of v0.2.0 needs the old app uninstalled once (new signing key); later updates install over it
3. Settings > Classes to ignore: check `WIX1003@Mon` is there. Log in (bottom-left), Settings > Check now, confirm the console shows "Check OK"
4. Test a class reminder with a real calendar event
5. Optional: hosted defaults file, update notice

## Build
- CI: push to GitHub, Actions > Build APK > download `SpectrumNotify-debug-apk`
- Local: open the folder in Android Studio, Run, or `gradle assembleDebug` (Gradle 8.9, JDK 17)
- Output: app/build/outputs/apk/debug/app-debug.apk (debug-signed, installable)

## Install
Allow "install unknown apps" for your file manager/browser, open the APK, then in the app:
1. Allow notifications
2. Set site URL (default https://spectrum.um.edu.my) and press Log in
3. Allow exact alarms (Android 12 only shows this prompt) and set battery to Unrestricted
