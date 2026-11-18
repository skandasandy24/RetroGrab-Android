package com.retrograb.mobile

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File

data class Track(
    val artist: String,
    val title: String,
    val durationMs: Long? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val trackNumber: Int? = null
) {
    val name get() = "$artist - $title"
    val filename get() = "${safeName(artist)} - ${safeName(title)}"
}

fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

class Extractors {
    private val http = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()

    fun spotify(link: String): List<Track> {
        val input = link.toHttpUrl()
        val resolved = if (input.host == "spotify.link" || input.host == "spoti.fi") {
            http.newCall(Request.Builder().url(input).build()).execute().use { it.request.url }
        } else input
        require(resolved.host == "open.spotify.com") { "Use an open.spotify.com link" }
        val route = resolved.pathSegments.dropWhile {
            it == "embed" || it.startsWith("intl-")
        }
        val kind = route.getOrNull(0)
        val id = route.getOrNull(1).orEmpty()
        require(kind in listOf("track", "album", "playlist") && id.matches(Regex("[A-Za-z0-9]{22}"))) {
            "Use a Spotify track, album or playlist link"
        }
        val embed = "https://open.spotify.com/embed/$kind/$id"
        val html = http.newCall(Request.Builder().url(embed).build()).execute().use {
            check(it.isSuccessful) { "Spotify returned HTTP ${it.code}" }
            requireNotNull(it.body).string()
        }
        val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()
        val props = JSONObject(requireNotNull(script) { "Spotify embed data is unavailable" })
            .getJSONObject("props").getJSONObject("pageProps")
        val entity = props.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: props.optJSONObject("data")?.optJSONObject("entity")
            ?: props.optJSONObject("entity")
            ?: error("Spotify embed has no track data")

        val albumEntity = entity.takeIf { kind == "album" }
        val albumName = albumEntity?.let { it.text("name") ?: it.text("title") }
        val albumCover = albumEntity?.let { spotifyCover(it) }

        fun parseTrack(raw: JSONObject): Track? {
            val row = raw.optJSONObject("track") ?: raw.optJSONObject("item") ?: raw
            val title = row.text("title") ?: row.text("name") ?: return null
            val artists = row.optJSONArray("artists")?.objects()
                ?.mapNotNull { it.text("name") }?.joinToString(", ")
            val artist = artists?.takeIf { it.isNotBlank() } ?: row.text("subtitle")
                ?: row.text("artist") ?: return null
            val fields = listOf(row, raw).distinct()
            val albums = fields.mapNotNull { it.optJSONObject("album") }
            return Track(
                artist = artist,
                title = title,
                durationMs = fields.firstNotNullOfOrNull { entry ->
                    listOf("duration", "durationMs", "duration_ms").firstNotNullOfOrNull { key ->
                        entry.optLong(key).takeIf { it > 0 }
                    }
                },
                album = albumName ?: fields.firstNotNullOfOrNull { entry ->
                    val album = entry.optJSONObject("album")
                    album?.text("name") ?: album?.text("title") ?: entry.text("album")
                        ?: entry.text("albumName")
                },
                coverUrl = albumCover ?: spotifyCover(*(albums + fields).toTypedArray()),
                trackNumber = fields.firstNotNullOfOrNull { entry ->
                    listOf("trackNumber", "track_number").firstNotNullOfOrNull { key ->
                        entry.optInt(key).takeIf { it > 0 }
                    }
                }
            )
        }

        val tracks = if (kind == "track") listOfNotNull(parseTrack(entity)) else {
            val rows = entity.optJSONArray("trackList") ?: entity.optJSONArray("tracks")
                ?: entity.optJSONObject("tracks")?.optJSONArray("items")
            rows?.objects()?.mapNotNull(::parseTrack).orEmpty()
        }
        check(tracks.isNotEmpty()) { "Spotify embed contains no playable tracks" }
        return tracks
    }

