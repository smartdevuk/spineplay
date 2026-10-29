package dev.family.audiobooks

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

internal val Ink = Color(0xFF18130F)
internal val Surface = Color(0xFF251D16)
internal val Amber = Color(0xFFFFB735)
internal val Ivory = Color(0xFFF7F2E8)
internal val Muted = Color(0xFFB9B1A7)
internal val TrackColor = Color(0xFF51473E)

class MainActivity : ComponentActivity() {
    private val model get() = ViewModelProvider(this)[LibraryViewModel::class.java]
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val backupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            val previous = model.graph.preferences.backupFolder
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            model.configureBackup(uri)
            if (previous != null && previous != uri.toString()) runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        } catch (_: SecurityException) { model.showError("Spineplay couldn't keep access to that folder. Please choose another backup folder.") }
    }
    private val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::exportBackup) }
    private val backupRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::previewRestore) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        setContent {
            AudioBooksTheme { SpineplayScreen(savedInstanceState == null, android.animation.ValueAnimator.areAnimatorsEnabled()) }
        }
    }
    fun requestPlaybackNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun backupAction(action: String) {
        when (action) {
            "folder" -> backupFolder.launch(null)
            "export" -> backupExport.launch("Spineplay-progress-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date())}.json")
            "restore" -> backupRestore.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            "now" -> model.backupNow()
            "disable" -> {
                model.graph.preferences.backupFolder?.let { runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                model.configureBackup(null)
            }
        }
    }
}

@Composable
private fun SpineplayScreen(coldStart: Boolean, animationsEnabled: Boolean, vm: LibraryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    BrandedLaunch(coldStart, !state.loading || state.books.isNotEmpty() || state.owner == null, animationsEnabled) { AudioBooksScreen(vm) }
}

