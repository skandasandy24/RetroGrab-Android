# Round 6: Spotify songs available in several languages → detect and ASK

Port this feature from the desktop RetroGrab (Python, `Source/spotify.py` + `engine.py`). Behaviour must match it.
Indian film songs (and others) are released in several languages with the same tune and near-identical titles;
the language tag in the title tells them apart, e.g. `Veezha Veera (From "X") [Tamil]`.

## 1. Language helpers (new `SpotifyLanguages.kt` or inside Extractors/DownloadWorker)
- `LANGUAGES` word → language: tamil, telugu, hindi, kannada, malayalam, marathi, bengali, bangla→bengali,
  punjabi, gujarati, odia, bhojpuri, urdu, english, spanish, korean, japanese, chinese, french, arabic.
- `norm(s)`: NFKC normalize, lowercase, remove quotes `"'“”‘’`＂`, replace every run of non-word chars with one
  space, trim.
- `languages(text)`: set of LANGUAGES values for words of `norm(text)` that are keys.
- `coreTitle(title)`: `norm` of the part before the first ` (`, ` [` or ` - `
  (`'Veezha Veera (From "X") [Tamil]'` → `veezha veera`).
- `stripLanguage(title)`: remove `[Tamil]`, `(Tamil)`, `[Tamil Version]`, `(telugu version)` etc. (regex
  `\s*[\[(]\s*(<all LANGUAGES keys>)(?:\s+version)?\s*[\])]`, case-insensitive).
- `withLanguage(track, lang)`: copy with title = `stripLanguage(title) + " [" + Lang + "]"`,
  album = stripLanguage(album) or null, and `strictLanguage = true`.
- `spotifyLanguage(track)`: the single language found in `title + album`, else null.

## 2. Candidate search (replace the current ytsearch8-only gather)
`gather(track)`: merge (dedupe by video id, keep order):
1. YouTube Music search WITHOUT the language tag: yt-dlp `--flat-playlist --dump-single-json`
   on `https://music.youtube.com/search?q=<urlencoded "Artist1, Artist2 - <stripped title>">#songs`, first 10
   entries, mark them `source = ytmusic`.
2. If the stripped title differs from the original: the same YT Music search WITH the original title, first 5.
3. `ytsearch8:<Artist1, Artist2 - <original title>>`.
Each failing search just logs and is skipped.

## 3. Scoring (replace current scoring; same as desktop `score`)
Higher is better. ytTitle/channel lowercased.
- `norm(ytTitle) == norm(spTitle)` → +60; else if coreTitle(spTitle) non-empty and contained in norm(ytTitle) → +15.
- want = languages(spTitle + " " + album), got = languages(ytTitle). If want non-empty: got∩want → +30;
  else if got non-empty → −100; else if track.strictLanguage → −60.
- source ytmusic → +10.
- duration known on both: diff = |yt − sp| seconds; −min(diff,120); if diff > 25 → −40. Unknown yt duration → −5.
- BAD_WORDS (live, cover, remix, sped up, slowed, reverb, karaoke, 8d, nightcore, 1 hour, instrumental, mashup,
  lofi, lo-fi, reprise, acoustic): word-boundary in ytTitle and not in spTitle → −30 each.
- channel ends with " - topic" → +20. ytTitle contains "audio" → +8. "official video"/"music video" → −4.
- +25 × (share of spTitle words found in ytTitle words). Any artist name in ytTitle or channel → +15.

`findMatch(track, candidates)`: rank; for the top 4 in order: if duration unknown, look it up
(`--dump-single-json --no-playlist` on the watch URL; on error log and keep it); skip if > 25 s off Spotify's
length (log "Skipping '<title>' - length …"); first survivor wins, log "Match: <title> [ytmusic|youtube]".
None → song fails with "No matching song found".

## 4. Detect languages
`availableLanguages(candidates, track)`:
- want = words of coreTitle(track.title); film = text inside `(from …)` in track.title (case-insensitive,
  after normalizing `＂` to `"`), may be null.
- For each candidate: langs = languages(title); skip unless exactly 1 language; skip if both durations known and
  > 25 s apart; sameTitle = want non-empty and |want ∩ words(coreTitle(title))| / |want| ≥ 0.5;
  sameFilm = film != null and norm(film) is contained in norm(title).
  If sameTitle and (sameFilm or film == null) → add that language.
- Return sorted list.

## 5. Asking (the important part)
In the Spotify loop, per track:
```
candidates = gather(track)
langs = availableLanguages(candidates, track)
if langs.size > 1:
    if remembered in langs: lang = remembered
    else if setting == "Use Spotify's version": lang = spotifyLanguage(track) ?: langs[0]
    else: answer = ask(label, langs, default = spotifyLanguage(track), multi = tracks.size > 1)
          answer == null → log "Skipped (no language chosen): <label>", count as skipped, continue
          lang = answer.language; if answer.useForRest → remembered = lang
    extra = YT Music search for withLanguage(track, lang) (first 5)
    best = findMatch(withLanguage(track, lang), merge(extra, candidates))
    track = track with title = withLanguage(track, lang).title   // tags + file name show the language
else:
    best = findMatch(track, candidates)
```
`label` = "<Artist> - <Title>".

How `ask` works on Android (the worker runs in the background):
- A process-wide `LanguagePrompt` singleton holds a `MutableStateFlow<LanguageQuestion?>` and a
  `CompletableDeferred<LanguageAnswer?>`. The worker sets the question and suspends on the deferred.
- Worker status/progress text: "Waiting for you to choose a language…"; log line "Asking which language: <label>
  (<Tamil, Telugu, Hindi>)".
- Post a high-priority notification (new channel "Questions", importance HIGH):
  title "Choose a language", text "<label> is available in Tamil, Telugu, Hindi", tap opens MainActivity
  (extra shows the question). Cancel it once answered.
- In the UI: while a question exists, show a Material 3 `ModalBottomSheet` (or AlertDialog) over the current
  screen: title "Choose language", text "This song is available in more than one language:", the song label,
  radio list of the languages (capitalized), Spotify's language preselected and marked "(Spotify's version)"; if
  no Spotify language, preselect the first. If `multi`: checkbox "Use this language for the rest of this album /
  playlist" (default off). Buttons: "Download" (primary) and "Skip song". Back / swipe-down = Skip song.
- If the app is not open, the question waits; if it is not answered within 15 minutes, use the default
  language, log "No answer - used <Lang>", and continue (don't hang forever). Cancelling the download cancels the
  question too.
- Survive configuration changes (rotation) — the question lives in the singleton, not the composable.

## 6. Setting
Settings → Spotify: "Songs in several languages": **Ask me** (default) / **Use Spotify's version**.

## 7. Version
`versionCode = 6`, `versionName = "1.0.5"`.

Keep everything else (tags, cover art, skip-existing, subfolder) working. Don't run Gradle or use the network.
Reply with files changed.
