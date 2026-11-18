package com.retrograb.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkInfo

internal fun Preferences.options(source: Source): Map<String, Any> {
    fun text(key: String, default: String) = this[stringPreferencesKey(key)] ?: default
    fun flag(key: String, default: Boolean = false) = this[booleanPreferencesKey(key)] ?: default

    return when (source) {
        Source.YOUTUBE -> mapOf(
            "yt.mode" to text("yt.mode", "Video + Audio"),
            "yt.quality" to text("yt.quality", "Best"),
            "yt.format" to text("yt.format", "mp4"),
            "yt.audioFormat" to text("yt.audioFormat", "mp3"),
            "yt.playlist" to flag("yt.playlist"),
            "yt.subtitles" to flag("yt.subtitles"),
            "yt.thumbnail" to flag("yt.thumbnail")
        )
        Source.INSTAGRAM -> mapOf("ig.mode" to text("ig.mode", "Auto"))
        Source.SPOTIFY -> mapOf(
            "sp.format" to text("sp.format", "mp3"),
            "sp.quality" to text("sp.quality", "320 kbps"),
            "sp.subfolder" to flag("sp.subfolder", true),
            "sp.skip" to flag("sp.skip", true),
            SPOTIFY_LANGUAGE_SETTING to text(SPOTIFY_LANGUAGE_SETTING, SPOTIFY_LANGUAGE_ASK)
        )
    }
}

