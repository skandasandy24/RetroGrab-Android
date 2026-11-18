# Round 3: in-app update alerts

Releases are published on GitHub in the public repo `skandasandy24/RetroGrab`, which also holds the desktop
app. Android releases have tags `android-v<version>` (e.g. `android-v1.0.2`); desktop releases use `v<version>`
and MUST be ignored. Android releases are NOT marked "latest", so do not use `/releases/latest`.

APK asset names on every Android release:
`RetroGrab-<version>-arm64-v8a.apk`, `RetroGrab-<version>-armeabi-v7a.apk`, `RetroGrab-<version>-x86_64.apk`,
`RetroGrab-<version>-universal.apk`.

## Update check
- New `AppUpdater.kt`. `GET https://api.github.com/repos/skandasandy24/RetroGrab/releases?per_page=30` with
  headers `Accept: application/vnd.github+json` and `User-Agent: RetroGrab-Android`. No auth.
- Take the non-draft, non-prerelease releases whose `tag_name` starts with `android-v`. Parse the version after
  that prefix (numbers separated by dots), pick the highest, and compare numerically with `BuildConfig.VERSION_NAME`.
- Pick the asset: the first entry of `Build.SUPPORTED_ABIS` that matches an asset name `-<abi>.apk`, otherwise
  `-universal.apk`. If no asset matches, fall back to opening the release `html_url` in the browser.
- Handle network errors and HTTP 403 rate limits quietly (log only) for automatic checks; show a Snackbar for
  manual checks ("You're on the latest version (1.0.2)" / "Couldn't check for updates").

## When it runs
- On app start, at most once every 6 hours (timestamp in DataStore).
- A WorkManager periodic job every 24 h (network connected constraint) that checks and, if there's a newer
  version that hasn't been skipped, posts a notification on a new "App updates" channel:
  title "RetroGrab <ver> is available", text "Tap to update". Tapping opens MainActivity with an extra that
  shows the update dialog. Don't post the same version twice.
- Settings: new row "Check for app updates" (shows current version), plus a switch
  "Notify me about app updates" (default on; turning it off cancels the periodic job).

## Update dialog (Material 3 AlertDialog)
- Title "Update available", text "RetroGrab <new> (you have <current>)" plus the release `body`
  (plain text, first ~800 chars, scrollable).
- Buttons: "Update" (primary), "Later", and a text button "Skip this version" (remember the skipped version;
  a newer one still shows).

## Download + install
- "Update": download the APK with OkHttp to `cacheDir/updates/` showing a determinate progress dialog with
  Cancel. Then install via `PackageInstaller` session (preferred) or `ACTION_VIEW` with a FileProvider URI and
  `application/vnd.android.package-archive`.
- Add `REQUEST_INSTALL_PACKAGES` permission. If `packageManager.canRequestPackageInstalls()` is false, show a
  short explanation then open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package; when the user
  returns, continue the install automatically.
- Add the FileProvider to the manifest if you use it (authority `${applicationId}.updates`).
- Delete old files in `cacheDir/updates/` on app start.

## Version
Set `versionCode = 3`, `versionName = "1.0.2"`.

Keep all existing behaviour and the Material 3 UI. Don't run Gradle or use the network.
Reply with the files you changed/added.
