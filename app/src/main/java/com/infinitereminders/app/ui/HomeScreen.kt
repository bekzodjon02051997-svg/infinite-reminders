package com.infinitereminders.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.infinitereminders.app.data.Fmt
import com.infinitereminders.app.data.PRIORITY_LABELS
import com.infinitereminders.app.data.Reminder
import com.infinitereminders.app.data.Repeat
import com.infinitereminders.app.data.State

private val SORTS = listOf("Newest", "Due date", "Priority", "Recently completed")
private val STATE_CHIPS = listOf(
    "Open" to setOf(State.ACTIVE, State.PENDING),
    "Active" to setOf(State.ACTIVE),
    "Awaiting" to setOf(State.PENDING),
    "Completed" to setOf(State.COMPLETED),
    "All" to setOf(State.ACTIVE, State.PENDING, State.COMPLETED)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onAdd: () -> Unit, onEdit: (Long) -> Unit, onSettings: () -> Unit) {
    val items = vm.items.collectAsLazyPagingItems()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val sync by vm.syncResult.collectAsStateWithLifecycle()
    val f by vm.filter.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    var deleteId by remember { mutableStateOf<Long?>(null) }
    var showBulk by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var catMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Infinite Reminders") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Add many at once") }, onClick = { menu = false; showBulk = true })
                            DropdownMenuItem(text = { Text("Restore all notifications") }, onClick = { menu = false; vm.retry() })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add reminder") }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Open", stats.open, Modifier.weight(1f))
                    StatCard("Active", stats.active, Modifier.weight(1f))
                    StatCard("Awaiting", stats.pending, Modifier.weight(1f))
                    StatCard("Done", stats.completed, Modifier.weight(1f), "${stats.completedWeek} /7d")
                }
            }
            if (sync.at > 0) {
                if (!sync.permissionGranted) item {
                    Banner("Notifications are OFF. Your reminders are saved but can't appear in the notification panel.", "Fix", onSettings, true)
                }
                if (sync.failed > 0) item {
                    Banner("${sync.failed} notification(s) failed to register: ${sync.error ?: "unknown error"}. Reminders are safe.", "Retry", { vm.retry() }, true)
                }
                if (sync.permissionGranted && sync.overflow > 0) item {
                    Banner("${sync.overflow} reminders are saved but not shown individually (Android caps an app at 50 notifications). They take a slot as you finish tasks.", null, {}, false)
                }
            }
            item {
                OutlinedTextField(
                    value = f.query,
                    onValueChange = { q -> vm.setFilter { it.copy(query = q) } },
                    label = { Text("Search all reminders") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(STATE_CHIPS) { (label, set) ->
                        FilterChip(
                            selected = f.states == set,
                            onClick = {
                                vm.setFilter {
                                    it.copy(states = set, sort = if (set == setOf(State.COMPLETED)) 3 else if (it.sort == 3) 0 else it.sort)
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { catMenu = true }) {
                            Text(if (f.category.isEmpty()) "All categories" else f.category, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                            DropdownMenuItem(text = { Text("All categories") }, onClick = { catMenu = false; vm.setFilter { it.copy(category = "") } })
                            cats.forEach { c ->
                                DropdownMenuItem(text = { Text(c) }, onClick = { catMenu = false; vm.setFilter { it.copy(category = c) } })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { sortMenu = true }) { Text("Sort: ${SORTS[f.sort]}") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SORTS.forEachIndexed { i, s ->
                                DropdownMenuItem(text = { Text(s) }, onClick = { sortMenu = false; vm.setFilter { it.copy(sort = i) } })
                            }
                        }
                    }
                }
            }

            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                val r = items[index]
                if (r != null) {
                    ReminderCard(
                        r,
                        onClick = { onEdit(r.id) },
                        onDone = { vm.done(r.id) },
                        onConfirm = { vm.confirm(r.id) },
                        onUndo = { vm.undo(r.id) },
                        onReopen = { vm.reopen(r.id) },
                        onDuplicate = { vm.duplicate(r.id) },
                        onDelete = { deleteId = r.id }
                    )
                }
            }
            if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                item {
                    Text(
                        if (f.query.isNotBlank() || f.category.isNotEmpty()) "No reminders match your search." else "Nothing here. Tap “Add reminder”.",
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete reminder?") },
            text = { Text("This permanently removes it and its notification. To just finish a task, use DONE → CONFIRM DONE instead.") },
            confirmButton = { TextButton(onClick = { vm.delete(id); deleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }
        )
    }
    if (showBulk) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBulk = false },
            title = { Text("Add many reminders") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("One reminder per line") }, minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.bulk(text); showBulk = false }, enabled = text.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showBulk = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatCard(label: String, n: Int, modifier: Modifier, sub: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(n.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit, warning: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ReminderCard(
    r: Reminder, onClick: () -> Unit, onDone: () -> Unit, onConfirm: () -> Unit, onUndo: () -> Unit,
    onReopen: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit
) {
    val barColor = when {
        r.colorArgb != 0 -> Color(r.colorArgb)
        r.state == State.PENDING -> Color(0xFFF59E0B)
        r.state == State.COMPLETED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val overdue = r.dueAt != null && r.state != State.COMPLETED && r.dueAt < System.currentTimeMillis()
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(barColor))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onClick() })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
                if (r.description.isNotBlank()) Text(r.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val meta = buildList {
                    add(when (r.state) { State.PENDING -> "AWAITING CONFIRMATION"; State.COMPLETED -> "COMPLETED"; else -> "ACTIVE" })
                    r.dueAt?.let { add((if (overdue) "Overdue " else "Due ") + Fmt.due(it, r.dueHasTime)) }
                    if (r.priority > 0) add(PRIORITY_LABELS[r.priority])
                    if (r.category.isNotBlank()) add(r.category)
                    if (r.repeat != Repeat.NONE) add(r.repeat.lowercase())
                    r.completedAt?.let { add("done " + Fmt.stamp(it)) }
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelMedium, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (r.state) {
                        State.ACTIVE -> OutlinedButton(onClick = onDone) { Text("DONE") }
                        State.PENDING -> {
                            OutlinedButton(onClick = onConfirm) { Text("CONFIRM DONE") }
                            TextButton(onClick = onUndo) { Text("UNDO") }
                        }
                        else -> TextButton(onClick = onReopen) { Text("REOPEN") }
                    }
                }
            }
        }
    }
}
