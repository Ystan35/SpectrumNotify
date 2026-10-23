# Agent Memory — Spectrum Notify session conventions

## Workflow rules (user-mandated)

- **Ask before executing**: before applying any non-trivial change, state what will be done and ask the user to confirm (this also proves understanding). Trivial fixes (typos, one-line renames) can proceed directly.
- **Ask about addon ideas**: before starting a new task, ask whether the user has any addon/feature ideas to fold into the same round, instead of just executing.
- **Version bump**: bump `versionCode` + `versionName` in `app/build.gradle.kts` when shipping user-visible changes — CI auto-syncs `latest.json` and users get update notifications. Forgetting this made "Check for update" wrongly say "latest".

## Prediction sources (established decisions)

- Occ **prediction** for the confirm dialog = **attendance/event-name learning** (`my_groups` from `Groups.learn`, i.e. the user's attendance events). NOT the page-availability detection (that only populates the full Occ list options).
- Confirm dialog: unconfirmed courses show **ALL catalog Occs** (fallback: learned/detected), dropdown **not prefilled** (user picks, "Save all" once).
- Course with no Occ data at all: still shown in the dialog with an empty "Choose Occ" dropdown.

## Env facts

- Real device: RRCW600L8VN (USB adb). Emulator AVD: SpectrumTest (emulator-5554).
- Local build: `JAVA_HOME="C:/Program Files/Java/jdk-25.0.4.1"` + gradle at `%USERPROFILE%/.gradle/wrapper/dists/gradle-9.2.1-bin/.../bin/gradle.bat`.
- APK to user's phone: `cmd //c "C:\Users\user\Desktop\send.bat" <apk>` (Telegram bot).
- Repo is PUBLIC — raw.githubusercontent links work for catalog + latest.json + apk/ updates.
- CI: GH_PAT secret (classic, repo scope) + `git config --unset-all http.https://github.com/.extraheader` before PAT push (checkout injects read-only creds).
- Real project repo: `C:\Users\user\Downloads\SpectrumNotify` (NOT SpectrumNotify-0_8_3 — that's a zip extract inside a stray home-dir repo).
