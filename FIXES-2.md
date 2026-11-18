# Fix round 2: native Android UI (replace the Windows 95 look)

The owner wants this to look and feel like a normal modern Android app, not a Windows window.
Keep ALL behaviour (download logic, worker, share intent, settings persistence, history, Instagram login,
auto-update) unchanged. Only replace the UI layer.

## Remove
- The fake desktop/window frame, the navy title bar, and the _ □ × buttons.
- Bevelled Win95 controls (`ClassicControls.kt`): delete the file once nothing uses it.
- Segmented block progress bar, teal desktop background, "Classic 95" theme option in Settings.

## Use Material 3
- Add `androidx.compose.material3:material3` (from the Compose BOM) and
  `androidx.compose.material:material-icons-extended`. Remove the old `compose-material` dependency if unused.
- Theme: `MaterialTheme` with light and dark color schemes that follow the system setting
  (Settings keeps a "Theme: System / Light / Dark" choice). Use dynamic color on Android 12+ only if the owner
  turns on "Use wallpaper colors" in Settings (default off). Brand colours from the logo:
  primary navy #1E3A8A (dark-scheme primary #9DB4FF), secondary gold #E0A800, tertiary teal #0F8A8A.
- `enableEdgeToEdge()` in MainActivity and correct insets via Scaffold.

## Layout
- `Scaffold` with:
  - `CenterAlignedTopAppBar` or `TopAppBar`: small round app logo (drawable titlebar/launcher foreground) +
    "RetroGrab", and a Settings icon action on the right.
  - `NavigationBar` at the bottom with 4 items: YouTube (Icons.Rounded.SmartDisplay), Instagram
    (Icons.Rounded.PhotoCamera), Spotify (Icons.Rounded.MusicNote), History (Icons.Rounded.History).
    Share intent still selects the right tab.
- Download tabs (one scrollable Column, 16dp side padding):
  - `OutlinedTextField` for the link with a trailing Paste icon button (ContentPaste) and a clear (X) button
    when not empty. Single line, URI keyboard, ImeAction.Go starts the download.
  - Options inside an `ElevatedCard`/`OutlinedCard` titled "Options": dropdowns via
    `ExposedDropdownMenuBox` (Mode, Quality, Format …), toggles as `ListItem` rows with a `Switch`
    (Playlist, Subtitles, Thumbnail / Put in subfolder, Skip songs already downloaded).
  - Instagram tab: a `ListItem` card showing "Logged in" / "Not logged in" with a Log in / Log out
    `FilledTonalButton`.
  - Full-width `Button` "Download" (Icons.Rounded.Download), 56dp tall, disabled while a job runs; while running
    show a "Cancel" `OutlinedButton` next to it if the worker supports cancel (WorkManager cancelUniqueWork).
  - Status card: `LinearProgressIndicator` (determinate when percent known, else indeterminate), status text,
    and an expandable "Show log" section (monospace, max ~200 lines, auto-scrolls to the bottom).
- History tab: `LazyColumn` of `ListItem`s with a leading icon per source, title (2 lines max, ellipsis),
  supporting text "YouTube · 18 Nov, 12:41", trailing status (check icon for Saved, error icon for Failed).
  Tap opens the file (ACTION_VIEW with the content URI). Empty state with an icon and "No downloads yet".
  Long-press or overflow menu: "Delete from history".
- Settings screen (from top bar icon, with back arrow): Theme (System/Light/Dark), Use wallpaper colors,
  Update yt-dlp now (shows current version + result in a Snackbar), Where files are saved
  (Movies/Music/Pictures › RetroGrab), About (version), and the line "Only download what you have the right
  to." as small supporting text at the bottom of Settings only (not on every screen).
- Instagram login screen: full-screen with a TopAppBar ("Log in to Instagram", back arrow) and the WebView
  filling the rest. Keep all the crash-recovery logic from round 1.
- Messages/errors as Snackbars, not text in the layout.
- Back button / predictive back works: Settings and login screens go back to the tabs.

Don't run Gradle or use the network. Reply with the files you changed/added/deleted.
