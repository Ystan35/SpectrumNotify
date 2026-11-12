# Spectrum Notify on the web

A timetable page (iPhone and laptop) with class reminders and a morning summary pushed to your device.
No Spectrum login. It uses the same `catalog.json` as the Android app.

```
index.html, core.js, sw.js, config.js   the page, its rules, the service worker, the public push key
server/                                  what to send and when (schedule.js), storage, push
api/                                     Vercel functions: subscribe, unsubscribe, test, tick
dev.js                                   try it locally:  node dev.js  ->  http://localhost:8765
test.js, test_server.js                  node test.js && node test_server.js
```

## Why a server

A web page cannot wake itself up when it is closed, and on iPhone only the push service can. So a small server
sends a push at the right minute. It keeps, per device: the push address, your course codes and Occs, and your two choices
(minutes before class, morning time). Nothing else.

## Put it online (Vercel)

1. Create a Vercel project from this repo with **Root Directory = `pwa`**.
2. Add a free **Upstash Redis** (Vercel Marketplace). It sets `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`.
3. Set these environment variables:
   - `VAPID_PRIVATE`: the private key (never put it in the repo). Make a new pair with
     `node -e "const k=require('web-push').generateVAPIDKeys();console.log(k)"` (run inside `pwa` after `npm install`).
   - `VAPID_PUBLIC`: its public half, **and** the same value in `config.js` (`publicKey`).
   - `VAPID_SUBJECT`: `https://github.com/Ystan35/SpectrumNotify` (or a `mailto:` you choose; it is shown to the push services).
   - `TICK_SECRET`: a long random string.
4. **Every minute** call `https://YOUR-SITE/api/tick?key=TICK_SECRET` from a free scheduler such as cron-job.org.
   Vercel's own free cron runs only once a day, which is too slow for "10 minutes before class".
5. Open the site. On iPhone: Share, **Add to Home Screen**, open it from the icon, add your courses, **Turn on reminders**,
   then **Send me a test notice**.

## Limits

- iPhone needs iOS 16.4 or newer, and the page on the Home Screen.
- A reminder is sent if the minute ticks within 6 minutes of its time. If the scheduler is down, that reminder is missed, not delayed.
- The break weeks are in `server/calendar.json`; `node test_server.js` fails if they differ from the Android app's `AcademicCalendar.kt`.
- Deadlines and attendance alerts are not here: they need a Spectrum login token stored on the server.
