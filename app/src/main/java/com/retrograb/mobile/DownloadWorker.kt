package com.retrograb.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val app = context.applicationContext as RetroGrabApp
    private val library = MediaLibrary(app)
    private val history = History(app)
    private val extractors = Extractors()
    private val engine = YoutubeDL.getInstance()
    private val logs = ArrayDeque<String>()
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private var progress = 0
    private var lastUpdate = 0L
    private var saved = 0
    private var skipped = 0
    private var currentTitle = ""

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url").orEmpty()
        val source = Source.fromUrl(url) ?: return@withContext Result.failure(workDataOf("log" to "Unsupported link"))
        currentTitle = url
        val cache = File(app.cacheDir, "downloads/$id")
        try {
            setForeground(foreground(source))
            report(source, "Preparing ${source.label} download", 0, force = true)
            app.awaitEngine()
            if (source == Source.YOUTUBE) {
                app.awaitStartupUpdate { report(source, "Updating yt-dlp…", 0, force = true) }
            }
            app.engineLock.withLock {
                cache.deleteRecursively()
                check(cache.mkdirs()) { "Cannot create download cache" }
                when (source) {
                    Source.YOUTUBE -> youtube(url, cache)
                    Source.INSTAGRAM -> instagram(url, cache)
                    Source.SPOTIFY -> spotify(url, cache)
                }
            }
            check(saved + skipped > 0) { "No files were downloaded" }
            val summary = "Saved $saved file(s), skipped $skipped."
            report(source, summary, 100, force = true)
            Result.success(workDataOf("log" to logs.joinToString("\n"), "percent" to 100))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = (error.message ?: error.cause?.message ?: "Download failed")
                .replace(Regex("https?://[^\\s]+")) { match -> match.value.substringBefore('?') }
                .take(500)
            runCatching {
                history.add(HistoryItem(currentTitle, source, System.currentTimeMillis(), "Failed · $message"))
            }.onFailure { logs.addLast("Could not record the failure in History.") }
            logs.addLast(message)
            Result.failure(workDataOf("log" to logs.joinToString("\n"), "percent" to progress))
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun value(key: String, default: String) = inputData.getString(key) ?: default
    private fun option(key: String) = inputData.getBoolean(key, false)

    private fun foreground(source: Source, status: String? = null): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(
                "downloads", "RetroGrab downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, "downloads")
            .setContentTitle("RetroGrab · ${source.label}")
            .setContentText(status ?: logs.lastOrNull() ?: "Preparing download")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
            .setProgress(100, progress, progress == 0)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(id.hashCode(), notification, type)
    }

    private fun report(source: Source, line: String, percent: Int, force: Boolean = false, status: String? = null) {
        if (line.isNotBlank()) {
            val cleaned = line.trim().replace(Regex("https?://[^\\s]+")) { it.value.substringBefore('?') }.take(180)
            if (logs.lastOrNull() != cleaned) {
                if (logs.size >= 15) logs.removeFirst()
                logs.addLast(cleaned)
            }
        }
        progress = percent.coerceIn(0, 100)
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastUpdate >= 350) {
            lastUpdate = now
            setProgressAsync(workDataOf("log" to logs.joinToString("\n"), "percent" to progress, "status" to status))
            notifications.notify(id.hashCode(), foreground(source, status).notification)
        }
    }

    private fun request(url: String, directory: File, output: String = "%(title).120B [%(id)s].%(ext)s") =
        YoutubeDLRequest(url).apply {
            addOption("--newline")
            addOption("--color", "never")
            addOption("--no-cache-dir")
            addOption("-o", File(directory, output).absolutePath)
        }

    private suspend fun execute(
        request: YoutubeDLRequest, source: Source, start: Int = 0, end: Int = 98,
        logOutput: Boolean = true, processId: String = id.toString()
    ) = try {
        runInterruptible(Dispatchers.IO) {
            engine.execute(request, processId, if (logOutput) { percent, _, line ->
                val position = if (percent < 0) progress else start + ((end - start) * percent / 100).toInt()
                report(source, line, position)
            } else null)
        }
    } finally {
        engine.destroyProcessById(processId)
    }

    private suspend fun youtube(url: String, directory: File) {
        val mode = value("yt.mode", "Video + Audio")
        val audio = mode == "Audio only"
        val format = if (audio) value("yt.audioFormat", "mp3") else value("yt.format", "mp4")
        val height = value("yt.quality", "Best").removeSuffix("p").toIntOrNull()
        val limit = height?.let { "[height<=$it]" }.orEmpty()
        val job = request(url, directory).apply {
            addOption(if (option("yt.playlist")) "--yes-playlist" else "--no-playlist")
            if (audio) {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", format)
            } else {
                val videoFilter = limit + if (format == "webm") "[ext=webm]" else ""
                val audioFilter = if (format == "webm") "[ext=webm]" else ""
                val selector = if (mode == "Video only") "bv$videoFilter"
                    else "bv*$videoFilter+ba$audioFilter/b$videoFilter"
                addOption("-f", selector)
                if (format == "mp4") addOption("-S", "ext:mp4:m4a")
                addOption("--merge-output-format", format)
                addOption("--remux-video", format)
            }
            if (option("yt.subtitles")) addOption("--write-subs")
            if (option("yt.thumbnail")) addOption("--write-thumbnail")
        }
        var downloadError: Exception? = null
        try {
            executeYouTube(job)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            downloadError = error
        }
        publish(directory, Source.YOUTUBE, "")
        downloadError?.let { throw it }
    }

    private suspend fun executeYouTube(request: YoutubeDLRequest) {
        try {
            execute(request, Source.YOUTUBE)
        } catch (error: Exception) {
            if (error is CancellationException || !youtubeUpdateErrors.containsMatchIn(error.message.orEmpty())) {
                throw error
            }
            report(Source.YOUTUBE, "Updating yt-dlp…", progress, force = true)
            try {
                app.updateYtDlp()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (updateError: Exception) {
                Log.w("DownloadWorker", "yt-dlp recovery update failed", updateError)
            }
            execute(request, Source.YOUTUBE)
        }
    }

    private suspend fun instagram(url: String, directory: File) {
        val source = Source.INSTAGRAM
        val mode = value("ig.mode", "Auto")
        val cookies = InstagramSession(app).copyTo(directory)
        val output = File(directory, "media").apply { mkdirs() }
        var images = emptyList<Extractors.Image>()
        var metadataFailure: Exception? = null
        if (mode != "Video") {
            try {
                val metadata = request(url, output).apply {
                    cookies?.let { addOption("--cookies", it.absolutePath) }
                    addOption("--dump-single-json")
                    addOption("--skip-download")
                    addOption("--ignore-no-formats-error")
                }
                val result = execute(metadata, source, 0, 10)
                images = extractors.images(JSONObject(result.out.lineSequence()
                    .last { it.trimStart().startsWith("{") }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                metadataFailure = error
            }
            if (images.isEmpty()) {
                try {
                    images = runInterruptible { extractors.instagramPostImages(url, cookies) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (metadataFailure == null) metadataFailure = error
                }
            }
            if (images.isEmpty()) {
                if (mode == "Photos") {
                    throw IllegalStateException("Instagram exposed no photo URLs for this post.", metadataFailure)
                }
                report(source, "No photo URLs found. Checking for videos.", 10, force = true)
            }
        }
        if (mode != "Photos") {
            val video = request(url, output).apply {
                cookies?.let { addOption("--cookies", it.absolutePath) }
                addOption("-f", "bv*+ba/b")
                addOption("--merge-output-format", "mp4")
            }
            try {
                execute(video, source, 10, 90)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (images.isEmpty() && output.listFiles().orEmpty().none(::isVideo)) throw metadataFailure ?: error
                report(source, "Some Instagram entries could not be downloaded as video.", 90, force = true)
            }
        }
        publish(output, source, "Instagram") { isVideo(it) }
        var imageFailures = 0
        images.forEachIndexed { index, image ->
            report(source, "Saving photo ${index + 1} of ${images.size}", 90 + index * 9 / images.size, force = true)
            try {
                val file = runInterruptible { extractors.saveImage(image, output, index + 1, cookies) }
                save(file, source, "Instagram")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                imageFailures++
                history.add(HistoryItem(image.title, source, System.currentTimeMillis(),
                    "Failed · ${error.message.orEmpty().take(180)}"))
            }
        }
        currentTitle = url
        check(imageFailures == 0) { "$imageFailures of ${images.size} photos failed. Saved $saved file(s)." }
    }

    private fun isVideo(file: File) = file.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov")

    private suspend fun spotify(url: String, directory: File) {
        val source = Source.SPOTIFY
        val tracks = runInterruptible { extractors.spotify(url) }
        val format = value("sp.format", "mp3")
        val quality = value("sp.quality", "320 kbps").substringBefore(' ')
        val folder = if (option("sp.subfolder")) "Spotify" else ""
        val useSpotifyVersion = value(SPOTIFY_LANGUAGE_SETTING, SPOTIFY_LANGUAGE_ASK) == SPOTIFY_LANGUAGE_ORIGINAL
        var rememberedLanguage: String? = null
        var failures = 0
        tracks.forEachIndexed { index, original ->
            var track = original
            currentTitle = track.name
            val start = index * 98 / tracks.size
            val end = (index + 1) * 98 / tracks.size
            report(source, "${index + 1}/${tracks.size} · ${track.name}", start, force = true)
            val trackDirectory = File(directory, index.toString()).apply { mkdirs() }
            try {
                report(source, "Checking language versions…", start, force = true)
                val plain = SpotifyLanguages.stripLanguage(track.title)
                val music = searchYouTubeMusic("${track.artist} - $plain", 10, trackDirectory, start)
                val youtube = searchSpotifyCandidates(
                    "ytsearch8:${track.name}", SpotifySource.YOUTUBE, 8, trackDirectory, start
                )
                val versions = SpotifyLanguages.detect(track, music + youtube) { candidate ->
                    lookupVersionDetails(candidate, trackDirectory, start)
                }
                val spotifyLanguage = SpotifyLanguages.spotifyLanguage(track)
                val languages = versions.keys.sortedWith(compareBy({ it != spotifyLanguage }, { it }))
                val choices = languages.joinToString(", ", transform = SpotifyLanguages::displayName)
                if (languages.isNotEmpty()) report(source, "Found versions: $choices", start, force = true)

                var selected: SpotifyCandidate? = null
                var filename = track.filename
                if (languages.size > 1) {
                    val question = LanguageQuestion(track.name, languages, spotifyLanguage, tracks.size > 1)
                    val remembered = rememberedLanguage?.takeIf { it in languages }
                    val language = when {
                        remembered != null -> remembered
                        useSpotifyVersion -> question.defaultLanguage
                        else -> {
                            report(source, "Asking which language: ${track.name} ($choices)", start, force = true,
                                status = "Waiting for you to choose a language…")
                            val answer = LanguagePrompt.ask(app, question) { fallback ->
                                report(source, "No answer - used ${SpotifyLanguages.displayName(fallback)}",
                                    start, force = true)
                            }
                            if (answer == null) {
                                report(source, "Skipped (no language chosen): ${track.name}", end, force = true)
                                skipped++
                                history.add(HistoryItem(track.name, source, System.currentTimeMillis(), "Skipped"))
                                return@forEachIndexed
                            }
                            if (answer.useForRest) rememberedLanguage = answer.language
                            answer.language
                        }
                    }
                    selected = versions.getValue(language)
                    if (language != ORIGINAL_LANGUAGE_KEY && language != spotifyLanguage) {
                        val suffix = " [${SpotifyLanguages.displayName(language)}]"
                        track = original.copy(title = plain + suffix)
                        currentTitle = track.name
                        filename = "${safeName(track.artist)} - ${safeName(plain, 110 - suffix.length)}$suffix"
                    }
                    report(source, "Match: ${selected.title} [${selected.source.label}]", start, force = true)
                }
                val name = "$filename.$format"
                if (option("sp.skip") && (library.hasAudio(name, "Spotify") || library.hasAudio(name, ""))) {
                    skipped++
                    history.add(HistoryItem(track.name, source, System.currentTimeMillis(), "Skipped"))
                    return@forEachIndexed
                }
                val candidate = selected ?: run {
                    val tagged = if (plain != track.title) {
                        searchYouTubeMusic(track.name, 5, trackDirectory, start)
                    } else emptyList()
                    val candidates = (music + tagged + youtube).filter { it.title.isNotBlank() }.distinctBy { it.id }
                    findMatch(track, candidates, trackDirectory, start)
                }
                val cover = track.coverUrl?.let { coverUrl ->
                    try {
                        runInterruptible { extractors.saveSpotifyCover(coverUrl, File(trackDirectory, "cover.jpg")) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("DownloadWorker", "Spotify cover download failed", error)
                        report(source, "Spotify cover unavailable, using YouTube thumbnail", start, force = true)
                        null
                    }
                }
                val download = request(candidate.url, trackDirectory,
                    filename.replace("%", "%%") + ".%(ext)s").apply {
                    addOption("--no-playlist")
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", format)
                    addOption("--audio-quality", "${quality}K")
                    if (cover == null) addOption("--embed-thumbnail")
                }
                execute(download, source, start, end)
                val file = File(trackDirectory, name)
                check(file.isFile) { "No matching audio file was produced" }
                val tagged = runInterruptible { tagSpotifyTrack(track, file, cover) }
                save(tagged, source, folder, track.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                val detail = error.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                    .replace(Regex("https?://[^\\s]+"), "[URL]").take(180)
                report(source, "Failed: ${track.name}. $detail", end, force = true)
                history.add(HistoryItem(track.name, source, System.currentTimeMillis(), "Failed · $detail"))
            } finally {
                trackDirectory.deleteRecursively()
            }
        }
        currentTitle = url
        check(failures == 0) { "$failures of ${tracks.size} tracks failed. Saved $saved, skipped $skipped." }
    }

    private suspend fun searchYouTubeMusic(
        query: String, limit: Int, directory: File, start: Int
    ): List<SpotifyCandidate> {
        val encoded = Uri.encode(query, "/").replace(Regex("[!'()*]")) { match ->
            "%${match.value[0].code.toString(16).uppercase()}"
        }
        return searchSpotifyCandidates(
            "https://music.youtube.com/search?q=$encoded#songs", SpotifySource.YOUTUBE_MUSIC, limit, directory, start
        )
    }

    private suspend fun searchSpotifyCandidates(
        url: String, source: SpotifySource, limit: Int, directory: File, start: Int
    ): List<SpotifyCandidate> = try {
        val search = request(url, directory).apply {
            addOption("--no-update")
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--playlist-end", limit)
            addOption("--skip-download")
        }
        val entries = spotifyMetadata(search, start).getJSONArray("entries")
        (0 until minOf(entries.length(), limit)).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val videoId = entry.optString("id").takeIf(youtubeId::matches) ?: return@mapNotNull null
            val title = entry.stringField("title").orEmpty()
            val channel = entry.stringField("channel") ?: entry.stringField("uploader").orEmpty()
            SpotifyCandidate(videoId, title, channel, entry.duration(), source)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("DownloadWorker", "${source.label} Spotify search failed", error)
        report(Source.SPOTIFY, "${source.label} search failed", start, force = true)
        emptyList()
    }

    private suspend fun lookupVersionDetails(
        candidate: SpotifyCandidate, directory: File, start: Int
    ): SpotifyCandidateDetails? = try {
        val metadata = request("https://music.youtube.com/watch?v=${candidate.id}", directory).apply {
            addOption("--no-update")
            addOption("--skip-download")
            addOption("-J")
        }
        // Each concurrent lookup must own its yt-dlp process, including when a video ID repeats.
        val json = spotifyMetadata(metadata, start, "$id-language-${UUID.randomUUID()}")
        SpotifyCandidateDetails(json.duration(), json.stringField("album"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("DownloadWorker", "Spotify language metadata lookup failed", error)
        null
    }

    private suspend fun findMatch(
        track: Track, candidates: List<SpotifyCandidate>, directory: File, start: Int
    ): SpotifyCandidate {
        val top = candidates.map { it to SpotifyLanguages.score(track, it) }
            .sortedByDescending { it.second }.take(4)
        for ((candidate) in top) {
            val duration = candidate.duration ?: lookupDuration(candidate, directory, start)
            if (SpotifyLanguages.lengthMismatch(track, duration)) {
                report(Source.SPOTIFY,
                    "Skipping '${candidate.title}' - length ${duration}s vs ${track.durationMs?.div(1000.0)}s",
                    start, force = true)
                continue
            }
            report(Source.SPOTIFY, "Match: ${candidate.title} [${candidate.source.label}]", start, force = true)
            return candidate
        }
        error("No matching song found")
    }

    private suspend fun lookupDuration(candidate: SpotifyCandidate, directory: File, start: Int): Double? = try {
        val metadata = request(candidate.url, directory).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
        }
        spotifyMetadata(metadata, start).duration()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("DownloadWorker", "Spotify candidate duration lookup failed", error)
        report(Source.SPOTIFY, "Could not check length for '${candidate.title}'", start, force = true)
        null
    }

    private suspend fun spotifyMetadata(
        request: YoutubeDLRequest, start: Int, processId: String = id.toString()
    ): JSONObject {
        val result = execute(request, Source.SPOTIFY, start, start, logOutput = false, processId = processId)
        val json = result.out.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: error("No metadata returned")
        return JSONObject(json)
    }

    private fun JSONObject.stringField(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun JSONObject.duration(): Double? = optDouble("duration").takeIf { it.isFinite() && it > 0 }

    private fun tagSpotifyTrack(track: Track, audio: File, cover: File?): File {
        val outputDirectory = File(audio.parentFile, "tagged")
        check(outputDirectory.mkdir()) { "Cannot create tagged audio directory" }
        val output = File(outputDirectory, audio.name)
        // These are the executable and shared libraries initialized by youtubedl-android and FFmpeg.
        val ffmpeg = File(app.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val libraries = File(app.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val command = buildList {
            addAll(listOf(ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", audio.absolutePath))
            cover?.let { addAll(listOf("-i", it.absolutePath)) }
            addAll(listOf("-map", "0:a", "-map", if (cover == null) "0:v?" else "1", "-c", "copy",
                "-map_metadata", "-1", "-map_metadata:s:a", "-1",
                "-metadata", "title=${track.title}", "-metadata", "artist=${track.artist}"))
            track.album?.let { addAll(listOf("-metadata", "album=$it")) }
            track.trackNumber?.let { addAll(listOf("-metadata", "track=$it")) }
            if (audio.extension == "mp3") {
                addAll(listOf("-id3v2_version", "3", "-metadata:s:v", "title=Album cover",
                    "-metadata:s:v", "comment=Cover (front)"))
            } else {
                addAll(listOf("-disposition:v", "attached_pic"))
            }
            add(output.absolutePath)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = libraries.absolutePath
        }.start()
        try {
            var detail: String? = null
            val reader = thread(isDaemon = true) {
                detail = runCatching { process.inputStream.bufferedReader().useLines { it.lastOrNull() } }.getOrNull()
            }
            val status = process.waitFor()
            reader.join()
            check(status == 0 && output.length() > 0) {
                "FFmpeg could not write Spotify tags${detail?.let { ": $it" }.orEmpty()}"
            }
        } finally {
            process.destroy()
        }
        return output
    }

    private suspend fun publish(
        directory: File, source: Source, folder: String,
        include: (File) -> Boolean = { it.extension.lowercase() in youtubeOutputs }
    ) {
        directory.listFiles().orEmpty().filter { it.isFile && include(it) }.sortedBy { it.name }.forEach {
            save(it, source, folder)
        }
    }

    private companion object {
        val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        val youtubeUpdateErrors = Regex("Precondition check failed|HTTP Error 400|Sign in to confirm", RegexOption.IGNORE_CASE)
        val youtubeOutputs = setOf(
            "mp4", "webm", "mkv", "mp3", "m4a", "opus",
            "jpg", "jpeg", "png", "webp", "avif",
            "srt", "vtt", "ass", "lrc", "ttml", "srv1", "srv2", "srv3", "json3"
        )
    }

    private suspend fun save(file: File, source: Source, folder: String, title: String = file.nameWithoutExtension) {
        currentTitle = title
        val uri = library.publish(file, folder)
        history.add(HistoryItem(title, source, System.currentTimeMillis(), "Saved", uri.toString()))
        saved++
        file.delete()
    }
}
