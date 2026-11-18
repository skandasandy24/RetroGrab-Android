# Round 5: Spotify matching + tags (bugs found in testing)

Test: Spotify track https://open.spotify.com/track/73OLulKEMiUukFBVGCRNTd (artist NoCopyrightSounds, title "Ncs").
Result: file name was right ("NoCopyrightSounds - Ncs.mp3") but the MP3's embedded tags came from YouTube:
title = the YouTube video title, album = "Spotify". The match is also just `ytsearch1:` (first result).
The desktop RetroGrab matches by title, artist AND length, and tags files with Spotify's data. Do the same.

## 1. Collect more data from the Spotify embed (`Extractors.kt`)
For every track also read, when present: `duration` (ms), album name, cover image URL (largest image),
track number. Field names vary between track/album/playlist embeds; check `duration`, `durationMs`,
`duration_ms`, album under `album`/`albumName`, images under `coverArt.sources`, `images`, `visualIdentity.image`.
For album embeds, the album name is the entity's own `name`/`title` and the cover is the entity's image.
Keep working if any of these are missing.

## 2. Better matching (`DownloadWorker.kt`)
- Search `ytsearch8:<artist> - <title>` with `--flat-playlist --dump-single-json` (no download) to get candidates
  with `title`, `duration`, `channel`, `url`/`id`.
- Score each candidate:
  - duration: if the Spotify duration is known, reject candidates more than 15 s off; prefer closest
    (biggest weight).
  - title similarity: normalized (lowercase, strip punctuation, strip "official video/audio/lyrics/lyric video/
    visualizer/hd/4k") token overlap with "<artist> <title>".
  - penalize titles containing live, cover, remix, sped up, slowed, reverb, karaoke, 8d, nightcore, 1 hour,
    instrumental, mashup UNLESS the Spotify title also contains that word.
  - small bonus if channel contains the artist name or ends with "- Topic".
- If no candidate survives the duration filter, fall back to the best title-scored one and write a log line
  "No exact length match, using closest result".
- Log the chosen video: "Matched: <yt title> (<m:ss>)".
- Download that specific video URL with the existing audio options, but do NOT use `--embed-metadata`
  (it writes YouTube's data).

## 3. Tag the file with Spotify data
After download + conversion, write tags with ffmpeg (the bundled one, via `FFmpeg`/`YoutubeDL` ffmpeg path
from youtubedl-android, e.g. run `ffmpeg` through `com.yausername.ffmpeg` or use yt-dlp's `--postprocessor-args`
— pick what works with the library):
- title = Spotify title, artist = Spotify artists joined with ", ", album = Spotify album (omit if unknown —
  never "Spotify"), track = track number if known.
- Cover art: download the Spotify cover JPG with OkHttp and embed it (mp3: ID3 APIC via
  `-map 0:a -map 1 -c copy -id3v2_version 3 -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)"`;
  m4a: `-disposition:v attached_pic`). If no cover, fall back to the YouTube thumbnail (`--embed-thumbnail`).
- File name stays `<Artist> - <Title>.<ext>` (sanitize illegal characters).

## 4. Version
`versionCode = 5`, `versionName = "1.0.4"`.

Keep everything else unchanged. Don't run Gradle or use the network. Reply with files changed.
