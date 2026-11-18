package com.retrograb.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.withResumed

@Composable
internal fun AppUpdateDialogs(activity: MainActivity) {
    val updates = activity.appUpdates
    val pendingApk = updates.pendingApk
    val starting = updates.starting
    LaunchedEffect(activity, pendingApk, starting) {
        if (pendingApk != null && !starting) {
            activity.lifecycle.withResumed {
                if (updates.shouldStartInstall()) activity.continueUpdateInstall()
            }
        }
    }

    val update = updates.offer
    val download = updates.downloadProgress
    when {
        download != null -> AlertDialog(
            onDismissRequest = updates::cancelDownload,
            title = { Text("Downloading update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { download.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${download.percent}%")
                }
            },
            confirmButton = { TextButton(onClick = updates::cancelDownload) { Text("Cancel") } }
        )
        updates.showInstallExplanation -> AlertDialog(
            onDismissRequest = { updates.finishInstall(deleteApk = true) },
            title = { Text("Allow app installs") },
            text = { Text("Allow RetroGrab to install apps in Android settings to continue installing the update.") },
            confirmButton = { Button(onClick = activity::openInstallPermissionSettings) { Text("Open settings") } },
            dismissButton = {
                TextButton(onClick = { updates.finishInstall(deleteApk = true) }) { Text("Cancel") }
            }
        )
        update != null && pendingApk == null -> key(update.version) {
            val required = update.level == UpdateLevel.REQUIRED
            AlertDialog(
                onDismissRequest = { if (!required) updates.dismissOffer() },
                properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
                title = {
                    Text(
                        when (update.level) {
                            UpdateLevel.NORMAL -> "Update available"
                            UpdateLevel.IMPORTANT -> "Important update"
                            UpdateLevel.REQUIRED -> "Update required"
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RetroGrab ${update.version} (you have ${BuildConfig.VERSION_NAME})")
                        if (required) {
                            Text("This version of RetroGrab no longer works properly. Please update to keep using the app.")
                        }
                        if (update.body.isNotBlank()) {
                            Text(
                                update.body,
                                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                            )
                        }
                        if (update.level == UpdateLevel.NORMAL) {
                            TextButton(onClick = updates::skipOffer) { Text("Skip this version") }
                        }
                    }
                },
                confirmButton = { Button(onClick = { activity.confirmUpdate(update) }) { Text("Update") } },
                dismissButton = if (required) null else {
                    { TextButton(onClick = updates::dismissOffer) { Text("Later") } }
                }
            )
        }
    }
}
