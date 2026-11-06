# Feedback tickets: one-time setup

The app sends feedback to a Google Apps Script web app (`Code.gs`), which files it as an issue in a private GitHub
repo and emails you a copy. The app later asks the script for the status of its own tickets.

## 1. Private GitHub repo
1. github.com → New repository → name `SpectrumNotify-feedback` → **Private** → Create.
2. Issues → Labels → add `bug`, `idea`, `question`, `assistant` and `in-progress`.
   Add `fixed-in:<version>` labels as you fix things (e.g. `fixed-in:0.33.0`).

## 2. GitHub token (issues on that repo only)
1. github.com → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token.
2. Name `feedback-script`, expiry **1 year** (put a reminder in your calendar to renew it).
3. Repository access: **Only select repositories** → `SpectrumNotify-feedback`.
4. Permissions → Repository permissions → **Issues: Read and write** and **Contents: Read and write** (screenshots are saved in the repo's `attachments/` folder). Nothing else.
   An existing token: open it under Fine-grained tokens → Edit → add Contents → Update. The token value stays the same.
5. Generate, and copy the token (it is shown once).

## 3. The script (signed in to the Google account that should get the emails)
1. script.google.com → New project → name it `Spectrum Notify feedback`.
2. Replace everything in `Code.gs` with this repo's `tools/feedback/Code.gs`; save.
3. Project Settings (gear) → Script properties → add:
   - `GH_TOKEN` = the token from step 2
   - `GH_REPO` = `Ystan35/SpectrumNotify-feedback`
   - `NOTIFY_EMAIL` = where copies go (optional; defaults to this Google account)
4. Deploy → New deployment → type **Web app** → Execute as **Me** → Who has access **Anyone** → Deploy.
5. Allow the permissions it asks for (send email as you, connect to an external service).
6. Copy the **Web app URL** (ends in `/exec`) and send it to the developer of the app (it goes in the app).

## 4. Check it works
```
curl -L -H "Content-Type: application/json" -d "{\"ticket\":\"SN-TEST1\",\"kind\":\"bug\",\"message\":\"Setup test\",\"app\":\"setup\",\"device\":\"curl\",\"install\":\"0000000000000000\"}" "<web app URL>"
curl -L "<web app URL>?ids=SN-TEST1"
```
The first should answer `{"ok":true,...}` and create an issue plus an email; the second `{"SN-TEST1":{"status":"received"}}`.
Close the test issue afterwards.

## Working tickets (like ClickUp)
| You do on GitHub | The user sees in the app |
|---|---|
| nothing yet (issue open) | Received |
| add label `in-progress` | Working on it |
| close with label `fixed-in:0.33.0` | Fixed in 0.33.0 (+ a notification; "Update to get it" if their app is older) |
| close as **not planned** | Closed |
| comment starting with `Reply:` before closing | Your reply under the ticket |

Status reaches phones within about 2 minutes plus their next background check (or at once when they open Send feedback).

The `t:SN-...` entries in Script properties are the script's record of which issue is which ticket. Saving that page may drop them; the script then finds the issue by its title again, so no harm done.

## Changing the script later
Edit `Code.gs`, then Deploy → **Manage deployments** → edit the existing one → Version: New version → Deploy.
This keeps the same URL; a *new* deployment gets a new URL, which phones would not know.
