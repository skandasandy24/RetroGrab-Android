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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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

private val navy = Color(0xFF1E3A8A)
private val lightBlue = Color(0xFF9DB4FF)
private val gold = Color(0xFFE0A800)
private val teal = Color(0xFF0F8A8A)

private val lightColors = lightColorScheme(
    primary = navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF122658),
    secondary = gold,
    onSecondary = Color(0xFF302100),
    secondaryContainer = Color(0xFFFFE3A0),
    onSecondaryContainer = Color(0xFF382900),
    tertiary = teal,
    onTertiary = Color(0xFF001C1C),
    tertiaryContainer = Color(0xFFACEFEF),
    onTertiaryContainer = Color(0xFF003737),
    surfaceTint = navy
)

private val darkColors = darkColorScheme(
    primary = lightBlue,
    onPrimary = Color(0xFF102659),
    primaryContainer = navy,
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = gold,
    onSecondary = Color(0xFF302100),
    secondaryContainer = Color(0xFF594300),
    onSecondaryContainer = Color(0xFFFFE3A0),
    tertiary = teal,
    onTertiary = Color(0xFF001C1C),
    tertiaryContainer = Color(0xFF005050),
    onTertiaryContainer = Color(0xFFACEFEF),
    surfaceTint = lightBlue
)

@Composable
internal fun RetroGrabTheme(
    activity: MainActivity,
    theme: String,
    wallpaperColors: Boolean,
    content: @Composable () -> Unit
) {
    val dark = when (theme) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = when {
        wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(activity) else dynamicLightColorScheme(activity)
        }
        dark -> darkColors
        else -> lightColors
    }

    SideEffect {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun SettingsScreen(
    modifier: Modifier,
    theme: String,
    wallpaperColors: Boolean,
    onThemeChange: (String) -> Unit,
    onWallpaperChange: (Boolean) -> Unit,
    version: String?,
    updateEnabled: Boolean,
    updating: Boolean,
    onUpdate: () -> Unit,
    checkingAppUpdate: Boolean,
    onCheckAppUpdate: () -> Unit,
    appUpdateNotifications: Boolean,
    changingAppUpdateNotifications: Boolean,
    onAppUpdateNotificationsChange: (Boolean) -> Unit
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        OptionDropdown(
            "Theme", theme, listOf("System", "Light", "Dark"),
            Modifier.padding(horizontal = 16.dp),
            onSelected = onThemeChange
        )
        SwitchRow(
            "Use wallpaper colors",
            checked = wallpaperColors,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onCheckedChange = onWallpaperChange
        )
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                version?.let { "Current yt-dlp version: $it" } ?: "Reading yt-dlp version\u2026",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onUpdate, enabled = updateEnabled) {
                Text(if (updating) "Updating yt-dlp\u2026" else "Update yt-dlp now")
            }
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Check for app updates") },
            supportingContent = {
                Text(if (checkingAppUpdate) "Checking for updates… (version ${BuildConfig.VERSION_NAME})"
                    else "Current version ${BuildConfig.VERSION_NAME}")
            },
            modifier = Modifier.clickable(enabled = !checkingAppUpdate, onClick = onCheckAppUpdate)
        )
        SwitchRow(
            "Notify me about app updates",
            checked = appUpdateNotifications,
            enabled = !changingAppUpdateNotifications,
            onCheckedChange = onAppUpdateNotificationsChange
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Where files are saved") },
            supportingContent = {
                Text("Movies / Music / Pictures \u203a RetroGrab")
            }
        )
        ListItem(
            headlineContent = { Text("About") },
            supportingContent = { Text("RetroGrab version ${BuildConfig.VERSION_NAME}") }
        )
        Text(
            "Only download what you have the right to.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
