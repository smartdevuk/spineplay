package dev.family.audiobooks

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimerContent(book: AudioBook, state: PlaybackState, preferred: Int, start: (Int) -> Unit, extend: () -> Unit, recover: () -> Unit) {
    val active = state.sleepDeadline != null || state.sleepAtChapterEnd
    Column(Modifier.fillMaxWidth().heightIn(max = 590.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Sleep timer", fontFamily = FontFamily.Serif, fontSize = 28.sp)
        val section = if (book.chapterAt(state.positionMs)?.isPart == true) "section" else "chapter"
        if (active) {
            Text(if (state.sleepAtChapterEnd) "Pauses at the end of this $section" else "Pauses in ${clock((state.sleepDeadline!! - SystemClock.elapsedRealtime()).coerceAtLeast(0))}", color = Amber)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.sleepDeadline != null) OutlinedButton(onClick = extend) { Text("+10 minutes") }
                TextButton(onClick = { start(0) }) { Text("Turn off") }
            }
        } else Button(onClick = { start(preferred) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Icon(Icons.Outlined.Bedtime, null); Spacer(Modifier.width(10.dp))
            Text(if (preferred == -1) "Stop at $section end" else "Start $preferred-minute timer")
        }
        Text("Choose a different timer", color = Muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 45, 60, 90).forEach { minutes ->
            FilterChip(selected = preferred == minutes, onClick = { start(minutes) }, label = { Text("${minutes}m") })
        } }
        OutlinedButton(onClick = { start(-1) }, modifier = Modifier.fillMaxWidth()) { Text("End of this $section") }
        Text("Your last timer is remembered. Volume gently fades during the final 15 seconds.", color = Muted, fontSize = 13.sp)
        state.sleepSession?.let { saved ->
            HorizontalDivider(color = TrackColor)
            Text("Find your place after sleep", fontFamily = FontFamily.Serif, fontSize = 22.sp)
            Text("Your last timer started at ${clock(saved.positionMs)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saved.createdMs))}.", color = Muted, fontSize = 13.sp)
            OutlinedButton(onClick = recover, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.History, null); Spacer(Modifier.width(8.dp)); Text("Return to ${clock(saved.positionMs)}") }
            Text("Your current place will also be saved in History.", color = Muted, fontSize = 12.sp)
        }
    }
}

data class DetailActions(val play: () -> Unit = {}, val author: () -> Unit = {}, val series: (String) -> Unit = {},
    val status: (ReadingStatus) -> Unit = {}, val later: (Boolean) -> Unit = {}, val download: () -> Unit = {},
    val saveSeries: (String, String) -> Unit = { _, _ -> }, val refresh: () -> Unit = {}, val resolve: (Boolean) -> Unit = {})
