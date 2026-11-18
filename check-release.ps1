<#
  Safety check run by release.ps1 BEFORE anything is published. Stops the release if phones
  that already have RetroGrab could not update to the new APKs.

  (The PC app's 1.5.1 broke every user's update because the build tools changed quietly.
   This is the Android version of that lesson.)

  Checks:
    1. Build tools are the pinned ones (JDK 21 from Android Studio, Gradle 8.14, AGP 8.7.3)
       and no library uses a floating "+" version.
    2. Every APK is signed with OUR key (a different key = "App not installed" on every phone).
    3. Package name unchanged, versionCode higher than the published release, minSdk not raised.
    4. Real update test on the emulator: install the currently published APK, update it to the
       new one over the top (same as the in-app updater), and the new version must start
       without crashing and keep the app's data (no reinstall).

  Standalone:  .\check-release.ps1 -ApkDir .\release-out
#>
param([Parameter(Mandatory)] [string]$ApkDir)
$ErrorActionPreference = 'Continue'   # native tools print to stderr; every check is explicit below
$root = $PSScriptRoot
$repo = 'skandasandy24/RetroGrab-Android'

# ---- pinned values: change ONLY on purpose, after reading the warning above ----
$PINNED_CERT = 'cbde253e0676f5993a3c5751df9fa5b23d2322d647ae10249dbabe496d4056f9'
$PACKAGE = 'com.retrograb.mobile'
$JAVA_MAJOR = '21'
$GRADLE = 'gradle-8.14-all.zip'
$AGP = '8.7.3'
$MAX_MIN_SDK = 24

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
$aapt = Join-Path $bt.FullName 'aapt2.exe'
$apksigner = Join-Path $bt.FullName 'apksigner.bat'
$adb = "$sdk\platform-tools\adb.exe"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$problems = New-Object System.Collections.Generic.List[string]
function Fail($msg) { $problems.Add($msg); Write-Host "  FAIL  $msg" -ForegroundColor Red }
function Ok($msg) { Write-Host "  ok    $msg" -ForegroundColor Green }

