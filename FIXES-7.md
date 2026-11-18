# Round 7: language detection must find versions whose titles are IDENTICAL

Round 6 (title-only detection, ported from desktop) FAILED on a real song: it downloaded straight away.
Reference implementation that was verified against live YouTube Music: `docs/langdetect_reference.py`.
Port its `detect()` logic to Kotlin exactly and use it instead of round 6's `availableLanguages()`.
Keep round 6's question UI, notification, 15-min timeout, "use for rest of album/playlist", setting and skip.

## Why round 6 failed
1. "Not Ramaiya Vastavaiya (TAMIL)": on YouTube Music the Hindi, Tamil and Telugu versions all have the title
   `Not Ramaiya Vastavaiya`. The language is only in the **album**: `Jawan - Hindi`, `Jawan` (original = Tamil),
   `Jawan - Telugu`. Flat search results have no album → each candidate needs a full metadata lookup.
2. "Veezha Veera (From "O.M: Operation Mole")": versions are `Veera Veera (From "O.M: Operation Mole - Telugu")`
   and `... - Hindi")`; the original has NO tag and Spotify has no tag either. Round 6 would offer only
   Hindi/Telugu and drop the original that Spotify actually has.

## Algorithm (see reference for exact code)
- `plain` = Spotify title with the language tag stripped. Candidates = YT Music search
  `"<artists> - <plain>"#songs` (10, src=ytmusic) + `ytsearch8:<artists> - <original title>` (src=youtube).
- `filmOf(title)`: text inside `(from "…")`, with a trailing ` - <language>` removed, normalized.
- `sameSong(title)`: word overlap of coreTitle vs coreTitle(plain) ≥ 0.5 AND same film when the Spotify title has
  a film; without a film, overlap ≥ 0.8.
- For a same-song **ytmusic** candidate whose title has no language word: fetch details
  (`--skip-download -J https://music.youtube.com/watch?v=<id>`) for `album` and `duration` — at most 6 lookups,
  run up to 3 in parallel, each with a 20 s timeout; on failure keep the flat data.
- Reject if both durations known and > 25 s apart.
- Language of a version = languages(title + " " + album). More than one language → ignore. None → key "original".
  youtube-source candidates without a language are ignored (random uploads).
- Keep one version per key; a ytmusic candidate replaces a youtube one for the same key.
- If an "original" exists and Spotify's title names exactly one language L: the original becomes L (replacing a
  youtube-source L); if a ytmusic L already exists, drop the original.
- Ask when there are **2 or more** versions.

## Question options
- One radio row per version: the language capitalized (Tamil, Hindi, Telugu…). The "original" key shows as
  **"Original"**. Mark Spotify's version with " (Spotify's version)": that is L when Spotify's title has a
  language, otherwise "Original". Preselect Spotify's version.
- Sort: Spotify's version first, then the rest alphabetically.
- The chosen option downloads **that exact video id** (from the map). Do not search again.
- "Use for the rest of this album / playlist" remembers the language key; for later tracks, if that key exists in
  their map use it; if not, ask again.
- Tag/file title: for a non-original choice that isn't Spotify's language, append " [<Lang>]" to the Spotify title
  (e.g. `Not Ramaiya Vastavaiya [Hindi]`, after stripping Spotify's own "(TAMIL)" tag); Spotify's version keeps the
  Spotify title unchanged.
- When there is only 1 (or 0) version: no question, use round 5/6 `findMatch` as before.

## Acceptance (must hold; the reference script prints exactly this)
| Spotify track | expected versions → asks? |
|---|---|
| `Not Ramaiya Vastavaiya (TAMIL)`, Anirudh Ravichander, Sreerama Chandra, 203 s | tamil=Pl9Ddheyr5M (Spotify's), hindi=4OXJvm4JBsU, telugu=RYErWwKbJHU → asks |
| `Veezha Veera (From "O.M: Operation Mole")`, Sai Abhyankkar, Tippu, 236 s | original=wkz07LzGpcY (Spotify's), hindi=YCwR9Egv8X4, telugu=ebrQN6zFBzU → asks |
| `Ncs`, NoCopyrightSounds, 194 s | none → no question |

Log lines: "Checking language versions…", "Found versions: Tamil, Hindi, Telugu", "Asking which language: …".

`versionCode = 7`, `versionName = "1.0.6"`. Don't run Gradle or use the network. Reply with files changed.