@Composable
internal fun BookDetailContent(book: AudioBook, reading: ReadingState, download: OfflineBookStatus?, localPosition: Long,
    loading: Boolean, conflict: PositionConflict?, actions: DetailActions, cover: @Composable (Modifier) -> Unit) {
    var statusMenu by remember { mutableStateOf(false) }
    var editSeries by remember { mutableStateOf(false) }
    var expanded by remember(book.summary.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cover(Modifier.size(86.dp).clip(RoundedCornerShape(7.dp)))
            Column(Modifier.weight(1f)) {
                Text(book.summary.title, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 29.sp)
                Text(book.summary.author, Modifier.clickable(enabled = book.summary.hasAuthor, onClick = actions.author).padding(vertical = 8.dp), color = Amber, fontSize = 15.sp)
            }
        }
        if (book.durationMs > 0) Text("${friendlyTime(book.durationMs)} · ${book.tracks.size} audio ${if (book.tracks.size == 1) "file" else "files"}", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { statusMenu = true }) { Text(reading.status.label); Icon(Icons.Outlined.ArrowDropDown, null) }
                DropdownMenu(statusMenu, { statusMenu = false }) { ReadingStatus.entries.forEach { status ->
                    DropdownMenuItem(text = { Text("Mark ${status.label.lowercase()}") }, onClick = { actions.status(status); statusMenu = false })
                } }
            }
            TextButton(onClick = { actions.later(!reading.later) }) { Icon(if (reading.later) Icons.Outlined.CheckCircle else Icons.Outlined.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text(if (reading.later) "Saved for later" else "Listen later") }
        }
        Button(onClick = actions.play, enabled = !loading && book.tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Icon(Icons.Outlined.PlayCircle, null); Spacer(Modifier.width(8.dp)); Text(when { reading.status == ReadingStatus.FINISHED -> "Listen again"; localPosition > 0 && reading.status != ReadingStatus.NOT_STARTED -> "Resume at ${clock(localPosition)}"; else -> "Listen" })
        }
        conflict?.let {
            Surface(color = Surface, shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Two saved places", color = Amber)
                Text("This phone: ${clock(localPosition)}\nPlex: ${clock(it.remote.positionMs)}\nBoth places are kept in History.", color = Muted, fontSize = 13.sp)
                TextButton(onClick = { actions.resolve(false) }, enabled = !loading) { Text("Keep this phone's place") }
                TextButton(onClick = { actions.resolve(true) }, enabled = !loading) { Text("Use Plex place") }
            } }
        }
        if (book.details.narrators.isNotEmpty()) DetailLine("Narrated by", book.details.narrators.joinToString())
        if (book.details.publisher.isNotBlank()) DetailLine("Publisher", book.details.publisher)
        if (book.details.year.isNotBlank()) DetailLine("Published", book.details.year)
        book.summary.series.forEach { series ->
            TextButton(onClick = { actions.series(series.name) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.LibraryBooks, null); Spacer(Modifier.width(8.dp))
                Text(series.name + (series.number?.let { " · Book ${seriesNumber(it)}" } ?: if (series.collection) " · collection" else ""))
            }
        }
        TextButton(onClick = { editSeries = true }, contentPadding = PaddingValues(0.dp)) { Text(if (book.summary.series.any { !it.collection }) "Edit series details" else "Add series details") }
        HorizontalDivider(color = TrackColor)
        Text("About this book", fontFamily = FontFamily.Serif, fontSize = 22.sp)
        Text(book.details.description.ifBlank { "No description is available in Plex yet." }, color = Muted, fontSize = 15.sp, lineHeight = 23.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 7, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (book.details.description.length > 350) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Show less" else "Read more") }
        val bytes = download?.bytes?.takeIf { download.ready } ?: book.tracks.sumOf { it.sizeBytes }
        if (bytes > 0) DetailLine(if (download?.ready == true) "On this phone" else "Audio size", "${bytes / 1048576} MB")
        if (download != null) Text(downloadLabel(download), color = Amber, fontSize = 13.sp)
        OutlinedButton(onClick = actions.download, enabled = !loading && book.tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Icon(if (download?.ready == true) Icons.Outlined.OfflinePin else Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(if (download != null) "Manage download" else "Make available offline")
        }
        TextButton(onClick = actions.refresh, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Refresh details from Plex") }
    }
    if (editSeries) {
        var name by remember(book.summary.id) { mutableStateOf(book.summary.series.firstOrNull { !it.collection }?.name.orEmpty()) }
        var number by remember(book.summary.id) { mutableStateOf(book.summary.series.firstOrNull { !it.collection }?.number?.let(::seriesNumber).orEmpty()) }
        AlertDialog(onDismissRequest = { editSeries = false }, title = { Text("Series details") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved for your Plex user in Spineplay. Your Plex library is not changed.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(name, { name = it.take(256) }, label = { Text("Series name") }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text("Book number (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }, confirmButton = { TextButton(onClick = { actions.saveSeries(name, number); editSeries = false }, enabled = name.isNotBlank() && (number.isBlank() || number.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100000.0 } == true)) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editSeries = false }) { Text("Cancel") } })
    }
}
internal fun seriesNumber(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
@Composable
private fun DetailLine(label: String, value: String) { Column { Text(label, color = Muted, fontSize = 11.sp); Text(value, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) } }

@Composable
internal fun BackupPanel(state: LibraryState, backup: BackupState, action: (String) -> Unit) {
    Text("Your place", color = Amber)
    Text(when { backup.saving -> "Saving progress backup…"; backup.lastSavedMs > 0 -> "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(backup.lastSavedMs))}"; else -> "No folder backup yet" }, fontSize = 14.sp)
    Text("Choose a folder you can access from another device, or a cloud folder offered by Android. Spineplay keeps three verified backups per user. Backups contain listening places and bookmarks, without audio or sign-in details.", color = Muted, fontSize = 12.sp)
    backup.error?.let { Text(it, color = Amber, fontSize = 12.sp) }
    OutlinedButton(onClick = { action("folder") }, modifier = Modifier.fillMaxWidth()) { Text(if (backup.configured) "Change backup folder" else "Enable automatic backups") }
    if (backup.configured) Row { TextButton(onClick = { action("now") }, enabled = !backup.saving) { Text("Back up now") }; TextButton(onClick = { action("disable") }) { Text("Turn off") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { action("export") }, modifier = Modifier.weight(1f)) { Text("Export backup") }
        OutlinedButton(onClick = { action("restore") }, modifier = Modifier.weight(1f)) { Text("Restore backup") }
    }
    Text(if (state.conflicts.isNotEmpty()) "${state.conflicts.size} different Plex places need a choice. Open the book's details to review them."
        else if (state.pendingSync > 0) "${state.pendingSync} saved ${if (state.pendingSync == 1) "place is" else "places are"} waiting to sync with Plex." else "No pending Plex position updates.", color = Muted, fontSize = 12.sp)
}