@Composable
internal fun AudioBooksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Ink, surface = Surface,
        onPrimary = Ink, onBackground = Ivory, onSurface = Ivory, secondary = Amber,
        secondaryContainer = TrackColor, onSecondaryContainer = Ivory, surfaceTint = Amber)) {
        CompositionLocalProvider(LocalContentColor provides Ivory, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioBooksScreen(vm: LibraryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val backup by vm.backup.collectAsStateWithLifecycle()
    val downloads = state.session?.let { offline[it.userKey] }.orEmpty()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.authUrl) {
        state.authUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    }
    LaunchedEffect(playback.book?.summary?.id) { if (playback.book != null) playerOpen = true; sheet = null }
    LaunchedEffect(state.message) {
        if (state.authUrl == null) state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }
    BackHandler(playerOpen || sheet != null || state.detailsBook != null || state.settingsOpen || state.selectingServer || state.setupSession != null || state.browse.author != null || state.browse.series != null) {
        when {
            state.detailsBook != null -> vm.closeDetails()
            sheet != null -> sheet = null
            state.setupSession != null && state.session == null -> vm.loadProfiles()
            state.setupSession != null || state.settingsOpen || state.selectingServer -> vm.closeSettings()
            playerOpen -> { playerOpen = false; vm.refreshRecent() }
            state.browse.author != null || state.browse.series != null -> vm.clearGroup()
        }
    }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                state.setupSession != null -> LibraryPicker(state, vm)
                state.selectingServer || state.session == null -> ConnectionScreen(state, vm)
                state.settingsOpen -> SettingsScreen(state, vm, backup, { (context as? MainActivity)?.backupAction(it) }) { sheet = "downloads" }
                playerOpen && playback.book != null -> PlayerContent(playback, { playerOpen = false; vm.refreshRecent() }, vm::playPause, vm::seek, vm::undo, vm::bookmark,
                    { sheet = it }, { vm.showAuthor(playback.book!!.summary.author); playerOpen = false }, downloads.firstOrNull { it.book.summary.id == playback.book!!.summary.id },
                    { vm.showDetails(playback.book!!.summary) }) { modifier ->
                    Cover(playback.book!!.summary, state.session!!, modifier)
                }
                else -> LibraryContent(state, playback, downloads, LibraryActions(vm::search, vm::scroll, vm::showAuthor, vm::clearGroup,
                    vm::filterDownloads, vm::showSettings, { sheet = "downloads" },
                    { summary -> if (playback.book?.summary?.id == summary.id) playerOpen = true else vm.openBook(summary) },
                    { playerOpen = true }, vm::playPause, vm::filterStatus, { vm.showDetails(it) }, { vm.playBook(it) })) { book, modifier -> Cover(book, state.session!!, modifier) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Amber, trackColor = TrackColor)
        }
    }
    state.error?.let { message -> AlertDialog(onDismissRequest = vm::dismissMessage,
        title = { Text("Let's reconnect") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } }) }
    state.resumeChoice?.let { choice -> AlertDialog(onDismissRequest = { vm.chooseResume(false) },
        title = { Text("Choose where to resume") },
        text = { Text("Your saved place on this phone is ${clock(choice.local.positionMs)}. Plex has a newer update at ${clock(choice.book.remotePositionMs)}. Both places stay in your listening history.") },
        confirmButton = { TextButton(onClick = { vm.chooseResume(true) }) { Text("Use Plex position") } },
        dismissButton = { TextButton(onClick = { vm.chooseResume(false) }) { Text("Keep this phone's place") } }) }
    state.replayBook?.let { book -> AlertDialog(onDismissRequest = vm::dismissReplay, title = { Text("Listen again?") },
        text = { Text("${book.summary.title} is marked finished. Starting again keeps your previous place in History.") },
        confirmButton = { TextButton(onClick = { vm.replay(true) }, enabled = !state.loading) { Text("Start again") } },
        dismissButton = { TextButton(onClick = vm::dismissReplay) { Text("Cancel") } }) }
    state.restorePreview?.let { archive -> AlertDialog(onDismissRequest = vm::dismissRestore, title = { Text("Restore progress backup?") },
        text = { Text("${archive.progress.size} books and ${archive.places.count { it.bookmark }} bookmarks. Existing places stay in place; different backup positions are added to History. This affects only the current Plex user.") },
        confirmButton = { TextButton(onClick = { vm.restoreBackup() }, enabled = !state.loading) { Text("Restore") } },
        dismissButton = { TextButton(onClick = vm::dismissRestore) { Text("Cancel") } }) }
    var dismissedConflict by rememberSaveable(state.session?.userKey) { mutableStateOf<String?>(null) }
    val conflict = state.conflicts.firstOrNull { it.bookId == playback.book?.summary?.id }
    val conflictKey = conflict?.let { "${it.bookId}/${it.remote.updatedMs}/${it.remote.positionMs}" }
    if (conflict != null && conflictKey != dismissedConflict && state.resumeChoice == null && state.detailsBook == null) {
        AlertDialog(onDismissRequest = { dismissedConflict = conflictKey }, title = { Text("Two saved places") },
            text = { Text("This phone is at ${clock(playback.positionMs)}. Plex has a different place at ${clock(conflict.remote.positionMs)}. Both are saved in History.") },
            confirmButton = { TextButton(onClick = { playback.book?.let { vm.resolveConflict(it, true) } }, enabled = !state.loading) { Text("Use Plex place") } },
            dismissButton = { TextButton(onClick = { playback.book?.let { vm.resolveConflict(it, false) } }, enabled = !state.loading) { Text("Keep this phone") } })
    }
    val book = playback.book
    val detail = state.detailsBook
    if (detail != null && state.session != null) {
        ModalBottomSheet(onDismissRequest = vm::closeDetails, containerColor = Surface) {
            val status = downloads.firstOrNull { it.book.summary.id == detail.summary.id }
            BookDetailContent(detail, state.reading[detail.summary.id] ?: ReadingState(), status,
                if (book?.summary?.id == detail.summary.id) playback.positionMs else state.detailsPositionMs, state.loading,
                state.conflicts.firstOrNull { it.bookId == detail.summary.id }, DetailActions(
                    play = { if (book?.summary?.id == detail.summary.id && state.reading[detail.summary.id]?.status != ReadingStatus.FINISHED) {
                        if (!playback.playing) vm.playPause()
                        playerOpen = true; vm.closeDetails()
                    } else vm.playBook(detail.summary) },
                    author = { vm.showAuthor(detail.summary.author); vm.closeDetails(); playerOpen = false },
                    series = { vm.showSeries(it); vm.closeDetails(); playerOpen = false }, status = { vm.setStatus(detail.summary, it) },
                    later = { vm.listenLater(detail.summary, it) }, download = {
                        if (status != null) { vm.closeDetails(); sheet = "downloads" } else { (context as? MainActivity)?.requestPlaybackNotifications(); vm.retryDownload(detail) }
                    }, saveSeries = { name, number -> vm.saveSeries(detail, name, number) }, refresh = { vm.refreshDetails(detail.summary) },
                    resolve = { vm.resolveConflict(detail, it) })) { modifier -> Cover(detail.summary, state.session!!, modifier) }
        }
    } else if (sheet == "downloads") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Surface) { DownloadsSheet(downloads, vm) {
            if (playback.book?.summary?.id == it.id) playerOpen = true else vm.openBook(it)
            sheet = null
        } }
    } else if (sheet == "lookup" && book != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null; vm.clearChapterEdition() }, containerColor = Surface) { ChapterLookupSheet(book, state, vm) }
    } else if (sheet == "timer" && book != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Surface) {
            TimerContent(book, playback, state.preferredSleepMinutes, { vm.timer(it); sheet = null }, vm::extendTimer, { vm.returnToBedtime(); sheet = null })
        }
    } else if (sheet != null && book != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(when (sheet) { "chapters" -> "Chapters & sections"; "history" -> "Your listening history";
                    "bookmarks" -> "Bookmarks"; "speed" -> "Playback speed"; "timer" -> "Sleep timer"; else -> "Find an exact place" }, fontSize = 24.sp, fontFamily = FontFamily.Serif)
                Spacer(Modifier.height(16.dp))
                when (sheet) {
                    "chapters" -> {
                        Text(book.chapterSource, color = Muted, fontSize = 12.sp)
                        if (book.chapterSource == "15-minute listening sections") Text("These are navigation sections, rather than the book's original chapters.", color = Muted, fontSize = 13.sp)
                        TextButton(onClick = { vm.clearChapterEdition(); sheet = "lookup" }) { Icon(Icons.Outlined.ManageSearch, null); Spacer(Modifier.width(8.dp)); Text("Find published chapters") }
                        ChapterList(book, playback, vm::seek) { sheet = null }
                    }
                    "offline" -> {
                        val status = downloads.firstOrNull { it.book.summary.id == book.summary.id }
                        Text("Save every audio file in this book for listening without a connection. Downloads stay on this phone and belong to this Plex profile.", color = Muted)
                        status?.let { Text(downloadLabel(it), Modifier.padding(vertical = 12.dp), color = Amber) }
                        if (status?.ready != true) Button(onClick = { (context as? MainActivity)?.requestPlaybackNotifications(); vm.downloadCurrent() }, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (status?.failed == true) "Retry download" else "Make available offline") }
                        TextButton(onClick = { sheet = "downloads" }) { Text("Manage downloads") }
                    }
                    "history", "bookmarks" -> PlacesList(book, vm, sheet == "bookmarks") { vm.seek(it); sheet = null }
                    "speed" -> FlowChoices(listOf(.5f, .75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).map { "${it}×" to it }) { vm.speed(it); sheet = null }
                    "timer" -> FlowChoices(listOf((if (book.chapterAt(playback.positionMs)?.isPart == true) "End of this section" else "End of this chapter") to -1, "15 minutes" to 15, "30 minutes" to 30, "45 minutes" to 45, "60 minutes" to 60, "Turn timer off" to 0)) { vm.timer(it); sheet = null }
                    "seek" -> ExactSeek(book, playback.positionMs) { vm.seek(it); sheet = null }
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(state: LibraryState, vm: LibraryViewModel) {
    val context = LocalContext.current
    var selectedProfile by remember { mutableStateOf<HomeProfile?>(null) }
    var pin by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(28.dp))
        SpineplayMark(Modifier.size(54.dp))
        Text("Spineplay", fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 46.sp, color = Ivory)
        Text("Audiobooks for Plex", color = Muted, fontSize = 15.sp)
        when {
            state.owner == null -> {
                Text("A quieter way to listen to your Plex audiobook library, with chapters and a place you can find again.", color = Muted, fontSize = 16.sp)
                Button(onClick = vm::signIn, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign in with Plex") }
                state.authUrl?.let { url ->
                    Text("Finish signing in with Plex in your browser, then return here.", color = Muted)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open Plex sign-in again") }
                    TextButton(onClick = vm::cancelSignIn) { Text("Cancel sign-in") }
                }
                Text("Your password stays with Plex. Each family member keeps their own progress.", color = Muted, fontSize = 13.sp)
            }
            state.profiles.isNotEmpty() -> {
                Text("Who's listening?", fontSize = 22.sp)
                state.profiles.forEach { profile ->
                    OutlinedButton(onClick = { if (profile.protected) selectedProfile = profile else vm.selectProfile(profile, "") }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                        Icon(if (profile.protected) Icons.Outlined.Lock else Icons.Outlined.Person, null)
                        Spacer(Modifier.width(12.dp)); Text(profile.name)
                    }
                }
            }
            state.servers.isNotEmpty() -> {
                Text("Choose your Plex server", fontSize = 22.sp)
                OutlinedTextField(address, { address = it }, label = { Text("Server address (optional)") },
                    supportingText = { Text("Leave blank to use the address supplied by Plex.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                state.servers.forEach { server -> Button(onClick = { vm.connect(server, address) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(server.name) } }
                Text("Choose your audiobook libraries next. This choice will be remembered.", color = Muted)
                if (state.session != null) TextButton(onClick = vm::closeSettings) { Text("Cancel") }
            }
            else -> { Text("Getting your Plex profiles…", color = Muted); TextButton(onClick = { vm.loadProfiles() }, enabled = !state.loading) { Text("Try again") } }
        }
        if (state.owner != null) TextButton(onClick = vm::signOut) { Text("Sign out") }
    }
    selectedProfile?.let { profile -> AlertDialog(onDismissRequest = { selectedProfile = null; pin = "" },
        title = { Text("${profile.name}'s Plex PIN") },
        text = { OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(), singleLine = true, label = { Text("Plex Home PIN") }) },
        confirmButton = { TextButton(onClick = { vm.selectProfile(profile, pin); selectedProfile = null; pin = "" }, enabled = pin.length == 4) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { selectedProfile = null; pin = "" }) { Text("Cancel") } }) }
}

@Composable
internal fun Cover(book: BookSummary, session: Session, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier.background(Surface), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(38.dp), tint = Amber.copy(alpha = .5f))
        val graph = (context.applicationContext as? AudioBooksApp)?.graph
        val offlineCover = remember(session.userKey, book.id) { graph?.downloads?.coverFile(session.userKey, book.id)?.takeIf { it.exists() } }
        if (!book.needsMetadata) AsyncImage(ImageRequest.Builder(context).data(offlineCover ?: (session.serverUrl + book.thumb))
            .addHeader("X-Plex-Token", session.serverToken).memoryCacheKey("${session.userKey}:${book.thumb}")
            .diskCacheKey("${session.userKey}:${book.thumb}").crossfade(true).build(), null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlayerContent(state: PlaybackState, close: () -> Unit, playPause: () -> Unit, seek: (Long) -> Unit,
    undo: () -> Unit, bookmark: () -> Unit, openSheet: (String) -> Unit, onAuthor: (() -> Unit)? = null,
    offline: OfflineBookStatus? = null, onDetails: (() -> Unit)? = null, cover: @Composable (Modifier) -> Unit) {
    val book = state.book ?: return
    val chapter = book.chapterAt(state.positionMs) ?: return
    val chapterPosition = (state.positionMs - chapter.startMs).coerceIn(0, chapter.durationMs)
    val fraction = chapterPosition.toFloat() / chapter.durationMs
    val wholeFraction = (state.positionMs.toFloat() / book.durationMs).coerceIn(0f, 1f)
    var preview by remember(chapter.startMs) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverSize = (maxHeight * .22f).coerceIn(128.dp, 190.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Outlined.KeyboardArrowDown, "Back to library") }
                Text("Now playing", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 17.sp)
                IconButton(onClick = { openSheet("offline") }) { Icon(if (offline?.ready == true) Icons.Outlined.OfflinePin else Icons.Outlined.Download, "Offline listening") }
                IconButton(onClick = { openSheet("bookmarks") }) { Icon(Icons.Outlined.Bookmarks, "View bookmarks") }
            }
            Spacer(Modifier.height(10.dp))
            cover(Modifier.size(coverSize).clip(RoundedCornerShape(5.dp)).then(if (onDetails != null) Modifier.clickable(onClick = onDetails).semantics { contentDescription = "Book details" } else Modifier))
            Spacer(Modifier.height(16.dp))
            Text(book.summary.title, Modifier.fillMaxWidth(), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 25.sp, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.summary.author, Modifier.fillMaxWidth().then(if (onAuthor != null && book.summary.hasAuthor) Modifier.clickable(onClick = onAuthor) else Modifier).padding(vertical = 5.dp), color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(chapter.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = { openSheet("chapters") }) { Icon(Icons.AutoMirrored.Outlined.List, "Open chapters") }
            }
            val seekColors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = TrackColor)
            Slider(preview ?: fraction, { preview = it }, onValueChangeFinished = {
                seek(chapter.startMs + ((preview ?: fraction) * chapter.durationMs).toLong()); preview = null
            }, modifier = Modifier.fillMaxWidth().height(34.dp).semantics { contentDescription = "Position within ${chapter.title}" },
                thumb = { Surface(Modifier.size(16.dp), color = Amber, shape = CircleShape) {} },
                track = { slider -> SliderDefaults.Track(slider, modifier = Modifier.height(4.dp), colors = seekColors, thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                colors = seekColors)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(preview?.let { (it * chapter.durationMs).toLong() } ?: chapterPosition), fontSize = 13.sp)
                Text("${preciseTime(((chapter.endMs - state.positionMs) / state.speed).toLong() + 999)} left in ${if (chapter.isPart) "section" else "chapter"}", Modifier.weight(1f),
                    color = Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(clock(chapter.durationMs + 999), fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Whole book", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${(wholeFraction * 100).roundToInt()}% complete", fontSize = 12.sp, color = Muted)
            }
            LinearProgressIndicator(progress = { wholeFraction }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(3.dp).clip(CircleShape), color = Amber, trackColor = TrackColor, drawStopIndicator = {})
            Text("${preciseTime(state.positionMs)} listened · ${friendlyTime(((book.durationMs - state.positionMs) / state.speed).toLong())} left",
                Modifier.padding(top = 7.dp), color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipButton(Icons.Outlined.Replay, "Back 15 seconds", "15") { seek(state.positionMs - 15000) }
                FilledIconButton(onClick = playPause, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber, contentColor = Ink)) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = Ink, strokeWidth = 3.dp)
                    else Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(38.dp))
                }
                SkipButton(Icons.Outlined.Forward30, "Forward 30 seconds", null) { seek(state.positionMs + 30000) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                UtilityButton(null, "${state.speed}×", "Speed") { openSheet("speed") }
                val timer = if (state.sleepAtChapterEnd) (if (chapter.isPart) "Section end" else "Chapter end") else state.sleepDeadline?.let { "${((it - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 60000) + 1}m timer" } ?: "Sleep timer"
                UtilityButton(Icons.Outlined.Bedtime, null, timer) { openSheet("timer") }
                UtilityButton(Icons.Outlined.BookmarkBorder, null, "Bookmark", bookmark)
            }
            state.error?.let { Text(it, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            if (state.undoMs != null) {
                Surface(Modifier.fillMaxWidth().padding(top = 12.dp), color = Surface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Amber, modifier = Modifier.size(20.dp))
                        Text("Previous place saved", Modifier.weight(1f).padding(start = 10.dp), fontSize = 12.sp)
                        TextButton(onClick = undo) { Text("Undo jump", fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 14.dp), color = TrackColor)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                UtilityButton(Icons.AutoMirrored.Outlined.List, null, "${if (book.chapters.all { it.isPart }) "Sections" else "Chapters"} · ${book.chapters.size}") { openSheet("chapters") }
                UtilityButton(Icons.Outlined.MyLocation, null, "Fine seek") { openSheet("seek") }
                UtilityButton(Icons.Outlined.History, null, "History") { openSheet("history") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UtilityButton(icon: ImageVector?, mainText: String?, label: String, action: () -> Unit) {
    Column(Modifier.widthIn(min = 76.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = action).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(24.dp)) else Text(mainText.orEmpty(), fontSize = 22.sp)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}
@Composable
private fun SkipButton(icon: ImageVector, description: String, label: String?, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(58.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(38.dp))
            if (label != null) Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
@Composable
internal fun ChapterList(book: AudioBook, state: PlaybackState, seek: (Long) -> Unit, close: () -> Unit) {
    val current = book.chapterAt(state.positionMs)
    val index = book.chapters.indexOf(current).coerceAtLeast(0)
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = index)
    LazyColumn(state = scroll, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
        items(book.chapters, key = { it.startMs }) { chapter ->
            val active = chapter == current
            val progress = ((state.positionMs - chapter.startMs).toFloat() / chapter.durationMs).coerceIn(0f, 1f)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (active) Amber.copy(alpha = .1f) else Color.Transparent)
                .clickable { seek(chapter.startMs); close() }.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (active) Icons.Outlined.GraphicEq else if (progress >= 1) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        null, Modifier.size(20.dp), tint = if (active || progress >= 1) Amber else Muted)
                    Text(chapter.title, Modifier.weight(1f).padding(horizontal = 12.dp), color = if (active) Amber else Ivory, fontSize = 15.sp)
                    Text(clock(chapter.durationMs + 999), color = Muted, fontSize = 12.sp)
                }
                if (progress > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp).height(2.dp), color = Amber, trackColor = TrackColor)
                if (chapter.isPart) Text("Audio section", Modifier.padding(start = 32.dp, top = 4.dp), color = Muted, fontSize = 11.sp)
            }
            HorizontalDivider(color = TrackColor.copy(alpha = .5f))
        }
    }
}
@Composable
private fun PlacesList(book: AudioBook, vm: LibraryViewModel, bookmarks: Boolean, restore: (Long) -> Unit) {
    val places by produceState<List<Place>>(emptyList(), book.summary.id, bookmarks) {
        value = withContext(Dispatchers.IO) { vm.places(book, bookmarks) }
    }
    if (places.isEmpty()) Text(if (bookmarks) "Tap Bookmark while listening to save a place." else "Your listening sessions and jumps will appear here.", color = Muted, modifier = Modifier.padding(bottom = 24.dp))
    LazyColumn(Modifier.heightIn(max = 500.dp)) {
        items(places, key = { it.id }) { place ->
            ListItem(headlineContent = { Text("${clock(place.positionMs)} · ${place.reason}") },
                supportingContent = { Text("${book.chapterAt(place.positionMs)?.title.orEmpty()}\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(place.timestamp))}") },
                leadingContent = { Icon(if (bookmarks) Icons.Outlined.Bookmark else Icons.Outlined.History, null, tint = Amber) },
                modifier = Modifier.clickable { restore(place.positionMs) }, colors = ListItemDefaults.colors(containerColor = Surface))
            HorizontalDivider(color = TrackColor)
        }
    }
}
@Composable
private fun <T> FlowChoices(choices: List<Pair<String, T>>, choose: (T) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 450.dp)) { items(choices) { (label, value) ->
        TextButton(onClick = { choose(value) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, fontSize = 17.sp) }
    } }
}
@Composable
private fun ExactSeek(book: AudioBook, position: Long, choose: (Long) -> Unit) {
    var time by remember { mutableStateOf(clock(position)) }
    val parsed = parsePosition(time)
    Text("Enter a whole-book time, or adjust by five seconds.", color = Muted, fontSize = 14.sp)
    OutlinedTextField(time, { time = it }, label = { Text("Hours:minutes:seconds") }, singleLine = true,
        isError = parsed == null || parsed > book.durationMs, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { time = clock(((parsed ?: position) - 5000).coerceAtLeast(0)) }) { Text("−5 seconds") }
        OutlinedButton(onClick = { time = clock(((parsed ?: position) + 5000).coerceAtMost(book.durationMs)) }) { Text("+5 seconds") }
    }
    Button(onClick = { parsed?.let(choose) }, enabled = parsed != null && parsed <= book.durationMs, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Go to this place") }
}
