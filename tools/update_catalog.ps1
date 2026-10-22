# Update the app's bundled UM timetable catalog and push it to GitHub.
# You do the login part; this script does the rest:
#   1. Finds the newest course_events_with_details.json / lecturer_data.json in your Downloads
#      (handles browser duplicates like "name (1).json").
#   2. Runs cleaner.js (um-timetable-sdk) -> timetable_data.json.
#   3. Runs make_catalog.js -> app/src/main/assets/catalog.json (stamps generatedAt).
#   4. Commits and pushes to Ystan35/SpectrumNotify main.
# Run it via update_catalog.bat (double-click) or: powershell -File tools\update_catalog.ps1
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

function Copy-Newest([string]$Name, [string]$Dest) {
    $dl = Join-Path $env:USERPROFILE 'Downloads'
    $base = [IO.Path]::GetFileNameWithoutExtension($Name)
    $ext = [IO.Path]::GetExtension($Name)
    $cands = @(Get-ChildItem -LiteralPath $dl -File -ErrorAction SilentlyContinue) |
        Where-Object { $_.Name -eq $Name -or $_.Name -match ('^' + [regex]::Escape($base) + ' \(\d+\)' + [regex]::Escape($ext) + '$') }
    if ($cands.Count -gt 0) {
        $src = $cands | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not (Test-Path $Dest) -or (Get-Item $Dest).LastWriteTime -lt $src.LastWriteTime) {
            Copy-Item -LiteralPath $src.FullName -Destination $Dest -Force
            Write-Host ("Using {0} ({1})" -f $src.Name, $src.LastWriteTime)
        }
        return $true
    }
    return (Test-Path $Dest)
}

# 1. SDK clone (first run only)
$sdk = Join-Path $env:TEMP 'um-timetable-sdk'
if (-not (Test-Path (Join-Path $sdk 'cleaner.js'))) {
    Write-Host 'Cloning um-timetable-sdk...'
    git clone --depth 1 https://github.com/damnitjoshua/um-timetable-sdk.git $sdk
    if ($LASTEXITCODE -ne 0) { throw 'git clone failed' }
}

# 2. Raw data files
$ok1 = Copy-Newest 'course_events_with_details.json' 'tools\course_events_with_details.json'
$ok2 = Copy-Newest 'lecturer_data.json' 'tools\lecturer_data.json'
if (-not $ok1 -or -not $ok2) {
    Write-Host 'Missing raw JSON(s) - paste tools\collect_timetable.js on TimeEdit first (see the file header).'
    exit 1
}

# 3. Clean + build the catalog
Write-Host 'Running cleaner.js...'
node 'tools/cleaner.js' 'tools\course_events_with_details.json' 'tools\lecturer_data.json'
if ($LASTEXITCODE -ne 0) { throw 'cleaner.js failed' }

Write-Host 'Building catalog.json...'
node 'tools\make_catalog.js' 'timetable_data.json' '2026/S1'
if ($LASTEXITCODE -ne 0) { throw 'make_catalog.js failed' }

# 4. Commit + push
git add app/src/main/assets/catalog.json
if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
$diff = git diff --cached --quiet; if ($LASTEXITCODE -eq 0) {
    Write-Host 'Catalog unchanged - nothing to push.'
    exit 0
}
git commit -m "Update catalog"
if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
# The app downloads from Ystan35/SpectrumNotify, so push that remote explicitly.
git push https://github.com/Ystan35/SpectrumNotify.git main
if ($LASTEXITCODE -ne 0) { throw 'git push failed' }

Write-Host ''
Write-Host 'Done. Installed apps will pick it up on their next background check.'
