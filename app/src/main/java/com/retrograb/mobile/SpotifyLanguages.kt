package com.retrograb.mobile

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.math.abs

internal const val SPOTIFY_LANGUAGE_SETTING = "sp.language"
internal const val SPOTIFY_LANGUAGE_ASK = "Ask me"
internal const val SPOTIFY_LANGUAGE_ORIGINAL = "Use Spotify's version"
internal const val ORIGINAL_LANGUAGE_KEY = "original"

internal enum class SpotifySource(val label: String) {
    YOUTUBE_MUSIC("ytmusic"), YOUTUBE("youtube")
}

internal data class SpotifyCandidate(
    val id: String,
    val title: String,
    val channel: String,
    val duration: Double?,
    val source: SpotifySource,
    val album: String? = null
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
}

internal data class SpotifyCandidateDetails(val duration: Double?, val album: String?)

internal object SpotifyLanguages {
    private val LANGUAGES = listOf(
        "tamil", "telugu", "hindi", "kannada", "malayalam", "marathi", "bengali", "punjabi",
        "gujarati", "odia", "bhojpuri", "urdu", "english", "spanish", "korean", "japanese",
        "chinese", "french", "arabic"
    ).associateWith { it } + ("bangla" to "bengali")
    private val quotes = Regex("""["'“”‘’`＂]""")
    private val nonWord = Regex("[^\\p{L}\\p{N}_]+")
    private const val WHITESPACE = "[\\u0009-\\u000d\\u001c-\\u001f\\u0085\\p{Z}]"
    private val languagePattern = LANGUAGES.keys.joinToString("|")
    private val titleSeparator = Regex("$WHITESPACE*[\\[(]|$WHITESPACE+-$WHITESPACE+")
    private val scoringTitleSeparator = Regex(" \\(| \\[| - ")
    private val languageTag = Regex(
        "$WHITESPACE*[\\[(]$WHITESPACE*($languagePattern)(?:$WHITESPACE+version)?$WHITESPACE*[\\])]",
        RegexOption.IGNORE_CASE
    )
    private val filmTag = Regex("""\(from$WHITESPACE+["＂]?([^\n]+?)["＂]?\)""", RegexOption.IGNORE_CASE)
    private val filmLanguageSuffix = Regex("$WHITESPACE*-$WHITESPACE*($languagePattern)$WHITESPACE*$", RegexOption.IGNORE_CASE)
    private val badWords = listOf(
        "live", "cover", "remix", "sped up", "slowed", "reverb", "karaoke", "8d", "nightcore",
        "1 hour", "instrumental", "mashup", "lofi", "lo-fi", "reprise", "acoustic"
    ).map { Regex("""(?<![\p{L}\p{N}_])${Regex.escape(it)}(?![\p{L}\p{N}_])""") }

