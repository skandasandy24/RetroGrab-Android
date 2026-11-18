package com.retrograb.mobile

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

val Context.settings by preferencesDataStore("settings")

class RetroGrabApp : Application() {
    internal val appUpdater by lazy { AppUpdater(this) }
    private val initialization = Executors.newSingleThreadExecutor()
    private val updatePrefs by lazy { getSharedPreferences("ytdlp_update", MODE_PRIVATE) }
    private lateinit var startupUpdate: Job
    val engineLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        initialization.submit {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Aria2c.getInstance().init(this)
        }.also { engine = it }
        initialization.shutdown()
        startupUpdate = CoroutineScope(Dispatchers.IO).launch {
            try {
                awaitEngine()
                engineLock.withLock {
                    val lastCheck = updatePrefs.getLong("last_check", 0L)
                    if (System.currentTimeMillis() - lastCheck >= 24 * 60 * 60 * 1000L) {
                        updateYtDlp()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("RetroGrabApp", "Automatic yt-dlp update failed", error)
            }
        }
    }

    private lateinit var engine: java.util.concurrent.Future<*>

    suspend fun awaitEngine() = withContext(Dispatchers.IO) { engine.get(); Unit }

    suspend fun awaitStartupUpdate(onWaiting: () -> Unit) {
        if (!startupUpdate.isCompleted) {
            onWaiting()
            startupUpdate.join()
        }
    }

    // Run on Dispatchers.IO while holding engineLock so the binary is idle.
    fun updateYtDlp(): YoutubeDL.UpdateStatus? {
        updatePrefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        return YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
    }
}

enum class Source(val label: String) {
    YOUTUBE("YouTube"), INSTAGRAM("Instagram"), SPOTIFY("Spotify");

    companion object {
        fun fromUrl(url: String): Source? {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            fun matches(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                matches("youtube.com") || host == "youtu.be" -> YOUTUBE
                matches("instagram.com") -> INSTAGRAM
                matches("spotify.com") || host == "spotify.link" || host == "spoti.fi" -> SPOTIFY
                else -> null
            }
        }
    }
}
