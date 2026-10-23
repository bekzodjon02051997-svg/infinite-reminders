package com.infinitereminders.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infinitereminders.app.data.Draft
import com.infinitereminders.app.data.PRIORITY_LABELS
import com.infinitereminders.app.data.Repeat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel, id: Long?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    var title by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(0) }
    var repeat by rememberSaveable { mutableStateOf(Repeat.NONE) }
    var color by rememberSaveable { mutableStateOf(0) }
    var alert by rememberSaveable { mutableStateOf(false) }
    var epochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var minuteOfDay by rememberSaveable { mutableStateOf<Int?>(null) }
    var loaded by rememberSaveable { mutableStateOf(id == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(id) {
        if (!loaded && id != null) {
            vm.load(id)?.let { r ->
                title = r.title; desc = r.description; category = r.category; priority = r.priority
                repeat = r.repeat; color = r.colorArgb; alert = r.alertEnabled
                r.dueAt?.let {
                    val z = Instant.ofEpochMilli(it).atZone(zone)
                    epochDay = z.toLocalDate().toEpochDay()
                    minuteOfDay = if (r.dueHasTime) z.hour * 60 + z.minute else null
                }
            }
            loaded = true
        }
        if (id == null) focus.requestFocus()
    }

    fun draft(): Draft {
        val day = epochDay
        val due = day?.let {
            val t = minuteOfDay?.let { m -> LocalTime.of(m / 60, m % 60) } ?: LocalTime.of(9, 0)
            LocalDate.ofEpochDay(it).atTime(t).atZone(zone).toInstant().toEpochMilli()
        }
        return Draft(title, desc, due, minuteOfDay != null, priority, category, repeat, color, alert)
    }

    fun save() {
        if (title.isBlank() || saving) return
        saving = true
        scope.launch {
            val ok = try { vm.save(id, draft()) } catch (e: Exception) { false }
            saving = false
            if (ok) onClose()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (id == null) "New reminder" else "Edit reminder") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title (required)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (id == null) save() })
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it }, label = { Text("Description (optional)") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )

            Text("Due", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val d = epochDay?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()
                    DatePickerDialog(ctx, { _, y, m, day -> epochDay = LocalDate.of(y, m + 1, day).toEpochDay() },
                        d.year, d.monthValue - 1, d.dayOfMonth).show()
                }) { Text(epochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "Pick date") }
                OutlinedButton(onClick = {
                    val m = minuteOfDay ?: (9 * 60)
                    TimePickerDialog(ctx, { _, h, min ->
                        minuteOfDay = h * 60 + min
                        if (epochDay == null) epochDay = LocalDate.now().toEpochDay()
                    }, m / 60, m % 60, DateFormat.is24HourFormat(ctx)).show()
                }) { Text(minuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "Pick time") }
                if (epochDay != null) TextButton(onClick = { epochDay = null; minuteOfDay = null; repeat = Repeat.NONE; alert = false }) { Text("Clear") }
            }

            Text("Priority", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRIORITY_LABELS.forEachIndexed { i, l ->
                    FilterChip(selected = priority == i, onClick = { priority = i }, label = { Text(l) })
                }
            }

            OutlinedTextField(
                value = category, onValueChange = { category = it }, label = { Text("Category (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (cats.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.take(8).forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                }
            }

            Text("Repeat (needs a due date)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.all.forEach { r ->
                    FilterChip(
                        selected = repeat == r, enabled = epochDay != null || r == Repeat.NONE,
                        onClick = { repeat = r }, label = { Text(r.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Text("Color", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { c ->
                    Box(
                        Modifier.size(34.dp)
                            .background(if (c == 0) MaterialTheme.colorScheme.surfaceVariant else Color(c), CircleShape)
                            .border(if (color == c) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = c },
                        contentAlignment = Alignment.Center
                    ) { if (c == 0) Text("–") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alert me at the due time", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Extra alert. The persistent notification already appears immediately.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = alert, onCheckedChange = { alert = it }, enabled = epochDay != null)
            }

            Button(onClick = { save() }, enabled = title.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (id == null) "Save & show notification" else "Save changes")
            }
            if (id != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete reminder") }
            }
        }
    }

    if (confirmDelete && id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete reminder?") },
            text = { Text("This permanently removes it and its notification.") },
            confirmButton = { TextButton(onClick = { vm.delete(id); confirmDelete = false; onClose() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
