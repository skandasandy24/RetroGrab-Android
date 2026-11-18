# Round 4: own repo + important / required updates

## 1. Releases now live in their own repo
- Repo: `skandasandy24/RetroGrab-Android` (public). In `AppUpdater.kt`:
  `RELEASES_URL = "https://api.github.com/repos/skandasandy24/RetroGrab-Android/releases?per_page=30"`.
- Tags in this repo are `v<version>` (e.g. `v1.0.3`). Accept both `v` and `android-v` prefixes.
- `githubUrl()` must now check the path prefix `/skandasandy24/RetroGrab-Android` + releasePath
  (exact repo name match, case-insensitive; `/skandasandy24/RetroGrab/` must NOT match).
- Asset names stay `RetroGrab-<version>-<abi>.apk` / `-universal.apk`.

## 2. Update levels, read from the release notes (body)
The person publishing adds ONE marker line anywhere in the release body:
- `[important]` → **important**
- `[required]` → **required**
- no marker → **normal**
Also support an optional line `min-version: 1.0.2`: if the installed version is lower than that, treat the
newest release as **required** regardless of its marker.
Strip marker lines and `min-version:` lines from the notes shown to the user (match case-insensitively,
whole line, surrounding whitespace allowed).

Behaviour:
- **normal**: as now (Update / Later / Skip this version).
- **important**: title "Important update"; buttons Update and Later only (no Skip). Ignore any stored skip
  for it. Show on every app start (ignore the 6-hour throttle; still a network call, so cache the last
  result for 30 min). Notification text "Important update — tap to install".
- **required**: title "Update required"; text adds "This version of RetroGrab no longer works properly.
  Please update to keep using the app."; only an Update button. The dialog is not dismissable
  (`onDismissRequest = {}`, back press does not close it, `DialogProperties(dismissOnBackPress = false,
  dismissOnClickOutside = false)`). If the user cancels the download/install, show the dialog again.
  Downloads tabs stay hidden behind the dialog.
- If several newer releases exist, the level is the HIGHEST level among all releases newer than the installed
  version (so an important 1.0.3 followed by a normal 1.0.4 still counts as important when you're on 1.0.2),
  and the APK offered is from the newest one.

## 3. Skip mistakes are recoverable
- Settings → "Check for app updates" always shows the update dialog if a newer version exists, even if skipped.
- When a version is skipped, show a Snackbar "Skipped 1.0.3. You can still update from Settings." with an
  Undo action that clears the skip.

## 4. Version
`versionCode = 4`, `versionName = "1.0.3"`.

Keep everything else unchanged. Don't run Gradle or use the network. Reply with files changed.