function Get-ApkInfo($apk) {
  $b = (& $aapt dump badging $apk) -join "`n"
  $cert = ((& $apksigner verify --print-certs $apk) -join "`n")
  [pscustomobject]@{
    Package     = [regex]::Match($b, "package: name='([^']+)'").Groups[1].Value
    VersionCode = [int][regex]::Match($b, "versionCode='(\d+)'").Groups[1].Value
    VersionName = [regex]::Match($b, "versionName='([^']+)'").Groups[1].Value
    MinSdk      = [int][regex]::Match($b, "minSdkVersion:'(\d+)'").Groups[1].Value
    Cert        = [regex]::Match($cert, 'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
  }
}

function Part($n, $what) { Write-Progress -Id 2 -ParentId 1 -Activity 'Update safety checks' -Status "$n of 4: $what" -PercentComplete (($n - 1) * 25) }
Part 1 'Build tools'
Write-Host "`n== 1. Build tools"
$jv = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1).ToString()
if ($jv -match "version `"$JAVA_MAJOR\.") { Ok "JDK $JAVA_MAJOR ($env:JAVA_HOME)" } else { Fail "JDK is '$jv', expected $JAVA_MAJOR" }
$wrapper = Get-Content "$root\gradle\wrapper\gradle-wrapper.properties" -Raw
if ($wrapper -match [regex]::Escape($GRADLE)) { Ok "Gradle $GRADLE" } else { Fail "Gradle wrapper changed (expected $GRADLE)" }
$toml = Get-Content "$root\gradle\libs.versions.toml" -Raw
if ($toml -match "agp = `"$([regex]::Escape($AGP))`"") { Ok "Android Gradle Plugin $AGP" } else { Fail "AGP changed (expected $AGP)" }
$floating = [regex]::Matches($toml, '"[^"]*\+[^"]*"') | ForEach-Object Value
if ($floating) { Fail "floating library versions (pin them): $($floating -join ', ')" } else { Ok 'all library versions pinned' }

Part 2 'Downloading the published release'
Write-Host "`n== 2+3. APKs vs the published release"
$prevDir = Join-Path $env:TEMP "retrograb-prev-release"
Remove-Item $prevDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $prevDir | Out-Null
$ErrorActionPreference = 'Continue'
gh release download -R $repo -p '*x86_64*.apk' -D $prevDir 2>&1 | Out-Null

$prevApk = Get-ChildItem $prevDir -Filter *.apk | Select-Object -First 1
if (-not $prevApk) { Fail "couldn't download the published x86_64 APK from $repo (no internet / gh not logged in?)" }
else {
  $prev = Get-ApkInfo $prevApk.FullName
  Ok "published: $($prev.VersionName) (code $($prev.VersionCode))"
  if ($prev.Cert -ne $PINNED_CERT) { Fail "the PUBLISHED release isn't signed with the pinned key - stop and ask" }
}
Part 3 'Checking signing key, package, versionCode, minSdk'
$apks = Get-ChildItem $ApkDir -Filter *.apk
if (-not $apks) { Fail "no APKs in $ApkDir" }
foreach ($apk in $apks) {
  $i = Get-ApkInfo $apk.FullName
  $n = $apk.Name
  if ($i.Cert -ne $PINNED_CERT) { Fail "$n is signed with a DIFFERENT key ($($i.Cert)) - phones would refuse the update" }
  if ($i.Package -ne $PACKAGE) { Fail "$n package is '$($i.Package)', must stay $PACKAGE" }
  if ($i.MinSdk -gt $MAX_MIN_SDK) { Fail "$n minSdk $($i.MinSdk) > $MAX_MIN_SDK - older phones would be cut off" }
  if ($prev -and $i.VersionCode -le $prev.VersionCode) { Fail "$n versionCode $($i.VersionCode) is not higher than published $($prev.VersionCode)" }
  if ($problems.Count -eq 0) { Ok "$n  $($i.VersionName) (code $($i.VersionCode)), our key, minSdk $($i.MinSdk)" }
}

Part 4 'Emulator: install published, update to new, launch'
Write-Host "`n== 4. Real update test on the emulator (published -> new)"
$newApk = $apks | Where-Object Name -like '*x86_64*' | Select-Object -First 1
if ($problems.Count -gt 0) { Write-Host '  skipped (fix the problems above first)' }
elseif (-not $newApk -or -not $prevApk) { Fail 'need both the published and the new x86_64 APK for the update test' }
else {
  $ErrorActionPreference = 'Continue'
  $dev = (& $adb devices) -match "`tdevice$"
  if (-not $dev) {
    Write-Host '  starting the Sandy_Mobile emulator...'
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Sandy_Mobile', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Minimized
    & $adb wait-for-device
    for ($t = 0; $t -lt 120 -and ((& $adb shell getprop sys.boot_completed) -join '').Trim() -ne '1'; $t++) { Start-Sleep 2 }
  }
  & $adb uninstall $PACKAGE 2>&1 | Out-Null
  $r = (& $adb install $prevApk.FullName 2>&1) -join ' '
  if ($r -notmatch 'Success') { Fail "installing the published APK failed: $r" }
  else {
    & $adb shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
    Start-Sleep 6
    $before = ([regex]::Match(((& $adb shell dumpsys package $PACKAGE) -join "`n"), 'firstInstallTime=[^
]+')).Value.Trim()
    & $adb logcat -c
    $r = (& $adb install -r $newApk.FullName 2>&1) -join ' '
    if ($r -notmatch 'Success') { Fail "UPDATING over the published version failed: $r" }
    else {
      & $adb shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
      Start-Sleep 10
      $dump = (& $adb shell dumpsys package $PACKAGE) -join "`n"
      $after = ([regex]::Match($dump, 'firstInstallTime=[^\r\n]+')).Value
      $code = [int]([regex]::Match($dump, 'versionCode=(\d+)').Groups[1].Value)
      $alive = ((& $adb shell pidof $PACKAGE) -join '').Trim()
      $crash = (& $adb logcat -d -b crash) -join "`n"
      $want = (Get-ApkInfo $newApk.FullName).VersionCode
      if ($code -ne $want) { Fail "after updating, the phone has versionCode $code, expected $want" }
      elseif (-not $before -or $before -ne $after.Trim()) { Fail "app was reinstalled instead of updated (data would be lost)" }
      elseif ($crash -match $PACKAGE) { Fail "new version CRASHED after the update:`n$crash" }
      elseif (-not $alive) { Fail 'new version is not running after launch (crashed or closed)' }
      else { Ok "updated $($prev.VersionName) -> new over the top, data kept, app running (pid $alive)" }
    }
  }

}

Write-Progress -Id 2 -Activity 'Update safety checks' -Completed
Remove-Item $prevDir -Recurse -Force -ErrorAction SilentlyContinue
if ($problems.Count -gt 0) {
  Write-Host "`nRELEASE BLOCKED - $($problems.Count) problem(s). Nothing was published." -ForegroundColor Red
  exit 1
}
Write-Host "`nAll update checks passed." -ForegroundColor Green
exit 0
