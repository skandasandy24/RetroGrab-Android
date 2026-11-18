package com.retrograb.mobile

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

data class HistoryItem(
    val title: String, val source: Source, val date: Long, val status: String, val uri: String = ""
)

class History(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "history.json"))

    fun read(): List<HistoryItem> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val records = JSONArray(String(file.readFully(), Charsets.UTF_8))
        List(records.length()) { index ->
            val entry = records.getJSONObject(index)
            HistoryItem(entry.getString("title"), Source.valueOf(entry.getString("source")),
                entry.getLong("date"), entry.getString("status"), entry.optString("uri"))
        }
    }

    fun add(item: HistoryItem) = synchronized(lock) {
        write(listOf(item) + read())
    }

    fun remove(item: HistoryItem): Unit = synchronized(lock) {
        val items = read().toMutableList()
        if (items.remove(item)) write(items)
    }

    private fun write(items: List<HistoryItem>) {
        val records = JSONArray()
        items.forEach {
            records.put(JSONObject().put("title", it.title).put("source", it.source.name)
                .put("date", it.date).put("status", it.status).put("uri", it.uri))
        }
        val output = file.startWrite()
        try {
            output.write(records.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object { private val lock = Any() }
}

fun safeName(name: String): String {
    val cleaned = name.map { character ->
        if (character in "\\/:*?\"<>|" || character.isISOControl()) '_' else character
    }.joinToString("").trim(' ', '.')
    val bounded = StringBuilder()
    var bytes = 0
    var offset = 0
    while (offset < cleaned.length) {
        val codePoint = cleaned.codePointAt(offset)
        val character = String(Character.toChars(codePoint))
        bytes += character.toByteArray(Charsets.UTF_8).size
        if (bytes > 110) break
        bounded.append(character)
        offset += character.length
    }
    return bounded.toString().trim(' ', '.').ifBlank { "RetroGrab" }
}

class MediaLibrary(private val context: Context) {
    private fun mime(file: File): String = when (file.extension.lowercase()) {
        "opus", "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mkv" -> "video/x-matroska"
        "jpg", "jpeg" -> "image/jpeg"
        "avif", "heic", "png", "webp" -> "image/${file.extension.lowercase()}"
        "srt", "vtt", "ass", "lrc", "ttml", "srv1", "srv2", "srv3", "json3" -> "application/octet-stream"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: error("Unsupported output format: ${file.extension}")
    }

    private fun root(mime: String) = when (mime.substringBefore('/')) {
        "video" -> Environment.DIRECTORY_MOVIES
        "audio" -> Environment.DIRECTORY_MUSIC
        "image" -> Environment.DIRECTORY_PICTURES
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun collection(mime: String): Uri = if (Build.VERSION.SDK_INT >= 29) {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        when (mime.substringBefore('/')) {
            "video" -> MediaStore.Video.Media.getContentUri(volume)
            "audio" -> MediaStore.Audio.Media.getContentUri(volume)
            "image" -> MediaStore.Images.Media.getContentUri(volume)
            else -> MediaStore.Downloads.getContentUri(volume)
        }
    } else error("Relative paths require Android 10")

    private fun path(mime: String, folder: String) =
        "${root(mime)}/RetroGrab/" + if (folder.isEmpty()) "" else "$folder/"

    @Suppress("DEPRECATION")
    private fun legacyDirectory(mime: String, folder: String): File {
        val base = File(Environment.getExternalStoragePublicDirectory(root(mime)), "RetroGrab")
        return if (folder.isEmpty()) base else File(base, folder)
    }

    fun hasAudio(name: String, folder: String): Boolean {
        val mime = mime(File(name))
        if (Build.VERSION.SDK_INT < 29) return File(legacyDirectory(mime, folder), name).isFile
        val resolver = context.contentResolver
        return resolver.query(collection(mime), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(name, path(mime, folder)), null)?.use { it.moveToFirst() } == true
    }

    suspend fun publish(file: File, folder: String): Uri {
        val mime = mime(file)
        if (Build.VERSION.SDK_INT < 29) {
            val directory = legacyDirectory(mime, folder)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
            var destination = File(directory, file.name)
            var suffix = 1
            while (!destination.createNewFile()) {
                destination = File(directory, "${file.nameWithoutExtension} (${suffix++}).${file.extension}")
            }
            try {
                file.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                return suspendCancellableCoroutine { continuation ->
                    MediaScannerConnection.scanFile(context, arrayOf(destination.path), arrayOf(mime)) { _, uri ->
                        continuation.resume(uri ?: androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.files", destination))
                    }
                }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path(mime, folder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection(mime), values)) { "Cannot allocate public storage" }
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish ${file.name}" }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
