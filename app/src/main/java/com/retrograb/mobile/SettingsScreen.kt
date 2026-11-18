package com.retrograb.mobile

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun RetroGrabTheme(
    activity: MainActivity,
    theme: String,
    accent: Accent,
    content: @Composable () -> Unit
) {
    val dark = when (theme) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }
    val pop = popColors(dark, wallpaper = null, accent = accent)

    SideEffect {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    ProvidePop(pop) {
        MaterialTheme(colorScheme = popColorScheme(pop), typography = PopTypography, shapes = PopShapes, content = content)
    }
}

@Composable
internal fun SettingsScreen(
    modifier: Modifier,
    theme: String,
    onThemeChange: (String) -> Unit,
    accentName: String,
    accentHue: Float,
    onAccentChange: (String) -> Unit,
    onHuePreview: (Float) -> Unit,
    onHueChosen: (Float) -> Unit,
    version: String?,
    updateEnabled: Boolean,
    updating: Boolean,
    onUpdate: () -> Unit,
    checkingAppUpdate: Boolean,
    onCheckAppUpdate: () -> Unit,
    appUpdateNotifications: Boolean,
    changingAppUpdateNotifications: Boolean,
    onAppUpdateNotificationsChange: (Boolean) -> Unit,
    spotifyLanguageMode: String,
    onSpotifyLanguageModeChange: (String) -> Unit
) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PopCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            SparkleTitle("Look", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp))
            OptionDropdown(
                "Theme", theme, listOf("System", "Light", "Dark"),
                Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                onSelected = onThemeChange
            )
        }
        PopCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            SparkleTitle("Accent", Modifier.padding(bottom = 4.dp))
            Text(
                if (accentName == ACCENT_CUSTOM) "Custom colour" else accentName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            AccentSwatches(accentName, accentHue, onAccentChange)
            Spacer(Modifier.height(16.dp))
            Text("Pick any colour", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            AccentSpectrum(accentHue, onHuePreview, onHueChosen)
        }
        PopCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            SparkleTitle("Download engine", Modifier.padding(bottom = 10.dp))
            Text(
                version?.let { "Current yt-dlp version: $it" } ?: "Reading yt-dlp version\u2026",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            PopButton(onClick = onUpdate, enabled = updateEnabled, primary = false, height = 48.dp) {
                Text(if (updating) "Updating yt-dlp\u2026" else "Update yt-dlp now")
            }
        }
        PopCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            SparkleTitle("App updates", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp))
            ListItem(
                headlineContent = { Text("Check for app updates", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)) },
                supportingContent = {
                    Text(if (checkingAppUpdate) "Checking for updates… (version ${BuildConfig.VERSION_NAME})"
                        else "Current version ${BuildConfig.VERSION_NAME}")
                },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
                colors = clear,
                modifier = Modifier.clickable(enabled = !checkingAppUpdate, onClick = onCheckAppUpdate)
            )
            SwitchRow(
                "Notify me about app updates",
                checked = appUpdateNotifications,
                enabled = !changingAppUpdateNotifications,
                onCheckedChange = onAppUpdateNotificationsChange
            )
        }
        PopCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            SparkleTitle("Spotify", Modifier.padding(bottom = 10.dp))
            OptionDropdown(
                "Songs in several languages", spotifyLanguageMode,
                listOf(SPOTIFY_LANGUAGE_ASK, SPOTIFY_LANGUAGE_ORIGINAL),
                onSelected = onSpotifyLanguageModeChange
            )
        }
        PopCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            ListItem(
                headlineContent = { Text("Where files are saved", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)) },
                supportingContent = {
                    Text("Movies / Music / Pictures \u203a RetroGrab")
                },
                colors = clear
            )
            ListItem(
                headlineContent = { Text("About", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)) },
                supportingContent = { Text("RetroGrab version ${BuildConfig.VERSION_NAME}") },
                colors = clear
            )
        }
        Text(
            "Only download what you have the right to.",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal suspend fun currentYtDlpVersion(app: RetroGrabApp): String = try {
    app.awaitEngine()
    withContext(Dispatchers.IO) {
        val engine = YoutubeDL.getInstance()
        engine.version(app)?.takeIf { it.isNotBlank() } ?: app.engineLock.withLock {
            engine.version(app)?.takeIf { it.isNotBlank() } ?: run {
                val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
                engine.execute(request).out.trim().ifBlank { "Unavailable" }
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    "Unavailable"
}

internal suspend fun updateYtDlpFromSettings(app: RetroGrabApp): String = try {
    app.awaitEngine()
    val result = withContext(Dispatchers.IO) { app.engineLock.withLock { app.updateYtDlp() } }
    when (result) {
        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is up to date."
        YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated."
        null -> "Update check finished."
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error.message ?: "Could not update yt-dlp."
}
