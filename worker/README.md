# SpectrumNotify cloud poller (Cloudflare Worker)

A Cloudflare Worker that polls your Spectrum (Moodle) site every 10 minutes and
pushes new notifications and upcoming deadlines to your phone via
[ntfy](https://ntfy.sh). Free tier: 100k requests/day, this uses ~150/day.

The Android app keeps working as-is — this is an addition, not a replacement.

## What it does

- Polls `message_popup_get_popup_notifications` — pushes anything new.
- Polls `core_calendar_get_action_events_by_timesort` — pushes activities due in the next 48h once each.
- Keeps seen-IDs and sent-flags in Workers KV so restarts don't re-push.
- Small HTTP API (`X-Admin-Key` header or `?key=` on every call):
  - `POST /config` `{"site": "...", "token": "...", "adminKey": "..."}` — set credentials
  - `POST /poll` — run a check immediately
  - `GET /status` — is it configured

## Setup (about 15 minutes)

1. Install Node, then: `npm install -g wrangler` and `wrangler login`
2. In this folder: `wrangler kv namespace create STATE` — paste the id into `wrangler.toml`
3. Pick a secret topic name for ntfy (something unguessable, e.g. `spectrum-x7k2p9`) and subscribe to it in the ntfy app on your phone. Put it in `wrangler.toml` as `NTFY_TOPIC`.
4. Generate an admin key: `wrangler secret` isn't needed — you'll store it via /config. Pick any long random string.
5. Deploy: `wrangler deploy`
6. Configure it (your Moodle token is the same one the app uses; Settings > Account in the app shows it):
   ```
   curl -X POST https://spectrum-notify-worker.<you>.workers.dev/config \
     -H "Content-Type: application/json" \
     -d '{"site":"https://spectrum.um.edu.my","token":"YOUR_TOKEN","adminKey":"YOUR_LONG_STRING"}'
   ```
7. Test: `curl -X POST ".../poll?key=YOUR_LONG_STRING"` — you should get `ok: N new...` and an ntfy ping.

The cron trigger fires every 10 minutes from `wrangler.toml`.

## Notes

- The Moodle token is stored in your Worker's KV. Only deploy this to your own Cloudflare account.
- To also use FCM push instead of ntfy, the Worker can be extended to call the FCM HTTP v1 API — ntfy is simpler because the app already has no Firebase dependency.
