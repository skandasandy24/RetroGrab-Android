<#
  Publish a new RetroGrab Android release (this is what triggers update alerts in the app).

  Usage (PowerShell, in this folder):
    .\release.ps1 -Version 1.0.7 -Notes "test" -DryRun   (build + update test only)
    .\release.ps1 -Version 1.0.4 -Notes "Fixed Instagram carousels"
    .\release.ps1 -Version 1.0.4 -Notes "Fixes YouTube downloads" -Level important
    .\release.ps1 -Version 1.0.4 -Notes "Old versions are broken" -Level required

  -Level normal     people can Update / Later / Skip
  -Level important  no Skip button, asks on every launch until they update
  -Level required   app is blocked until they update

  Steps: bumps versionCode/versionName -> builds signed APKs -> commits + pushes -> creates GitHub release
  "v<Version>" with the 4 APKs. Pushing code WITHOUT running this never alerts anyone.
#>
param(
  [Parameter(Mandatory)] [ValidatePattern('^\d+\.\d+\.\d+$')] [string]$Version,
  [Parameter(Mandatory)] [string]$Notes,
  [ValidateSet('normal', 'important', 'required')] [string]$Level = 'normal',
  [switch]$DryRun   # build + run every update check, then stop (publishes nothing)
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'skandasandy24/RetroGrab-Android'
$gradleFile = Join-Path $root 'app\build.gradle.kts'

if (-not (Test-Path (Join-Path $root 'keystore.properties'))) { throw 'keystore.properties is missing - restore it from D:\CALUDE WORKS\_keys-backup\retrograb' }
if (git -C $root tag --list "v$Version") { throw "v$Version already exists - pick a higher version." }

function Step($n, $what) { Write-Progress -Id 1 -Activity "RetroGrab release $Version" -Status "Step $n of 5: $what" -PercentComplete (($n - 1) * 20) }

# 1. Bump version
Step 1 'Bumping version'
$g = Get-Content $gradleFile -Raw
$code = [int]([regex]::Match($g, 'versionCode = (\d+)').Groups[1].Value) + 1
$g = $g -replace 'versionCode = \d+', "versionCode = $code" -replace 'versionName = "[^"]+"', "versionName = `"$Version`""
[IO.File]::WriteAllText($gradleFile, $g)
Write-Host "Version $Version (code $code), level: $Level"

# 2. Build signed APKs
Step 2 'Building signed APKs (takes a few minutes)'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
& cmd /c "cd /d `"$root`" && .\gradlew.bat assembleRelease --console=plain"
if ($LASTEXITCODE -ne 0) { throw 'Build failed - nothing was published.' }

$out = Join-Path $root 'release-out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory $out | Out-Null
$apkDir = Join-Path $root 'app\build\outputs\apk\release'
foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal') {
  Copy-Item (Join-Path $apkDir "app-$abi-release.apk") (Join-Path $out "RetroGrab-$Version-$abi.apk")
}

# 2b. Safety check: can phones that have the published version actually update to these APKs?
Step 3 'Update safety checks + emulator update test'
try { & (Join-Path $root 'check-release.ps1') -ApkDir $out; $checkFailed = $LASTEXITCODE -ne 0 }
catch { Write-Host $_ -ForegroundColor Red; $checkFailed = $true }
if ($checkFailed -or $DryRun) {
  git -C $root checkout -- app/build.gradle.kts 2>&1 | Out-Null   # undo the version bump
  Write-Progress -Id 1 -Activity 'RetroGrab release' -Completed
  if ($checkFailed) { throw 'Update check failed - nothing was committed or published.' }
  Write-Host 'Dry run: build + update test passed. Nothing was committed or published.'
  return
}

Step 4 'Committing and pushing'
# 3. Commit + push (git prints harmless warnings on stderr; Windows PowerShell would treat them as fatal)
$ErrorActionPreference = 'Continue'
git -C $root add -A 2>&1 | Out-Null
git -C $root commit -m "Release $Version" 2>&1 | Out-Null
git -C $root push origin HEAD 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'git push failed - the release was NOT published.' }

Step 5 'Uploading the GitHub release'
# 4. GitHub release (the marker line tells the app how urgent it is; the app hides it)
$body = $Notes
if ($Level -ne 'normal') { $body = "$Notes`n`n[$Level]" }
$bodyFile = Join-Path $out 'notes.md'; [IO.File]::WriteAllText($bodyFile, $body)
gh release create "v$Version" (Get-ChildItem $out -Filter *.apk).FullName -R $repo -t "RetroGrab $Version" -F $bodyFile
if ($LASTEXITCODE -ne 0) { throw 'Creating the GitHub release failed.' }
Write-Progress -Id 1 -Activity 'RetroGrab release' -Completed
Write-Host "Published: https://github.com/$repo/releases/tag/v$Version"
Write-Host "Phones get the alert on the first app open after 6 h since their last check, or from the daily background check (24 h). Settings > Check for app updates shows it right away."
