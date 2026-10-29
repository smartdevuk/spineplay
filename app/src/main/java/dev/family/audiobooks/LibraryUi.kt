package dev.family.audiobooks

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

data class LibraryActions(val search: (String) -> Unit = {}, val scroll: (Int, Int) -> Unit = { _, _ -> },
    val author: (String) -> Unit = {}, val clearAuthor: () -> Unit = {}, val downloadsOnly: (Boolean) -> Unit = {},
    val settings: () -> Unit = {}, val downloads: () -> Unit = {}, val open: (BookSummary) -> Unit = {},
    val player: () -> Unit = {}, val playPause: () -> Unit = {}, val statusFilter: (String) -> Unit = {}, val details: (BookSummary) -> Unit = {},
    val play: (BookSummary) -> Unit = {})

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryContent(state: LibraryState, playback: PlaybackState, downloads: List<OfflineBookStatus>, actions: LibraryActions,
    cover: @Composable (BookSummary, Modifier) -> Unit) {
    val browse = state.browse
    val readyIds = downloads.filter { it.ready }.map { it.book.summary.id }.toSet()
    val reading = state.reading.toMutableMap().apply { playback.book?.let { book ->
        if (playback.positionMs > 1000 && this[book.summary.id]?.status != ReadingStatus.FINISHED) this[book.summary.id] = (this[book.summary.id] ?: ReadingState()).copy(status = ReadingStatus.IN_PROGRESS)
    } }
    val matches = remember(state.books, browse.query, browse.author, browse.downloadsOnly, readyIds, browse.status, browse.series, reading) {
        val filtered = state.books.filter { book ->
            (browse.author == null || book.author.equals(browse.author, true)) &&
                (browse.series == null || book.series.any { it.name.equals(browse.series, true) }) &&
                (book.title.contains(browse.query, true) || book.author.contains(browse.query, true)) && (!browse.downloadsOnly || book.id in readyIds) &&
                matchesStatus(browse.status, reading[book.id] ?: ReadingState())
        }
        if (browse.series != null) orderedSeries(filtered, browse.series) else orderedBooks(filtered)
    }
    val recent = buildList {
        playback.book?.takeIf { playback.positionMs > 0 && playback.positionMs < it.durationMs - 10000 }?.let {
            add(ListeningBook(it, SavedProgress(playback.positionMs, Long.MAX_VALUE, playback.speed)))
        }
        addAll(state.recent)
    }.distinctBy { it.book.summary.id }.filter { it.book.summary.id in state.books.map(BookSummary::id) && (reading[it.book.summary.id]?.status ?: ReadingStatus.IN_PROGRESS) == ReadingStatus.IN_PROGRESS }.take(4)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = browse.index, initialFirstVisibleItemScrollOffset = browse.offset)
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) -> actions.scroll(index, offset) }
    }
    var previousFilter by remember { mutableStateOf(listOf(browse.query, browse.author, browse.downloadsOnly.toString(), browse.status, browse.series)) }
    LaunchedEffect(browse.query, browse.author, browse.downloadsOnly, browse.status, browse.series) {
        val filter = listOf(browse.query, browse.author, browse.downloadsOnly.toString(), browse.status, browse.series)
        if (filter != previousFilter) { grid.scrollToItem(browse.index, browse.offset); previousFilter = filter }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(browse.series ?: if (browse.author == null) "Your library" else "By ${browse.author}", fontSize = if (browse.author == null && browse.series == null) 30.sp else 25.sp,
                    lineHeight = if (browse.author == null) 38.sp else 32.sp, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${state.books.size} books · ${state.session?.libraryNames?.joinToString()?.takeIf { it.isNotBlank() } ?: "Your libraries"}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = actions.downloads) { Icon(Icons.Outlined.Download, "Manage downloads") }
            IconButton(onClick = actions.settings) { Icon(Icons.Outlined.Settings, "Settings") }
        }
        Text("Listening as ${state.session?.profile?.name}", Modifier.padding(horizontal = 24.dp, vertical = 5.dp), color = Muted, fontSize = 12.sp)
        OutlinedTextField(browse.query, actions.search, placeholder = { Text(if (browse.author == null) "Find a book or author" else "Search these books") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (browse.query.isNotEmpty()) IconButton(onClick = { actions.search("") }) { Icon(Icons.Outlined.Close, "Clear search") } },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp))
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var menu by remember { mutableStateOf(false) }
            Box {
                FilterChip(selected = browse.status != "all", onClick = { menu = true }, modifier = Modifier.testTag("status-filter"), label = { Text(filterLabel(browse.status), fontSize = 12.sp) }, trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(16.dp)) })
                DropdownMenu(menu, { menu = false }) { (listOf("all" to "All books") + ReadingStatus.entries.map { it.name to it.label } + ("later" to "Listen later")).forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("status-option-$value"), onClick = { actions.statusFilter(value); menu = false })
                } }
            }
            FilterChip(selected = browse.downloadsOnly, onClick = { actions.downloadsOnly(!browse.downloadsOnly) }, label = { Text("On this phone · ${readyIds.size}", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Outlined.OfflinePin, null, Modifier.size(16.dp)) })
            if (browse.author != null || browse.series != null) InputChip(selected = true, onClick = actions.clearAuthor, label = { Text("Back to all books", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(16.dp)) })
        }
        if (state.loading && state.books.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Loading your saved library…", color = Muted)
        } else LazyVerticalGrid(columns = GridCells.Adaptive(130.dp), state = grid, modifier = Modifier.weight(1f).testTag("library-books"), contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (browse.query.isBlank() && browse.author == null && browse.series == null && browse.status == "all" && !browse.downloadsOnly && recent.isNotEmpty()) {
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Continue listening", fontFamily = FontFamily.Serif, fontSize = 23.sp)
                        recent.forEach { entry ->
                            Surface(color = Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable {
                                if (playback.book?.summary?.id == entry.book.summary.id) actions.player() else actions.open(entry.book.summary)
                            }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    cover(entry.book.summary, Modifier.size(58.dp).clip(RoundedCornerShape(5.dp)))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(entry.book.summary.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.book.chapterAt(entry.progress.positionMs)?.title.orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        LinearProgressIndicator(progress = { (entry.progress.positionMs.toFloat() / entry.book.durationMs).coerceIn(0f, 1f) },
                                            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp), color = Amber, trackColor = TrackColor, drawStopIndicator = {})
                                        Text("${friendlyTime(((entry.book.durationMs - entry.progress.positionMs) / entry.progress.speed).toLong())} left", color = Muted, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = { if (playback.book?.summary?.id == entry.book.summary.id) actions.playPause() else actions.play(entry.book.summary) }) {
                                        Icon(if (playback.book?.summary?.id == entry.book.summary.id && playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Resume ${entry.book.summary.title}", tint = Amber)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Text(if (browse.series != null) "Reading order" else if (browse.downloadsOnly) "Ready offline" else if (browse.status != "all") filterLabel(browse.status) else if (browse.query.isNotBlank() || browse.author != null) "${matches.size} books" else "Recently added",
                    fontFamily = FontFamily.Serif, fontSize = 23.sp)
            }
            if (matches.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (state.loading) "Loading your library…" else if (browse.downloadsOnly) "Downloaded books will appear here when every file is ready." else "No books match this view.", color = Muted)
            }
            val recognised = if (browse.series != null) matches else matches.filterNot { it.needsMetadata }
            val incomplete = if (browse.series != null) emptyList() else matches.filter { it.needsMetadata }
            items(recognised, key = { it.id }) { book -> LibraryBookCard(book, book.id in readyIds, reading[book.id] ?: ReadingState(), browse.series, actions, cover) }
            if (incomplete.isNotEmpty()) item(key = "metadata", span = { GridItemSpan(maxLineSpan) }) {
                Column { Text("Other audio", fontSize = 21.sp, fontFamily = FontFamily.Serif); Text("Cover or book details are missing in Plex.", color = Muted, fontSize = 12.sp) }
            }
            items(incomplete, key = { it.id }) { book -> LibraryBookCard(book, book.id in readyIds, reading[book.id] ?: ReadingState(), null, actions, cover) }
        }
        playback.book?.let { book ->
            HorizontalDivider(color = TrackColor)
            Row(Modifier.fillMaxWidth().clickable(onClick = actions.player).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                cover(book.summary, Modifier.size(42.dp).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(book.summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(book.chapterAt(playback.positionMs)?.title.orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = actions.playPause) { Icon(if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playback.playing) "Pause" else "Resume") }
            }
        }
    }
}
@Composable
private fun LibraryBookCard(book: BookSummary, ready: Boolean, reading: ReadingState, series: String?, actions: LibraryActions, cover: @Composable (BookSummary, Modifier) -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { actions.open(book) }, onLongClick = { actions.details(book) })) {
        Box {
            cover(book, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(7.dp)))
            IconButton(onClick = { actions.details(book) }, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(36.dp).background(Ink.copy(alpha = .8f), RoundedCornerShape(24.dp))) { Icon(Icons.Outlined.MoreHoriz, "Details for ${book.title}", tint = Ivory, modifier = Modifier.size(20.dp)) }
            if (ready) Icon(Icons.Outlined.OfflinePin, "Available offline", Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Ink, RoundedCornerShape(20.dp)).padding(4.dp).size(18.dp), tint = Amber)
        }
        if (series != null) Text(book.series.firstOrNull { it.name.equals(series, true) }?.number?.let { "Book ${seriesNumber(it)}" } ?: "Order not available", color = Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Text(book.title, Modifier.padding(top = 9.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, lineHeight = 19.sp)
        if (book.hasAuthor) Text(book.author, Modifier.fillMaxWidth().clickable { actions.author(book.author) }.padding(vertical = 6.dp), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        else Text("Details unavailable", Modifier.padding(top = 4.dp), color = Muted, fontSize = 12.sp)
        if (reading.status == ReadingStatus.FINISHED) Text("Finished", color = Amber, fontSize = 11.sp)
    }
}
private fun filterLabel(value: String) = when (value) { "all" -> "All books"; "later" -> "Listen later"; else -> runCatching { ReadingStatus.valueOf(value).label }.getOrDefault("All books") }

@Composable
internal fun LibraryPicker(state: LibraryState, vm: LibraryViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Where are your books?", fontFamily = FontFamily.Serif, fontSize = 32.sp)
        Text("Choose the audio libraries you want in this app. Your choice is remembered; you can change it in Settings.", color = Muted)
        Text(state.setupSession?.serverName.orEmpty(), color = Amber)
        state.libraries.forEach { library ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.toggleLibrary(library.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(library.id in state.librarySelection, { vm.toggleLibrary(library.id) }); Text(library.name, Modifier.weight(1f))
            }
        }
        Button(onClick = { vm.saveLibraries() }, enabled = state.librarySelection.isNotEmpty() && !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Use these libraries") }
        if (state.session != null) TextButton(onClick = vm::closeSettings) { Text("Cancel") }
    }
}
@Composable
internal fun SettingsScreen(state: LibraryState, vm: LibraryViewModel, backup: BackupState, backupAction: (String) -> Unit, downloads: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = vm::closeSettings) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }; Text("Settings", fontSize = 32.sp, fontFamily = FontFamily.Serif) }
        Text("Plex", color = Amber, modifier = Modifier.padding(top = 12.dp))
        SettingRow("Listening as", state.session?.profile?.name.orEmpty(), "Change user") { vm.loadProfiles() }
        SettingRow("Server", state.session?.serverName.orEmpty(), "Change server") { vm.changeServer() }
        SettingRow("Libraries", state.session?.libraryNames?.joinToString().orEmpty().ifBlank { "Selected audiobook libraries" }, "Choose libraries") { vm.changeLibraries() }
        Text("Your user, server and library choices are remembered. Your Plex Home PIN is used to authorise the session and is not stored.", color = Muted, fontSize = 13.sp)
        HorizontalDivider(color = TrackColor)
        BackupPanel(state, backup, backupAction)
        state.conflicts.forEach { conflict -> state.books.firstOrNull { it.id == conflict.bookId }?.let { book -> TextButton(onClick = { vm.showDetails(book) }) { Text("Review ${book.title}") } } }
        HorizontalDivider(color = TrackColor)
        Text("Offline listening", color = Amber)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Unmetered networks only"); Text("Usually your home Wi-Fi. Turn off to allow mobile data.", color = Muted, fontSize = 12.sp) }
            Switch(state.wifiOnly, vm::wifiOnly)
        }
        OutlinedButton(onClick = downloads, modifier = Modifier.fillMaxWidth()) { Text("Manage downloads") }
        TextButton(onClick = { vm.loadLibrary(); vm.closeSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh library") }
        TextButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Plex") }
        Text("Spineplay 0.3.0 · Audiobooks for Plex", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}
@Composable
private fun SettingRow(label: String, value: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).padding(16.dp)) {
        Text(label, color = Muted, fontSize = 12.sp); Text(value, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text(action) }
    }
}
fun downloadLabel(status: OfflineBookStatus): String = when {
    status.ready -> "Ready offline"
    status.failed -> "Download interrupted · retry available"
    status.paused -> "Paused · ${status.percent}%"
    status.waiting -> "Waiting for an allowed network · ${status.percent}%"
    else -> "Downloading · ${status.percent}% · ${status.bytes / 1048576} MB"
}
@Composable
internal fun DownloadsSheet(downloads: List<OfflineBookStatus>, vm: LibraryViewModel, open: (BookSummary) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text("Downloads", fontFamily = FontFamily.Serif, fontSize = 28.sp)
        Text("Ready books work without Plex or an internet connection. Keep this app installed to retain them.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        Row { TextButton(onClick = vm::pauseDownloads) { Text("Pause downloads") }; TextButton(onClick = vm::resumeDownloads) { Text("Resume") } }
        if (downloads.isEmpty()) Text("Open a book and tap the download icon to save it on this phone.", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(downloads, key = { it.book.summary.id }) { status ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(status.book.summary.title, fontSize = 17.sp); Text(downloadLabel(status), color = if (status.ready) Amber else Muted, fontSize = 12.sp)
                    if (!status.ready) LinearProgressIndicator(progress = { status.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Amber, trackColor = TrackColor)
                    Row { if (status.ready) TextButton(onClick = { open(status.book.summary) }) { Text("Listen") }
                        if (status.failed) TextButton(onClick = { vm.retryDownload(status.book) }) { Text("Retry") }
                        TextButton(onClick = { vm.removeDownload(status.book) }) { Text(if (status.ready) "Remove from phone" else "Cancel download") } }
                }
                HorizontalDivider(color = TrackColor)
            }
        }
    }
}
@Composable
internal fun ChapterLookupSheet(book: AudioBook, state: LibraryState, vm: LibraryViewModel) {
    val context = LocalContext.current
    var id by remember(book.summary.id) { mutableStateOf(book.summary.asin) }
    var region by remember { mutableStateOf("uk") }
    var adjustment by remember { mutableStateOf("0") }
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Find published chapters", fontFamily = FontFamily.Serif, fontSize = 27.sp)
        Text("Timings must match the same audiobook edition. This sends the edition ID and region to Audnexus, without your audio or Plex credentials.", color = Muted, fontSize = 13.sp)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.audible.co.uk/search?keywords=${Uri.encode(book.summary.title + " " + book.summary.author)}"))) }) { Text("Find this edition on Audible") }
        OutlinedTextField(id, { id = it; vm.clearChapterEdition() }, label = { Text("Audible link or ASIN") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("uk", "us", "au", "ca").forEach { code -> FilterChip(selected = region == code, onClick = { region = code; vm.clearChapterEdition() }, label = { Text(code.uppercase()) }) } }
        Button(onClick = { vm.findChapters(id, region) }, enabled = !state.loading && id.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Check chapter list") }
        state.chapterEdition?.let { edition ->
            Text("${edition.chapters.size} chapters · ${edition.title}", color = Amber)
            Text("Your file: ${clock(book.durationMs)}\nPublished edition: ${clock(edition.durationMs)}\nDifference: ${"%.1f".format(abs(book.durationMs - edition.durationMs) / 1000.0)} seconds", color = Muted, fontSize = 13.sp)
            edition.chapters.take(3).forEach { Text("${clock(it.startMs)} · ${it.title}", fontSize = 13.sp) }
            Text("Check a chapter start after applying. A small timing adjustment can account for different opening credits; timings are never stretched to fit.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(adjustment, { adjustment = it }, label = { Text("Timing adjustment in seconds") }, supportingText = { Text("Positive starts later; negative starts earlier.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { adjustment.toLongOrNull()?.let(vm::applyEdition) }, enabled = !state.loading && adjustment.toLongOrNull() in -300L..300L, modifier = Modifier.fillMaxWidth()) { Text("Use these chapters") }
        }
    }
}
