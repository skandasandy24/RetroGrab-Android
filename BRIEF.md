# RetroGrab Mobile — build brief

## What it is
RetroGrab is the owner's own Windows desktop app (v1.5.0, Python + yt-dlp + gallery-dl). It downloads
YouTube videos/audio, Instagram reels/posts, and Spotify songs (matched to audio and saved as MP3).
It has a Windows 95 look ("Classic 95" theme) with a dark mode. Build the **Android** version.
Reference files are in `reference-assets/` (logo.png = app icon, titlebar.png = small title-bar icon,
desktop-settings.json = the desktop app's options, which the mobile options should mirror).

## Tech (fixed)
- Native Android, Kotlin, Jetpack Compose, single module `app`, package `com.retrograb.mobile`.
- minSdk 24, targetSdk 35, compileSdk 35. AGP 8.7.x, Kotlin 2.0.x, Gradle 8.10.x (write gradle-wrapper.properties;
  the owner will add the wrapper jar).
- Downloads engine: **youtubedl-android** (`io.github.junkfood02.youtubedl-android:library`, `:ffmpeg`, `:aria2c`,
  latest 0.17.x) which bundles yt-dlp + ffmpeg. Init in `Application.onCreate`. Add a
  "Check for updates" action that calls `YoutubeDL.updateYoutubeDL(context, UpdateChannel.STABLE)`.
- Run downloads in a foreground service (WorkManager with foreground info is fine) with a progress notification,
  so they continue when the app is in the background. Android 13+ POST_NOTIFICATIONS permission.
- Save finished files to public storage via MediaStore: videos → `Movies/RetroGrab/`, audio → `Music/RetroGrab/`,
  images → `Pictures/RetroGrab/`, with Instagram/Spotify subfolders like the desktop app. Download to the app cache
  first, then copy into MediaStore and delete the temp file.

## Screens / features
Three tabs at the top, exactly like desktop: **YouTube**, **Instagram**, **Spotify**. Each tab: URL field with a
Paste button, the options below, a big "Download" button, and a progress bar + log text area (show yt-dlp's
progress lines, like the desktop log window).

- **Share sheet**: register an `ACTION_SEND` text/plain intent filter so "Share → RetroGrab" from the YouTube,
  Instagram and Spotify apps opens RetroGrab with the link filled in and the right tab selected (detect by host).
- **YouTube options**: Mode (Video + Audio / Audio only / Video only), Quality (Best, 2160p, 1440p, 1080p, 720p,
  480p, 360p), Format (mp4, webm, mkv; for audio: mp3, m4a, opus), toggles: Playlist, Subtitles, Thumbnail.
  Audio only → `-x --audio-format <fmt>`. Use `-f "bv*[height<=H]+ba/b[height<=H]"` style selectors and
  `--merge-output-format`.
- **Instagram options**: Mode (Auto / Video / Photos). "Log in to Instagram…" button opens an in-app WebView on
  instagram.com/accounts/login; after login, read cookies from `CookieManager` and write a Netscape-format
  cookies.txt in app-private storage, passed to yt-dlp with `--cookies`. Show "Logged in" / "Log out" state.
  Photos/carousels: yt-dlp handles reels and video posts; for image posts use yt-dlp's playlist entries and
  `--write-thumbnail` fallback is NOT good enough — instead fetch post JSON via yt-dlp `--dump-single-json`
  and download image URLs directly with OkHttp using the same cookies.
- **Spotify options**: Format (mp3, m4a), Quality (320 / 256 / 192 / 128 kbps), toggles: "Put in subfolder",
  "Skip songs already downloaded". Accept track, album and playlist links. Get the track list from the public
  embed page (`https://open.spotify.com/embed/track|album|playlist/<id>`, parse the JSON inside the
  `__NEXT_DATA__` script tag for title + artists). For each track, download with yt-dlp
  `"ytsearch1:<artist> - <title> audio"`, `-x --audio-format mp3 --audio-quality 320K`, `--embed-metadata`,
  `--embed-thumbnail`, output name `<Artist> - <Title>.mp3`. Skip if that file already exists in MediaStore.
- **History** screen (menu): list of past downloads (title, source, date, status), tap to open the file.
  Store in Room or a JSON file.
- **Settings** (menu): theme Classic 95 light / dark toggle, remember last tab and all options
  (DataStore), "Check for updates" (yt-dlp update), About with version.
- Footer line on every screen: "Only download what you have the right to."

## Look — Windows 95, done properly
- Teal desktop background (#008080) in light mode; in dark mode use #0B1A1A desktop with #2B2B2B windows.
- The main content sits in a "window": #C0C0C0 face, navy title bar (gradient #000080 → #1084D0) with the
  titlebar icon + "RetroGrab" in white bold, and decorative _ □ × buttons.
- Bevelled controls drawn with Compose borders: raised = white top/left + #808080/#000 bottom/right; pressed
  and text fields = inverted (sunken). Tabs look like Win95 property-sheet tabs.
- Progress bar = sunken box filled with navy blocks (segmented), not a smooth Material bar.
- Font: a pixel/system-style font. Bundle "W95FA" only if license allows; otherwise use Compose
  `FontFamily.SansSerif` bold for titles and `FontFamily.Monospace` for the log.
- Launcher icon from reference-assets/logo.png (adaptive icon, teal background, and a legacy PNG set).
- Touch targets ≥ 48dp, layout works on small phones (360dp wide) and respects system bars (edge-to-edge insets).

## Deliverables
- Full Gradle project in this folder (settings.gradle.kts, build.gradle.kts, app/…, gradle/libs.versions.toml,
  gradle/wrapper/gradle-wrapper.properties, gradle.properties with `android.useAndroidX=true`).
  ABI splits not needed; include `abiFilters` arm64-v8a, armeabi-v7a, x86_64. `extractNativeLibs=true`
  (required by youtubedl-android).
- `README.md` with how to build (`gradlew assembleDebug`) and where the APK lands.
- Do NOT try to run Gradle or download anything; the owner builds it and will send back any compile errors.