    private fun JSONObject.text(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private data class Cover(val url: String, val area: Long)

    private fun spotifyCover(vararg entries: JSONObject): String? {
        fun cover(url: String?, area: Long): Cover? =
            url?.takeIf { it.toHttpUrlOrNull()?.isHttps == true }?.let { Cover(it, area) }

        fun images(value: Any?): List<Cover> = when (value) {
            is JSONObject -> {
                val width = value.optInt("width").coerceAtLeast(0).toLong()
                val height = value.optInt("height").coerceAtLeast(0).toLong()
                val area = if (width > 0 && height > 0) width * height else maxOf(width, height).let { it * it }
                listOfNotNull(cover(value.text("url"), area)) + images(value.opt("sources"))
            }
            is JSONArray -> (0 until value.length()).flatMap { images(value.opt(it)) }
            is String -> listOfNotNull(cover(value, 0))
            else -> emptyList()
        }

        return entries.flatMap { entry ->
            images(entry.opt("coverArt")) + images(entry.opt("images")) + images(entry.opt("image")) +
                images(entry.optJSONObject("visualIdentity")?.opt("image"))
        }.maxByOrNull { it.area }?.url
    }

    fun saveSpotifyCover(url: String, file: File): File {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Spotify cover returned HTTP ${response.code}" }
            requireNotNull(response.body).byteStream().buffered().use { input ->
                input.mark(2)
                check(input.read() == 0xff && input.read() == 0xd8) { "Spotify cover is not a JPEG" }
                input.reset()
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

    private fun isImageUrl(url: String): Boolean =
        url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('.')?.lowercase() in imageExtensions

    private fun isImage(entry: JSONObject): Boolean =
        entry.optString("media_type") in setOf("1", "image", "photo", "GraphImage") ||
            entry.optString("__typename") == "GraphImage" ||
            entry.optString("ext").lowercase() in imageExtensions ||
            isImageUrl(entry.optString("url")) ||
            (entry.optString("vcodec") == "none" && entry.optString("acodec") == "none")

    data class Image(val url: String, val title: String)

    fun images(info: JSONObject): List<Image> {
        val result = linkedMapOf<String, Image>()
        fun visit(entry: JSONObject) {
            val explicit = listOf("display_url", "image_url").map { entry.optString(it) } +
                listOfNotNull(entry.optJSONObject("image_versions2")?.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optString("url"))
            val imageFormats = entry.optJSONArray("formats")?.objects()?.filter(::isImage).orEmpty()
            val image = (explicit + entry.optString("url") +
                imageFormats.asReversed().map { it.optString("url") }).firstOrNull {
                it.startsWith("https://") && (it in explicit || isImageUrl(it) ||
                    isImage(entry) || imageFormats.any { format -> format.optString("url") == it })
            }
            val isVideo = entry.optBoolean("is_video") ||
                entry.optString("media_type") in setOf("2", "video", "GraphVideo") ||
                entry.optString("__typename") == "GraphVideo" ||
                entry.optString("video_url").isNotEmpty() ||
                entry.optJSONArray("formats")?.objects()?.any {
                    it.optString("ext") in setOf("mp4", "webm", "mkv")
                } == true
            val isCarousel = entry.optJSONArray("carousel_media") != null ||
                entry.optJSONObject("edge_sidecar_to_children") != null || entry.optString("media_type") == "8"
            if (image != null && !isVideo && !isCarousel) {
                val title = entry.optString("title").ifBlank { entry.optString("id", "Instagram photo") }
                result[image] = Image(image, title)
            }
            listOf("entries", "carousel_media").forEach { key ->
                entry.optJSONArray(key)?.objects()?.forEach(::visit)
            }
            entry.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")?.objects()
                ?.mapNotNull { it.optJSONObject("node") }?.forEach(::visit)
        }
        visit(info)
        return result.values.toList()
    }

    private fun instagramRequest(url: okhttp3.HttpUrl, cookieFile: File?): Request.Builder {
        val request = Request.Builder().url(url).header("Referer", "https://www.instagram.com/")
        if (cookieFile != null && url.isHttps &&
            (url.host == "instagram.com" || url.host.endsWith(".instagram.com"))) {
            val cookies = cookieFile.readLines().map { it.split('\t') }.filter { it.size == 7 }
                .joinToString("; ") { "${it[5]}=${it[6]}" }
            if (cookies.isNotBlank()) request.header("Cookie", cookies)
        }
        return request
    }

    fun instagramPostImages(link: String, cookieFile: File?): List<Image> {
        val route = link.toHttpUrl().pathSegments
        val shortcode = route.getOrNull(1).orEmpty()
        if (route.firstOrNull() != "p" || !shortcode.matches(Regex("[A-Za-z0-9_-]+"))) return emptyList()
        val url = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis".toHttpUrl()
        val client = http.newBuilder().followRedirects(false).build()
        return client.newCall(instagramRequest(url, cookieFile).build()).execute().use { response ->
            check(response.isSuccessful) { "Instagram post JSON returned HTTP ${response.code}" }
            val root = JSONObject(requireNotNull(response.body).string())
            val data = root.optJSONObject("data") ?: root.optJSONObject("graphql") ?: root
            val posts = data.optJSONArray("items")?.objects() ?: listOfNotNull(
                data.optJSONObject("xdt_shortcode_media") ?: data.optJSONObject("shortcode_media")
            )
            posts.flatMap(::images).distinctBy { it.url }
        }
    }

    fun saveImage(image: Image, directory: File, index: Int, cookieFile: File?): File {
        val url = image.url.toHttpUrl()
        val permitted = listOf("instagram.com", "cdninstagram.com", "fbcdn.net")
        require(url.isHttps && permitted.any { url.host == it || url.host.endsWith(".$it") }) {
            "Instagram returned an unsupported image host"
        }
        val request = instagramRequest(url, cookieFile)
        val client = http.newBuilder().followRedirects(false).build()
        client.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "Instagram image returned HTTP ${response.code}" }
            val body = requireNotNull(response.body)
            val extension = when (body.contentType()?.toString()?.substringBefore(';')) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/avif" -> "avif"
                "image/heic" -> "heic"
                else -> error("Instagram did not return an image")
            }
            return File(directory, "${safeName(image.title)} - $index.$extension").apply {
                outputStream().use { output -> body.byteStream().copyTo(output) }
            }
        }
    }

    private companion object {
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "heic")
    }
}
