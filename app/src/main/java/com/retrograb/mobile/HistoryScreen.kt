package com.retrograb.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
                PopIconBadge(Icons.Rounded.History, Modifier.size(64.dp))
                Text("No downloads yet", style = MaterialTheme.typography.titleLarge)
            }
        }
        return
    }

    val date = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PopHeadline("Your", "History", Modifier.padding(top = 4.dp, bottom = 6.dp)) }
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
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, date: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val failed = item.status.startsWith("Failed")
    val row = Modifier.fillMaxWidth()
    val pop = LocalPop.current
    PopCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
    ListItem(
        modifier = if (item.uri.isNotBlank() || failed) row.clickable(onClick = onOpen) else row,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            PopIconBadge(item.source.icon(), fill = when (item.source) {
                Source.YOUTUBE -> pop.pink
                Source.INSTAGRAM -> pop.lavender
                Source.SPOTIFY -> pop.card
            })
        },
        headlineContent = {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
        },
        supportingContent = { Text("${item.source.label} \u00b7 $date") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.status == "Saved" -> Icon(
                        Icons.Rounded.CheckCircle, contentDescription = "Saved",
                        tint = pop.blue
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
}