@Composable
internal fun DownloadScreen(
    source: Source,
    prefs: Preferences,
    link: String,
    onLinkChange: (String) -> Unit,
    onPaste: () -> Unit,
    setText: (String, String) -> Unit,
    setFlag: (String, Boolean) -> Unit,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDownload: () -> Unit,
    busy: Boolean,
    showCancel: Boolean,
    cancelling: Boolean,
    onCancel: () -> Unit,
    work: WorkInfo?,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    val download = {
        if (!busy) {
            focus.clearFocus()
            onDownload()
        }
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PopHeadline("Grab from", source.label, Modifier.padding(top = 4.dp, bottom = 2.dp))
        OutlinedTextField(
            value = link,
            onValueChange = onLinkChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("${source.label} link") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = popFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { download() }),
            trailingIcon = {
                Row {
                    if (link.isNotEmpty()) {
                        IconButton(onClick = { onLinkChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear link")
                        }
                    }
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste link")
                    }
                }
            }
        )

        DownloadOptions(source, prefs.options(source), setText, setFlag)

        if (source == Source.INSTAGRAM) {
            PopCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
                ListItem(
                    headlineContent = {
                        Text(if (loggedIn) "Logged in" else "Not logged in",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    },
                    trailingContent = {
                        PopButton(onClick = if (loggedIn) onLogout else onLogin, primary = false,
                            height = 40.dp, modifier = Modifier.width(132.dp)) {
                            Text(if (loggedIn) "Log out" else "Log in")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopButton(
                onClick = download,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                height = 60.dp
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Text("DOWNLOAD", Modifier.padding(start = 8.dp))
            }
            if (showCancel) {
                PopButton(onClick = onCancel, modifier = Modifier.width(128.dp), enabled = !cancelling,
                    primary = false, height = 60.dp) {
                    Text("Cancel")
                }
            }
        }

        DownloadStatus(work)
    }
}

@Composable
private fun DownloadOptions(
    source: Source,
    options: Map<String, Any>,
    setText: (String, String) -> Unit,
    setFlag: (String, Boolean) -> Unit
) {
    val choice: @Composable (String, String, List<String>) -> Unit = { title, key, values ->
        OptionDropdown(title, options.getValue(key) as String, values, Modifier.padding(horizontal = 16.dp)) {
            setText(key, it)
        }
    }
    val toggle: @Composable (String, String) -> Unit = { title, key ->
        SwitchRow(title, options.getValue(key) as Boolean) { setFlag(key, it) }
    }

    PopCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SparkleTitle("Options", Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
            when (source) {
                Source.YOUTUBE -> {
                    choice("Mode", "yt.mode", listOf("Video + Audio", "Audio only", "Video only"))
                    choice("Quality", "yt.quality", listOf("Best", "2160p", "1440p", "1080p", "720p", "480p", "360p"))
                    if (options["yt.mode"] == "Audio only") {
                        choice("Format", "yt.audioFormat", listOf("mp3", "m4a", "opus"))
                    } else {
                        choice("Format", "yt.format", listOf("mp4", "webm", "mkv"))
                    }
                    toggle("Playlist", "yt.playlist")
                    toggle("Subtitles", "yt.subtitles")
                    toggle("Thumbnail", "yt.thumbnail")
                }
                Source.INSTAGRAM -> choice("Mode", "ig.mode", listOf("Auto", "Video", "Photos"))
                Source.SPOTIFY -> {
                    choice("Format", "sp.format", listOf("mp3", "m4a"))
                    choice("Quality", "sp.quality", listOf("320 kbps", "256 kbps", "192 kbps", "128 kbps"))
                    toggle("Put in subfolder", "sp.subfolder")
                    toggle("Skip songs already downloaded", "sp.skip")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionDropdown(
    label: String,
    value: String,
    choices: List<String>,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = popFieldColors(),
            trailingIcon = { PopDropdownArrow(expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp), containerColor = LocalPop.current.card,
            border = BorderStroke(PopBorder, LocalPop.current.line)
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        expanded = false
                        onSelected(choice)
                    }
                )
            }
        }
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = label },
                enabled = enabled,
                colors = popSwitchColors()
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun DownloadStatus(work: WorkInfo?) {
    val data = if (work?.state?.isFinished == true) work.outputData else work?.progress
    val state = when (work?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Queued"
        WorkInfo.State.RUNNING -> data?.getString("status") ?: "Downloading"
        WorkInfo.State.SUCCEEDED -> "Finished"
        WorkInfo.State.FAILED -> "Failed"
        WorkInfo.State.CANCELLED -> "Cancelled"
        null -> "Ready"
    }
    // The worker uses zero while it prepares a download without a measured percentage.
    val percent = data?.getInt("percent", -1)?.takeIf { it in 1..100 }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pop = LocalPop.current
    PopCard(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(spring(stiffness = 500f)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val active = work?.state?.isFinished == false
            val shownPercent by animateIntAsState(percent ?: 0, tween(500), label = "percent")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(active, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    SpinningSparkle()
                }
                Text(
                    if (percent == null) state else "$state \u00b7 $percent%",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                AnimatedVisibility(percent != null && work?.state != WorkInfo.State.SUCCEEDED, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(),
                    exit = scaleOut() + fadeOut()) {
                    PopChip("$shownPercent%", fill = pop.pink)
                }
                AnimatedVisibility(work?.state == WorkInfo.State.SUCCEEDED,
                    enter = scaleIn(spring(dampingRatio = 0.4f, stiffness = 300f)) + fadeIn()) {
                    PopChip("Done \u2713", fill = pop.lavender)
                }
            }
            if (percent != null) {
                PopProgress(percent / 100f)
            } else if (work?.state?.isFinished == false) {
                PopProgress(null)
            }
            TextButton(onClick = { expanded = !expanded }, colors = ButtonDefaults.textButtonColors(contentColor = pop.ink)) {
                Text(if (expanded) "Hide log" else "Show log")
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            if (expanded) {
                val rawLog = data?.getString("log").orEmpty()
                val log = remember(rawLog) { rawLog.lines().takeLast(200).joinToString("\n") }
                val scroll = rememberScrollState()
                LaunchedEffect(log, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                Text(
                    text = log.ifBlank { "No log yet" },
                    modifier = Modifier.fillMaxWidth().height(144.dp).verticalScroll(scroll),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}