    fun norm(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase().replace(quotes, "").replace(nonWord, " ").trim()

    fun languages(text: String): Set<String> = words(norm(text)).mapNotNull(LANGUAGES::get).toSet()

    private fun coreTitle(title: String): String = norm(titleSeparator.split(title, limit = 2).first())

    fun stripLanguage(title: String): String = title.replace(languageTag, "").trim { it.isWhitespace() || it == '\u0085' }

    fun displayName(language: String): String = language.replaceFirstChar { it.titlecase() }

    fun spotifyLanguage(track: Track): String = languages(track.title).singleOrNull() ?: ORIGINAL_LANGUAGE_KEY

    private fun filmOf(title: String): String? {
        val match = filmTag.find(Normalizer.normalize(title, Normalizer.Form.NFKC)) ?: return null
        return norm(match.groupValues[1].replace(filmLanguageSuffix, ""))
    }

    suspend fun detect(
        track: Track,
        candidates: List<SpotifyCandidate>,
        details: suspend (SpotifyCandidate) -> SpotifyCandidateDetails?
    ): Map<String, SpotifyCandidate> = coroutineScope {
        val want = words(coreTitle(stripLanguage(track.title)))
        if (want.isEmpty()) return@coroutineScope emptyMap()
        val film = filmOf(track.title).orEmpty()
        val sameSong = candidates.filter { candidate ->
            val overlap = want.intersect(words(coreTitle(candidate.title))).size.toDouble() / want.size
            if (film.isEmpty()) overlap >= 0.8 else overlap >= 0.5 && filmOf(candidate.title) == film
        }
        val permits = Semaphore(3)
        val lookups = sameSong.withIndex().asSequence().filter { (_, candidate) ->
            candidate.source == SpotifySource.YOUTUBE_MUSIC && languages(candidate.title).isEmpty()
        }.take(6).associate { (index, candidate) ->
            index to async { permits.withPermit { withTimeoutOrNull(20_000L) { details(candidate) } } }
        }

        val versions = linkedMapOf<String, SpotifyCandidate>()
        for ((index, candidate) in sameSong.withIndex()) {
            val metadata = lookups[index]?.await()
            val version = if (metadata == null) candidate else candidate.copy(
                duration = metadata.duration, album = metadata.album
            )
            if (lengthMismatch(track, version.duration)) continue
            val found = languages("${version.title} ${version.album.orEmpty()}")
            if (found.size > 1) continue
            val key = found.singleOrNull() ?: ORIGINAL_LANGUAGE_KEY
            if (key == ORIGINAL_LANGUAGE_KEY && version.source == SpotifySource.YOUTUBE) continue
            val existing = versions[key]
            if (existing == null ||
                (existing.source == SpotifySource.YOUTUBE && version.source == SpotifySource.YOUTUBE_MUSIC)
            ) {
                versions[key] = version
            }
        }

        val spotifyLanguage = spotifyLanguage(track)
        if (spotifyLanguage != ORIGINAL_LANGUAGE_KEY) {
            val original = versions.remove(ORIGINAL_LANGUAGE_KEY)
            if (original != null && versions[spotifyLanguage]?.source != SpotifySource.YOUTUBE_MUSIC) {
                versions[spotifyLanguage] = original
            }
        }
        versions
    }

    fun score(track: Track, candidate: SpotifyCandidate): Double {
        val ytTitle = candidate.title.lowercase()
        val channel = candidate.channel.lowercase()
        val spTitle = track.title.lowercase()
        val ytNorm = norm(candidate.title)
        val spNorm = norm(track.title)
        val core = norm(scoringTitleSeparator.split(track.title, limit = 2).first())
        var score = when {
            ytNorm == spNorm -> 60.0
            core.isNotEmpty() && core in ytNorm -> 15.0
            else -> 0.0
        }

        val want = languages("${track.title} ${track.album.orEmpty()}")
        val got = languages(candidate.title)
        if (want.isNotEmpty()) {
            score += when {
                got.any { it in want } -> 30
                got.isNotEmpty() -> -100
                track.strictLanguage -> -60
                else -> 0
            }
        }
        if (candidate.source == SpotifySource.YOUTUBE_MUSIC) score += 10
        val difference = durationDifference(track, candidate.duration)
        if (difference != null) {
            score -= difference.coerceAtMost(120.0)
            if (difference > 25) score -= 40
        } else if (candidate.duration == null) {
            score -= 5
        }
        score -= 30 * badWords.count { it.containsMatchIn(ytTitle) && !it.containsMatchIn(spTitle) }
        if (channel.endsWith(" - topic")) score += 20
        if ("audio" in ytTitle) score += 8
        if ("official video" in ytTitle || "music video" in ytTitle) score -= 4

        val expectedWords = words(spNorm)
        if (expectedWords.isNotEmpty()) {
            score += 25.0 * expectedWords.intersect(words(ytNorm)).size / expectedWords.size
        }
        val artists = track.artist.lowercase().split(',').map(String::trim)
        if (artists.any { it.isNotEmpty() && (it in ytTitle || it in channel) }) score += 15
        return score
    }

    fun lengthMismatch(track: Track, duration: Double?): Boolean =
        (durationDifference(track, duration) ?: 0.0) > 25

    private fun durationDifference(track: Track, duration: Double?): Double? =
        if (track.durationMs != null && duration != null) abs(duration - track.durationMs / 1000.0) else null

    private fun words(text: String): Set<String> =
        if (text.isEmpty()) emptySet() else text.split(' ').toSet()
}
