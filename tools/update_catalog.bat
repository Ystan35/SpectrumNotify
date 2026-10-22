@echo off
rem Update the app's bundled UM timetable catalog and push it to GitHub.
rem You do the login part; this script does the rest.
rem
rem Steps:
rem   1. Open https://cloud.timeedit.net/my_um/web/students/ in Chrome/Edge and log in.
rem   2. Paste main.js into the console (F12) -> wait -> it downloads course_events_with_details.json
rem   3. Paste lecturer.js into the console -> it downloads lecturer_data.json
rem   4. Put both JSON files (from your Downloads folder) into this tools folder if they are not already here.
rem   5. Run this script (double-click or `tools\update_catalog.bat`).
rem
rem The script runs cleaner.js, rebuilds app/src/main/assets/catalog.json, commits and pushes.

setlocal enabledelayedexpansion
cd /d "%~dp0.."

set SDK_DIR=%TEMP%\um-timetable-sdk
if not exist "%SDK_DIR%\cleaner.js" (
  echo Cloning um-timetable-sdk...
  git clone --depth 1 https://github.com/damnitjoshua/um-timetable-sdk.git "%SDK_DIR%" || goto :fail
)

set RAW=tools\course_events_with_details.json
set LEC=tools\lecturer_data.json
if not exist "%RAW%" (
  if exist "%USERPROFILE%\Downloads\course_events_with_details.json" copy /y "%USERPROFILE%\Downloads\course_events_with_details.json" "%RAW%" >nul
)
if not exist "%LEC%" (
  if exist "%USERPROFILE%\Downloads\lecturer_data.json" copy /y "%USERPROFILE%\Downloads\lecturer_data.json" "%LEC%" >nul
)
if not exist "%RAW%" ( echo Missing course_events_with_details.json - run main.js on TimeEdit first & goto :fail )
if not exist "%LEC%" ( echo Missing lecturer_data.json - run lecturer.js on TimeEdit first & goto :fail )

echo Running cleaner.js...
node "%SDK_DIR%\cleaner.js" "%RAW%" "%LEC%" || goto :fail

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
