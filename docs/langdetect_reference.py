"""Prototype of the improved language detection (metadata only, nothing is downloaded).
Usage: python -I langdetect.py "<artists>" "<spotify title>" <spotify seconds>"""
import json, re, subprocess, sys, unicodedata, urllib.parse

LANGUAGES = {"tamil": "tamil", "telugu": "telugu", "hindi": "hindi", "kannada": "kannada", "malayalam": "malayalam",
             "marathi": "marathi", "bengali": "bengali", "bangla": "bengali", "punjabi": "punjabi",
             "gujarati": "gujarati", "odia": "odia", "bhojpuri": "bhojpuri", "urdu": "urdu", "english": "english",
             "spanish": "spanish", "korean": "korean", "japanese": "japanese", "chinese": "chinese",
             "french": "french", "arabic": "arabic"}
TAG = re.compile(r"\s*[\[(]\s*(" + "|".join(LANGUAGES) + r")(?:\s+version)?\s*[\])]", re.I)


def norm(s):
    s = unicodedata.normalize("NFKC", s or "").lower()
    s = re.sub(r"[\"'“”‘’`＂]", "", s)
    return re.sub(r"[^\w]+", " ", s).strip()


def langs(text):
    return {LANGUAGES[w] for w in norm(text).split() if w in LANGUAGES}


def core(title):
    return norm(re.split(r"\s*[\(\[]|\s+-\s+", title or "", maxsplit=1)[0])


def film_of(title):
    m = re.search(r"\(from\s+[\"＂]?(.+?)[\"＂]?\)", unicodedata.normalize("NFKC", title or ""), re.I)
    if not m:
        return None
    return norm(re.sub(r"\s*-\s*(" + "|".join(LANGUAGES) + r")\s*$", "", m.group(1), flags=re.I))


def ytdlp(*args):
    out = subprocess.run(["yt-dlp", "--no-update", *args], capture_output=True, text=True, encoding="utf8").stdout
    return out


def search(url, n):
    out = ytdlp("--flat-playlist", "-J", "--playlist-end", str(n), url)
    try:
        return [e for e in json.loads(out).get("entries") or [] if e and e.get("id")]
    except Exception:
        return []


def details(vid):
    out = ytdlp("--skip-download", "-J", f"https://music.youtube.com/watch?v={vid}")
    try:
        d = json.loads(out)
        return {"duration": d.get("duration"), "album": d.get("album"), "title": d.get("title")}
    except Exception:
        return {}


def same_song(title, sp_title, sp_film):
    want = set(core(sp_title).split())
    have = set(core(title).split())
    sim = len(want & have) / len(want) if want else 0
    if sp_film:
        return film_of(title) == sp_film and sim >= 0.5
    return sim >= 0.8


def detect(artists, sp_title, sp_dur):
    plain = TAG.sub("", sp_title).strip()
    q = f"{artists} - {plain}"
    cands = search("https://music.youtube.com/search?q=" + urllib.parse.quote(q) + "#songs", 10)
    for e in cands:
        e["src"] = "ytmusic"
    cands += [dict(e, src="youtube") for e in search(f"ytsearch8:{artists} - {sp_title}", 8)]
    sp_film = film_of(sp_title)
    sp_lang = langs(sp_title)
    versions = {}  # language or "original" -> (video id, title, album)
    checked = 0
    for e in cands:
        title = e.get("title") or ""
        if not same_song(title, plain, sp_film):
            continue
        d = {"duration": e.get("duration"), "album": None}
        # YouTube Music often has identical titles for every language: the album says which one it is
        if e["src"] == "ytmusic" and not langs(title) and checked < 6:
            d = details(e["id"]) or d
            checked += 1
        if d.get("duration") and abs(d["duration"] - sp_dur) > 25:
            continue
        found = langs(f"{title} {d.get('album') or ''}")
        if len(found) > 1:
            continue
        key = next(iter(found)) if found else "original"
        if e["src"] == "youtube" and key == "original":
            continue  # random uploads without a tag are not a language version
        # official YouTube Music tracks beat random YouTube uploads of the same language
        if key not in versions or (versions[key][3] == "youtube" and e["src"] == "ytmusic"):
            versions[key] = (e["id"], title, d.get("album"), e["src"])
    # the untagged original IS Spotify's language (Spotify says "(TAMIL)", YouTube Music just says "Jawan")
    if "original" in versions and len(sp_lang) == 1:
        lang = next(iter(sp_lang))
        if lang not in versions or versions[lang][3] == "youtube":
            versions[lang] = versions.pop("original")
        else:
            versions.pop("original")
    return versions


if __name__ == "__main__":
    v = detect(sys.argv[1], sys.argv[2], int(sys.argv[3]))
    print(f"{sys.argv[2]}  ->  {len(v)} version(s); ASK = {len(v) > 1}")
    for k, (vid, t, a, src) in sorted(v.items()):
        print(f"   {k:9} {vid} [{src}]  title={t!r}  album={a!r}")
