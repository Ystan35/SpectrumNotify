# Finds the newest copy of a browser-downloaded file in Downloads (handles "name (1).json")
# and copies it to the destination if it is missing or older than the newest download.
# Usage: powershell -File newest_download.ps1 <filename.json> <destination-path>
param([string]$Name, [string]$Dest)
$ErrorActionPreference = 'Stop'
$dl = Join-Path $env:USERPROFILE 'Downloads'
$base = [IO.Path]::GetFileNameWithoutExtension($Name)
$ext = [IO.Path]::GetExtension($Name)
$cands = @(Get-ChildItem -LiteralPath $dl -File -ErrorAction SilentlyContinue) |
  Where-Object { $_.Name -eq $Name -or $_.Name -match ('^' + [regex]::Escape($base) + ' \(\d+\)' + [regex]::Escape($ext) + '$') }
if ($cands.Count -eq 0) { exit 1 }
$src = $cands | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ((Test-Path $Dest) -and ((Get-Item $Dest).LastWriteTime -ge $src.LastWriteTime)) { exit 0 }  # destination already up to date
Copy-Item -LiteralPath $src.FullName -Destination $Dest -Force
Write-Host ("Using {0} ({1})" -f $src.Name, $src.LastWriteTime)
exit 0
