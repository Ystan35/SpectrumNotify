# Changelog

> Entries for 0.9 – 0.25.0 live in the git history (`git log`); this file restarts at 0.25.1.

## 0.26.2 (versionCode 83)
- Settings → UM links: the toolbox shortcuts get their own section. Each link has a line icon picked from 19 (school, payment, library, calendar, mail, book, web, document, bus, food, Wi-Fi, person, star, grades, video, link, home, map, help), a name and a web address (https:// added if left out; a mistake keeps the dialog open and says why). Reorder with ▲ ▼, add any of your Spectrum course pages, Reset to UM defaults; removing or resetting offers Undo. Links saved before get an icon guessed from their name. The toolbox shows the icons, and its "Edit UM links" opens this section.
- Fix: with the app on Light and the phone on Dark (or the reverse), dialogs, time/date pickers and dropdowns followed the phone and drew dark with dark text (the Occ dialog's course codes were unreadable). Every screen now applies the app's Colour mode to Android's own parts too.
- Fix: the toolbox (Aa) showed no labels — row names and link names had zero width; rows were also needlessly tall.
- Fix: the Get set up card did not update when a step's text or button changed without it being ticked (after the first check, "Confirm your Occ" kept saying it waits for a check, with no button).
- Fix: Settings could freeze long enough for "isn't responding" (worst with Extra large text): every course card re-read and re-parsed all saved Spectrum data and rebuilt the timetable. Timetable and course lists are now built once and reused until the data, your timetable settings or the date change (Settings opens in ~1 s).
- Fix: changing the colour mode or text size redrew the screen twice (2–3 s of frozen frames); semester dates are now read once instead of for every calendar day.

## 0.26.1 (versionCode 82)
- Get set up → "Let checks run in the background" kept asking after the battery was set to Unrestricted (some phones, Samsung especially, do not report it). The step now shows Android's own one-tap "allow in background" prompt, and after one try offers "Done" so you can confirm it yourself. Settings → App & maintenance uses the same prompt.
- Home card: the button row (Join class · Attendance · Course page) is its own padded zone; a sideways drag that starts on it scrolls the buttons and no longer flips to the next class.

## 0.26.0 (versionCode 81)
- Whole 2026/27 academic calendar bundled (UM Lampiran D, amended, Senate 22 Jul 2026): Semester I (exams end 04.02.2027, was 14.02), Semester II (01.03–04.07.2027; mid-break 19–25 Apr; UM iFest 08–11 Jun with no classes) and the Special Semester. The app knows which semester a date is in; revision week, exams and breaks show in the calendar. Settings → semester dates edits the semester you are planning for; corrections are kept per semester.
- Different subjects each semester: timetable entries belong to a semester. Classes picked from the UM list or added by hand are tagged with the semester being planned (during exams/break that is the next one); learned Spectrum classes with the semester they were seen in. Timetable, Courses and Home show the current semester; the month calendar shows each date's own semester. Existing entries count as Semester I.
- Past courses: Spectrum keeps old enrolments, so a course whose Spectrum end date is over two weeks gone is left out of Courses, Occ questions and background checks.
- New semester, fresh setup: "Confirm your Occ" and "Check your class times" come back on the Get set up card for each semester.

## 0.25.1 (versionCode 80)
- Settings without typing: quiet hours from an hour list, daily messages with a clock picker and an on/off switch, deadline reminders as multi-select chips (1h–72h), check frequency as chips (10/15/30/60 min). Every change saves immediately; the "red box when you leave" check is gone. The "Raw groups" box now really saves.
- "Get set up" card on Home replaces the launch-time permission prompt and the Occ → short names → check times dialog chain. The Welcome card's Log in opens the sign-in directly.
- Occ dialog: a course left on "?" stays unconfirmed (it was marked confirmed and never asked again); the dialog opens by itself once, then from the setup card.
- Undo bar after hiding a deadline (Home and bell), swiping away a notification and removing a class, instead of a confirm dialog plus "restore it from Settings".
- Quiet hours silence every pop-up (class alerts, daily messages, event reminders); they still go into the bell list. "Send test notification" ignores quiet hours.
- Week grid: Saturday/Sunday columns only when they have a class this week; larger text (12–14sp); screen-reader descriptions on class blocks.
- Fix: a meeting link pasted in a class's Location dialog was stored but the Join buttons kept the old detected link. Pasted links now win on Home, the course sheet, Timetable, the widget and the 10-minute alert.
- Fix: the calendar showed this semester's classes on dates after the semester ends (break, next semester).
- Fix: after changing a Spectrum class's time (e.g. 10–2 → 11–1), the 10-minute alarm and alert still fired for the old time. The alarm, the alert and Home now all use your time, and saving or resetting re-arms the alarm at once.

## 0.8.4 (versionCode 17)
- Refreshable UM timetable data: each background check downloads the newest `catalog.json` from the repo (raw.githubusercontent) and keeps the newest copy by its `generatedAt` date; the bundled copy stays the first-launch/offline fallback. The picker shows "Data as of <date>" — lecturers and rooms may have changed. Pushing a new `catalog.json` updates every installed app, no APK needed.
- Catalog tooling: `tools/collect_timetable.js` (one console paste combines the SDK's main.js + lecturer.js), `tools/update_catalog.bat`/`.ps1` (finds newest Downloads copy, cleans, builds, commits, pushes), patched `tools/cleaner.js` (skips reservations with missing details instead of crashing), `tools/make_catalog.js` stamps `generatedAt`.
- Logic fixes: a failed poll now saves what it already learned (no duplicate notifications after a network drop); unique notification record ids (same-second records no longer collide — tap-to-open and swipe-dismiss target the right record); catalog load ignores corrupt/empty stored copies; catalog refresh can no longer overwrite a newer stored copy; catalog download moved out of the state lock.
- HCI fixes: ripple pressed-feedback on buttons, chips, fields and swatches; 48 dp minimum touch targets (chips, fields, top-bar action); Settings validates quiet hours (0–23), times (HH:MM) and clamps poll interval to ≥10, marking invalid boxes red; "Delete this class" asks first; DayNight theme so dialogs/pickers/switches match dark mode.

## 0.8.3
- No duplicate classes in the timetable: same course + day + overlapping time shows once. Priority: Spectrum class > class picked from the UM list (Occ) > hand-made entry. Hidden duplicates stay stored and reappear if the kept one goes away.

## 0.8.2
- Check interval: default and minimum are now 10 minutes (Settings > Background checks; old 15-minute settings reset to 10). Below 15 minutes the app uses a self-re-arming alarm (PollAlarmReceiver) since Android only allows 15+ minute periodic jobs; 15+ still uses WorkManager. Quiet hours still apply. Doze may delay alarms; keep battery on Unrestricted.

## 0.8.1
- Bottom bar labels centred under their icons (equal-width cells). Settings is an accordion: one section open at a time (Profile, Quiet hours, Daily messages, Your classes, Other groups' posts, Deadlines, Background checks, Tools). Profile opens by itself while logged out.

## 0.8.0
- Layout: home = reminders only (greeting, Up next, Due soon). Bottom bar: Courses, Timetable, Notifications (bell, red dot when unread), Settings. Notification history moved to the bell page (filter chips, expand, swipe to dismiss, Clear all). Tapping a phone notification opens the bell page on that record. Account moved into Settings as the PROFILE section.

## 0.7.0
- Notification pages: Courses > a course card > "Notification pages" lists the course's pages with a switch each. Only switched-off pages are stored, so new pages are on by default. Items on an off page are not notified (still count as seen, so re-enabling never replays old items).

## 0.6.1
- One Occ drop-down per course (a student has one Occ; lectures are shared). Adds that Occ's lecture, tutorial and lab slots and sets My group to `CODE:occN`. Settings > "Choose my Occ from a list".

## 0.6.0
- UM timetable picker (data from damnitjoshua/um-timetable-sdk, bundled as `app/src/main/assets/catalog.json`): Timetable > + > "Pick from UM timetable". Type a code or name, choose your Occ from drop-downs showing day, time, room (lecturer under). Saving adds the slots and sets My group so other groups' posts are hidden.

## 0.5.0
- Courses page: one card per enrolled course with weekly class times and a "Course home page" button. Course list remembered from each check; falls back to cached calendar courses before the first check.

## 0.4.1
- Other groups' posts hidden: materials, announcements and notifications naming a different Occ/tutorial group of the same course are not notified. Group learned from attendance event names or Settings > My group (`WIX1003:occ2, WIX1003:t3`, `off` disables). Posts naming no group still notify. Deadlines are not filtered.

## 0.4.0
- Timetable: weekly slots learned from Spectrum attendance events (course + weekday + time, remembered 6 weeks), your own classes (+ button with time picker, room/note), one colour per course, lecture/tutorial/lab tags from the class name (English/Malay; K1 = lecture, T1 = tutorial), "Other" otherwise. Day view (cards, NOW/NEXT) and week grid. Tap a learned class to set its type, recolour, or remove (restorable).

## 0.3.0
- Student home screen: greeting, sync status, refresh button, "Up next" class card (countdown, Join class), "Due soon" strip, filter chips with counts, category icons, unread dots, swipe to dismiss, tap-to-expand, light/dark following the phone.

## 0.2.3
- Links in notification text are tappable; link chips under each card; your Spectrum name at the bottom-left; Settings sections with plain-language help under every box.

## 0.2.2
- Main page: notification record cards (newest first, expandable, link buttons). Tapping a phone notification opens the app on that record. "Classes to ignore" setting (default `WIX1003@Mon`).

## 0.2.1
- In-app sign-in page (WebView) that re-requests the token after Microsoft sign-in; browser login kept as fallback.

## 0.2.0
- Launcher icon; fixed debug signing key (`app/debug.keystore`) so updates install over the old build.

## 0.1.0
- First working version: login + encrypted token, background poll (notifications, materials, announcements, deadlines), class reminders via exact alarms, morning summary and afternoon check-in, Settings.

## Known limitations
- Not compiled against a real device until recently — CI "Build APK" is the verifying compile; layouts and Spectrum event names are tested only against real data once installed.
- The group filter only works if attendance event names contain Occ / T numbers; otherwise set My group by hand.
- Lecture/tutorial/lab detection depends on what Spectrum puts in the event name/description; slots without it show no tag until you set one.
- Not ported: auto-download of files; "Attendance OPEN" alerts (needs `private_token`, which the login redirect does not return).
- The UM catalog only reflects TimeEdit when you refresh it (`tools/update_catalog.bat`); between refreshes the "Data as of" date tells you how old it is.
