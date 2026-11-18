package com.retrograb.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val appUpdateNotificationsKey = booleanPreferencesKey("appUpdateNotifications")
internal const val EXTRA_APP_UPDATE_VERSION = "com.retrograb.mobile.APP_UPDATE_VERSION"

internal enum class UpdateLevel { NORMAL, IMPORTANT, REQUIRED }

internal data class AppUpdate(
    val version: String,
    val body: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    val level: UpdateLevel
) {
    fun encode(): String = JSONObject()
        .put("version", version)
        .put("body", body)
        .put("releaseUrl", releaseUrl)
        .put("apkUrl", apkUrl)
        .put("apkSize", apkSize)
        .put("level", level.name)
        .toString()

    companion object {
        fun decode(value: String): AppUpdate {
            val json = JSONObject(value)
            return AppUpdate(
                json.getString("version"), json.getString("body"), json.getString("releaseUrl"),
                if (json.isNull("apkUrl")) null else json.getString("apkUrl"), json.getLong("apkSize"),
                UpdateLevel.entries.firstOrNull { it.name == json.optString("level") } ?: UpdateLevel.NORMAL
            )
        }
    }
}

class UpdateFileProvider : FileProvider()

internal class AppUpdater(private val context: Context) {
    private val client = OkHttpClient()
    private val preferencesLock = Mutex()
    private val releaseCheckLock = Mutex()
    private val directory = File(context.cacheDir, "updates")
    private var cleanedCache = false

    suspend fun cleanCache(pendingApk: String?) = withContext(Dispatchers.IO) {
        preferencesLock.withLock {
            if (!cleanedCache) {
                directory.listFiles().orEmpty().forEach { file ->
                    if (file.name != pendingApk) file.delete()
                }
                cleanedCache = true
            }
        }
    }

    fun cachedApk(name: String): File = File(directory, name)

    suspend fun claimStartupCheck(): Boolean {
        val now = System.currentTimeMillis()
        var due = false
        context.settings.edit {
            val last = it[lastStartupCheckKey]
            if (last == null || now - last >= TimeUnit.HOURS.toMillis(6)) {
                it[lastStartupCheckKey] = now
                due = true
            }
        }
        return due
    }

    suspend fun claimNotificationPermissionPrompt(): Boolean {
        var shouldPrompt = false
        context.settings.edit {
            if (it[appUpdateNotificationsKey] != false && it[notificationPromptedKey] != true) {
                it[notificationPromptedKey] = true
                shouldPrompt = true
            }
        }
        return shouldPrompt
    }

