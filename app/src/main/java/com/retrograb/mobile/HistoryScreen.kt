package com.retrograb.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HistoryScreen(activity: MainActivity, work: WorkInfo?, modifier: Modifier) {
    val history = remember(activity) { History(activity) }
    val scope = rememberCoroutineScope()
    var downloads by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(work?.id, work?.state, work?.progress, revision) {
        try {
            downloads = withContext(Dispatchers.IO) { history.read() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            activity.showMessage("Could not read download history.")
        }
    }

    val historyItems = downloads ?: return
    if (historyItems.isEmpty()) {
        Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Rounded.History, contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    val date = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    LazyColumn(modifier) {
        items(historyItems) { item ->
            key(item) {
                HistoryRow(
                    item = item,
                    date = date.format(Date(item.date)),
                    onOpen = {
                        if (item.uri.isNotBlank()) activity.open(item) else activity.showMessage(item.status)
                    },
                    onDelete = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { history.remove(item) }
                                revision++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                activity.showMessage("Could not remove the download from history.")
                            }
                        }
                    }
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, date: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val failed = item.status.startsWith("Failed")
    val row = Modifier.fillMaxWidth()
    ListItem(
        modifier = if (item.uri.isNotBlank() || failed) row.clickable(onClick = onOpen) else row,
        leadingContent = { Icon(item.source.icon(), contentDescription = null) },
        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${item.source.label} \u00b7 $date") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.status == "Saved" -> Icon(
                        Icons.Rounded.CheckCircle, contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    failed -> Icon(
                        Icons.Rounded.Error, contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> Text(item.status, style = MaterialTheme.typography.labelSmall)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "History item options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete from history") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    )
}
