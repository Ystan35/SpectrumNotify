@echo off
rem Update the app's bundled UM timetable catalog and push it to GitHub.
rem You do the login part; this script does the rest.
rem
rem Steps:
rem   1. Open https://cloud.timeedit.net/my_um/web/students/ in Chrome/Edge and log in.
rem   2. Paste tools\collect_timetable.js into the console (F12) -> wait -> it downloads
rem      lecturer_data.json and course_events_with_details.json (the course part takes a while).
rem   3. Run this script (double-click or `tools\update_catalog.bat`).
rem
rem It finds the newest copy of each JSON in your Downloads folder, even when the browser
rem renamed duplicates to "name (1).json". Then it runs cleaner.js, rebuilds
rem app/src/main/assets/catalog.json, commits and pushes.

setlocal
cd /d "%~dp0.."

set SDK_DIR=%TEMP%\um-timetable-sdk
if not exist "%SDK_DIR%\cleaner.js" (
  echo Cloning um-timetable-sdk...
  git clone --depth 1 https://github.com/damnitjoshua/um-timetable-sdk.git "%SDK_DIR%" || goto :fail
)

rem Pick the newest "name.json" or "name (n).json" from Downloads into tools\name.json.
for %%N in (course_events_with_details.json lecturer_data.json) do (
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\newest_download.ps1 "%%N" "tools\%%N"
  if errorlevel 1 (
    echo Missing %%N - paste tools\collect_timetable.js on TimeEdit first.
    goto :fail
  )
)

echo Running cleaner.js...
node "%SDK_DIR%\cleaner.js" "tools\course_events_with_details.json" "tools\lecturer_data.json" || goto :fail

echo Building catalog.json...
node tools\make_catalog.js timetable_data.json 2026/S1 || goto :fail

git add app/src/main/assets/catalog.json
git diff --cached --quiet && (
  echo Catalog unchanged - nothing to push.
  exit /b 0
)
git commit -m "Update catalog" || goto :fail
rem The app downloads from Ystan35/SpectrumNotify, so push that remote explicitly.
git push https://github.com/Ystan35/SpectrumNotify.git main || goto :fail

echo.
echo Done. Installed apps will pick it up on their next background check.
exit /b 0

:fail
echo.
echo FAILED - see the message above.
exit /b 1
