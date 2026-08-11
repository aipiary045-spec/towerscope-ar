# Publishes TowerScope-AR-sideload.apk to the replaceable GitHub release
# tag "sideload-latest" (easy phone download). Also refreshes a Drive helper
# note via the Cursor Google Drive MCP when an agent runs after this.
#
# Usage (from repo root):
#   powershell -File scripts/publish_sideload.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Building release APK..."
& .\gradlew.bat :app:assembleRelease --quiet
if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed" }

$src = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$dest = Join-Path $root "TowerScope-AR-sideload.apk"
Copy-Item -Force $src $dest

$tag = "sideload-latest"
Write-Host "Updating GitHub release $tag ..."
gh release delete $tag -R aipiary045-spec/towerscope-ar --yes 2>$null | Out-Null
# git writes progress to stderr even on success; don't let that stop the script
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
git push origin ":refs/tags/$tag" 2>$null | Out-Null
$ErrorActionPreference = $prevEap
gh release create $tag $dest `
  -R aipiary045-spec/towerscope-ar `
  --title "TowerScope sideload (latest)" `
  --notes "Latest sideload APK for phone install." `
  --latest=false

$url = "https://github.com/aipiary045-spec/towerscope-ar/releases/download/sideload-latest/TowerScope-AR-sideload.apk"
Write-Host ""
Write-Host "Done."
Write-Host "Phone download: $url"
Write-Host "Local file:     $dest"
Write-Host "Drive helper:   open 'TowerScope-AR-sideload (download)' in Google Drive"
