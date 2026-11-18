# Fix round 1 (from on-device testing)

The project builds and most features pass on an Android 16 x86_64 emulator: YouTube video + MP3, Instagram public
reel, share sheet, History, Settings, light/dark theme. Fix these four things only. Don't restructure other code.

1. **Instagram login WebView is blank.** Logcat shows
   `chromium: aw_browser_terminator.cc Renderer process (...) crash detected (code -1)` right after
   `loadUrl("https://www.instagram.com/accounts/login/")` in `RetroGrabScreen.kt` `InstagramWebView`.
   - Override `WebViewClient.onRenderProcessGone`: return true, remove/destroy the dead WebView, and recreate it
     (e.g. bump a `key()` counter in Compose) and reload, max 2 retries; after that show a Classic-style message
     "Login page crashed. Tap Retry." with a Retry button.
   - Set `setLayerType(View.LAYER_TYPE_SOFTWARE, null)` as a fallback on the retry attempt.
   - Set a normal mobile Chrome user agent (strip the `; wv` token from the default UA) so Instagram serves the
     login page.
   - Only call `callback()` (cookie capture) from `onPageFinished`, not `onPageStarted`, and only when the
     `sessionid` cookie exists for instagram.com.
   - Call `webView.destroy()` in `onRelease` of the AndroidView.

2. **Auto-update yt-dlp.** A fresh install's bundled yt-dlp is too old: YouTube fails with
   "Precondition check failed / HTTP 400" until the user taps Settings → Check for updates.
   - In `RetroGrabApp`, after `YoutubeDL.init`, start a background coroutine (Dispatchers.IO) that calls
     `YoutubeDL.updateYoutubeDL(context, UpdateChannel.STABLE)` at most once per 24 h (store last-check time in
     DataStore or SharedPreferences). Ignore failures (log only).
   - In `DownloadWorker`, before running a YouTube download, wait for that update if it is still in progress
     (a shared `Deferred`/`Mutex` is fine) and log "Updating yt-dlp…" to the download log while waiting.
   - Also: if a YouTube download fails and the error contains "Precondition check failed" or "HTTP Error 400"
     or "Sign in to confirm", force one update and retry once.

3. **Title bar minimise button shows no "_".** The middle □ and × render, the first button is empty. Draw the
   underscore as a small filled rectangle near the bottom of the button instead of a text glyph.

4. **Release signing.** In `app/build.gradle.kts` add a `release` signingConfig that reads
   `keystore.properties` from the project root (storeFile, storePassword, keyAlias, keyPassword) if that file
   exists, and use it for the `release` build type. Keep `isMinifyEnabled = false`. Add `keystore.properties`
   and `*.jks` to a `.gitignore`. Set `versionCode = 2`, `versionName = "1.0.1"`.

Don't run Gradle. Reply with the files you changed.