    suspend fun syncNotificationWork(enabled: Boolean? = null) = preferencesLock.withLock {
        val notify = if (enabled == null) {
            context.settings.data.first()[appUpdateNotificationsKey] != false
        } else {
            context.settings.edit { it[appUpdateNotificationsKey] = enabled }
            enabled
        }
        val manager = WorkManager.getInstance(context)
        val operation = if (notify) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            notifications.cancel(NOTIFICATION_ID)
            manager.cancelUniqueWork(WORK_NAME)
        }
        withContext(Dispatchers.IO) { operation.result.get() }
        Unit
    }

    suspend fun findUpdate(refresh: Boolean = false, keepPriorityOnError: Boolean = false): AppUpdate? =
        releaseCheckLock.withLock {
            val prefs = context.settings.data.first()
            val sameVersion = prefs[cachedInstalledVersionKey] == BuildConfig.VERSION_NAME
            val cached = if (sameVersion) prefs[cachedUpdateKey]?.let(AppUpdate::decode) else null
            val age = prefs[cachedCheckTimeKey]?.let { System.currentTimeMillis() - it }
            if (!refresh && sameVersion && age != null && age in 0 until TimeUnit.MINUTES.toMillis(30)) {
                return@withLock cached
            }

            val update = try {
                fetchUpdate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!keepPriorityOnError || cached == null || cached.level == UpdateLevel.NORMAL) throw error
                Log.w("AppUpdater", "Could not refresh the cached app update", error)
                return@withLock cached
            }
            context.settings.edit {
                it[cachedInstalledVersionKey] = BuildConfig.VERSION_NAME
                it[cachedCheckTimeKey] = System.currentTimeMillis()
                if (update == null) it.remove(cachedUpdateKey) else it[cachedUpdateKey] = update.encode()
            }
            update
        }

    private suspend fun fetchUpdate(): AppUpdate? {
        val request = Request.Builder().url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RetroGrab-Android")
            .get()
            .build()
        return client.newCall(request).consume { response ->
            if (!response.isSuccessful) throw IOException("GitHub releases returned HTTP ${response.code}")
            val releases = JSONArray(response.body?.string() ?: throw IOException("Empty GitHub response"))
            val newer = (0 until releases.length()).asSequence()
                .mapNotNull { releases.optJSONObject(it)?.let(::parseRelease) }
                .filter { compareVersions(it.version, BuildConfig.VERSION_NAME) > 0 }
                .toList()
            val newest = newer.maxWithOrNull { first, second -> compareVersions(first.version, second.version) }
            newest?.copy(level = newer.maxOf { it.level })
        }
    }

    suspend fun isSkipped(version: String): Boolean =
        versionKey(version) in context.settings.data.first()[skippedVersionsKey].orEmpty()

    suspend fun skip(version: String) = preferencesLock.withLock {
        val prefs = context.settings.edit {
            it[skippedVersionsKey] = it[skippedVersionsKey].orEmpty() + versionKey(version)
        }
        val notified = prefs[notificationReleaseKey]?.let(AppUpdate::decode)
        if (notified?.version == version && notified.level == UpdateLevel.NORMAL) {
            notifications.cancel(NOTIFICATION_ID)
        }
    }

    suspend fun clearSkip(version: String) = preferencesLock.withLock {
        context.settings.edit {
            it[skippedVersionsKey] = it[skippedVersionsKey].orEmpty() - versionKey(version)
        }
    }

    suspend fun notificationUpdate(version: String): AppUpdate? {
        val prefs = context.settings.data.first()
        return prefs[notificationReleaseKey]?.let(AppUpdate::decode)?.takeIf {
            it.version == version &&
                compareVersions(it.version, BuildConfig.VERSION_NAME) > 0 &&
                githubUrl(it.releaseUrl, "/releases/tag/") &&
                (it.apkUrl == null || githubUrl(it.apkUrl, "/releases/download/")) &&
                (it.level != UpdateLevel.NORMAL || versionKey(it.version) !in prefs[skippedVersionsKey].orEmpty())
        }
    }

    suspend fun notifyIfEligible(update: AppUpdate) = preferencesLock.withLock {
        val prefs = context.settings.data.first()
        val version = versionKey(update.version)
        if (prefs[appUpdateNotificationsKey] == false ||
            (update.level == UpdateLevel.NORMAL && version in prefs[skippedVersionsKey].orEmpty()) ||
            version in prefs[notifiedVersionsKey].orEmpty()
        ) return@withLock

        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
            if (notifications.getNotificationChannel(CHANNEL_ID).importance == NotificationManager.IMPORTANCE_NONE) {
                return@withLock
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@withLock

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.fromParts("retrograb-update", update.version, null))
            .putExtra(EXTRA_APP_UPDATE_VERSION, update.version)
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Commit the release and de-duplication marker together before exposing the notification.
        context.settings.edit {
            it[notificationReleaseKey] = update.encode()
            it[notifiedVersionsKey] = it[notifiedVersionsKey].orEmpty() + version
        }
        try {
            notifications.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("RetroGrab ${update.version} is available")
                    .setContentText(
                        if (update.level == UpdateLevel.IMPORTANT) "Important update — tap to install" else "Tap to update"
                    )
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (error: Exception) {
            context.settings.edit {
                it[notifiedVersionsKey] = it[notifiedVersionsKey].orEmpty() - version
                val previous = prefs[notificationReleaseKey]
                if (previous == null) it.remove(notificationReleaseKey) else it[notificationReleaseKey] = previous
            }
            throw error
        }
    }

    suspend fun download(update: AppUpdate, onProgress: (Int) -> Unit): File {
        var temporaryApk: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val request = Request.Builder().url(checkNotNull(update.apkUrl)).build()
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
                val file = File.createTempFile("retrograb-", ".apk", directory)
                temporaryApk = file
                client.newCall(request).consume(onCancelled = { file.delete() }) { response ->
                    saveApk(response, update, file, onProgress)
                }
                file
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { temporaryApk?.delete() }
            throw error
        }
    }

    private fun saveApk(response: Response, update: AppUpdate, file: File, onProgress: (Int) -> Unit) {
        if (!response.isSuccessful) throw IOException("APK download returned HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty APK response")
        val total = body.contentLength().takeIf { it > 0 } ?: update.apkSize
        if (total <= 0) throw IOException("APK size is unavailable")
        var received = 0L
        var progress = 0
        body.byteStream().use { input ->
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > total) throw IOException("APK exceeded the expected download size")
                    output.write(buffer, 0, count)
                    val percent = (received.toDouble() / total * 100).toInt().coerceAtMost(99)
                    if (percent != progress) {
                        progress = percent
                        onProgress(percent)
                    }
                }
            }
        }
        if (received != total || (update.apkSize > 0 && received != update.apkSize)) {
            throw IOException("APK download size does not match the release")
        }
        val apk = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (apk == null || apk.packageName != context.packageName || apk.versionName != update.version) {
            throw IOException("APK package or version does not match the update")
        }
        onProgress(100)
    }

    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun parseRelease(json: JSONObject): AppUpdate? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val version = tagPattern.matchEntire(json.optString("tag_name"))?.groupValues?.get(1) ?: return null
        val releaseUrl = json.optString("html_url")
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val available = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val asset = (Build.SUPPORTED_ABIS.asSequence() + "universal").firstNotNullOfOrNull { abi ->
            available.firstOrNull { candidate ->
                candidate.optString("name") == "RetroGrab-$version-$abi.apk" &&
                    githubUrl(candidate.optString("browser_download_url"), "/releases/download/")
            }
        }

        var level = UpdateLevel.NORMAL
        val body = if (json.isNull("body")) "" else json.optString("body")
        val notes = body.lineSequence().filter { line ->
            val trimmed = line.trim()
            val minimum = minVersionPattern.matchEntire(trimmed)?.groupValues?.get(1)
            when {
                trimmed.equals("[required]", ignoreCase = true) -> level = UpdateLevel.REQUIRED
                trimmed.equals("[important]", ignoreCase = true) -> level = maxOf(level, UpdateLevel.IMPORTANT)
                minimum != null -> {
                    if (compareVersions(BuildConfig.VERSION_NAME, minimum) < 0) level = UpdateLevel.REQUIRED
                }
                else -> return@filter true
            }
            false
        }.joinToString("\n").take(800)
        return AppUpdate(
            version, notes, releaseUrl, asset?.optString("browser_download_url"), asset?.optLong("size") ?: 0, level
        )
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/skandasandy24/RetroGrab-Android/releases?per_page=30"
        private const val WORK_NAME = "retrograb-app-updates"
        private const val CHANNEL_ID = "app-updates"
        private const val NOTIFICATION_ID = 1248542800
        private const val VERSION_PATTERN = "[0-9]+(?:\\.[0-9]+)*"
        private val tagPattern = Regex("(?:android-)?v($VERSION_PATTERN)")
        private val minVersionPattern = Regex("min-version:\\s*($VERSION_PATTERN)", RegexOption.IGNORE_CASE)
        private val lastStartupCheckKey = longPreferencesKey("lastAppUpdateStartupCheck")
        private val cachedInstalledVersionKey = stringPreferencesKey("appUpdateCacheInstalledVersion")
        private val cachedCheckTimeKey = longPreferencesKey("appUpdateCacheCheckTime")
        private val cachedUpdateKey = stringPreferencesKey("appUpdateCacheRelease")
        private val notificationPromptedKey = booleanPreferencesKey("appUpdateNotificationsPrompted")
        private val skippedVersionsKey = stringSetPreferencesKey("skippedAppUpdateVersions")
        private val notifiedVersionsKey = stringSetPreferencesKey("notifiedAppUpdateVersions")
        private val notificationReleaseKey = stringPreferencesKey("appUpdateNotificationRelease")

        fun githubUrl(url: String, releasePath: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps && parsed.host == "github.com" &&
                parsed.encodedPath.startsWith("/skandasandy24/RetroGrab-Android$releasePath", ignoreCase = true)
        }

        private fun parts(version: String) = version.split('.').map(String::toBigInteger)

        private fun versionKey(version: String) = parts(version).dropLastWhile { it == BigInteger.ZERO }.joinToString(".")

        fun compareVersions(first: String, second: String): Int {
            val left = parts(first)
            val right = parts(second)
            for (index in 0 until maxOf(left.size, right.size)) {
                val difference = left.getOrElse(index) { BigInteger.ZERO }
                    .compareTo(right.getOrElse(index) { BigInteger.ZERO })
                if (difference != 0) return difference
            }
            return 0
        }
    }
}

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val updater = (applicationContext as RetroGrabApp).appUpdater
        updater.findUpdate()?.let { updater.notifyIfEligible(it) }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("AppUpdater", "Periodic app update check failed", error)
        Result.success()
    }
}

private suspend fun <T> Call.consume(
    onCancelled: () -> Unit = {},
    read: (Response) -> T
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (continuation.isActive) continuation.resume(read(it))
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                if (continuation.isCancelled) onCancelled()
            }
        }
    })
}
