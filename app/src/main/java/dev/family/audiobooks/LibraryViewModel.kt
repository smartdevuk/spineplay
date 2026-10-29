package dev.family.audiobooks

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ResumeChoice(val book: AudioBook, val local: SavedProgress, val play: Boolean = false)
data class LibraryState(val session: Session? = null, val owner: Identity? = null,
    val profiles: List<HomeProfile> = emptyList(), val servers: List<PlexServer> = emptyList(),
    val selectedIdentity: Identity? = null, val books: List<BookSummary> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val authUrl: String? = null,
    val message: String? = null, val resumeChoice: ResumeChoice? = null,
    val setupSession: Session? = null, val libraries: List<PlexLibrary> = emptyList(), val librarySelection: Set<String> = emptySet(),
    val settingsOpen: Boolean = false, val selectingServer: Boolean = false, val browse: BrowseState = BrowseState(),
    val recent: List<ListeningBook> = emptyList(), val wifiOnly: Boolean = true, val chapterEdition: ChapterEdition? = null,
    val reading: Map<String, ReadingState> = emptyMap(), val detailsBook: AudioBook? = null, val preferredSleepMinutes: Int = 30,
    val restorePreview: ProgressArchive? = null, val conflicts: List<PositionConflict> = emptyList(), val replayBook: AudioBook? = null,
    val detailsPositionMs: Long = 0, val pendingSync: Int = 0, val replayPlay: Boolean = false)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as AudioBooksApp).graph
    val playback = graph.playback.asStateFlow()
    val offline = graph.downloads.state
    val backup = graph.backups.state
    private val mutable = MutableStateFlow(LibraryState(owner = graph.vault.owner(), session = graph.vault.session(),
        browse = graph.vault.session()?.let { graph.preferences.browse(it.userKey) } ?: BrowseState(), wifiOnly = graph.preferences.wifiOnly))
    val state = mutable.asStateFlow()
    private var authJob: Job? = null
    private var operationJob: Job? = null
    private var detailsJob: Job? = null
    private val controllerFuture = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    init {
        controllerFuture.addListener({ runCatching { controllerFuture.get() }.onSuccess { controller = it } }, ContextCompat.getMainExecutor(application))
        if (mutable.value.session != null) loadLibrary() else if (mutable.value.owner != null) loadProfiles()
        viewModelScope.launch { graph.store.revision.collect { refreshLocal() } }
        graph.sync.wake()
    }
    private fun work(action: suspend () -> Unit): Job {
        operationJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            mutable.update { it.copy(loading = true, error = null) }
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message?.takeIf { message -> !message.contains("http") } ?: "Couldn't reach Plex. Check your connection and try again.") } }
            finally { if (operationJob == currentCoroutineContext().job) mutable.update { it.copy(loading = false) } }
        }
        operationJob = job
        job.start()
        return job
    }
    fun signIn() {
        authJob?.cancel()
        authJob = work {
            val pin = graph.plex.createPin()
            mutable.update { it.copy(authUrl = pin.link, message = "Complete sign-in in your browser, then return here.") }
            repeat(150) {
                delay(2000)
                val identity = graph.plex.checkPin(pin)
                if (identity != null) {
                    withContext(Dispatchers.IO) { graph.vault.saveOwner(identity) }
                    mutable.update { it.copy(owner = identity, authUrl = null, message = null) }
                    fetchProfiles(identity)
                    return@work
                }
            }
            mutable.update { it.copy(authUrl = null, message = null, error = "This sign-in expired. Please start again.") }
        }
    }
    fun cancelSignIn() {
        authJob?.cancel()
        mutable.update { it.copy(loading = false, authUrl = null, message = null) }
    }
    private suspend fun fetchProfiles(owner: Identity) {
        val profiles = graph.plex.profiles(owner)
        mutable.update { it.copy(profiles = profiles, servers = emptyList(), session = null, settingsOpen = false, selectingServer = false,
            setupSession = null, recent = emptyList(), browse = BrowseState()) }
    }
    fun loadProfiles() = work {
        command(PlaybackCommands.CLOSE)
        withContext(Dispatchers.IO) { graph.vault.saveSession(null) }
        mutable.value.owner?.let { fetchProfiles(it) }
        mutable.update { it.copy(books = emptyList(), resumeChoice = null) }
    }
    fun selectProfile(profile: HomeProfile, pin: String) = work {
        val owner = mutable.value.owner ?: error("Please sign in again.")
        val identity = graph.plex.switchProfile(owner, profile, pin)
        val servers = graph.plex.servers(identity)
        require(servers.isNotEmpty()) { "No Plex servers are shared with this profile." }
        mutable.update { it.copy(selectedIdentity = identity, servers = servers, profiles = emptyList()) }
        if (servers.size == 1) resolveServer(identity, servers.single())
    }
    fun connect(server: PlexServer, address: String = "") = work {
        val identity = mutable.value.selectedIdentity ?: error("Choose a profile first.")
        val selected = if (address.isBlank()) server else {
            val url = (if (address.startsWith("http")) address else "http://$address").trimEnd('/')
            require(PlexClient.allowedServerUrl(url)) { "Use HTTPS for a remote server, or an HTTP address on your home network." }
            server.copy(connections = (listOf(url) + server.connections).distinct())
        }
        resolveServer(identity, selected)
    }
    private suspend fun resolveServer(identity: Identity, server: PlexServer, forcePicker: Boolean = false) {
        val found = graph.plex.discoverLibraries(identity, server)
        val remembered = withContext(Dispatchers.IO) { graph.vault.remembered(found.session.userKey) }
        val ids = remembered?.libraryIds.orEmpty().filter { id -> found.libraries.any { it.id == id } }
        if (!forcePicker && ids.isNotEmpty() && ids.size == remembered?.libraryIds?.size) {
            attach(found.session.copy(libraryId = ids.first(), libraryIds = ids, libraryNames = found.libraries.filter { it.id in ids }.map { it.name }))
        } else mutable.update { it.copy(setupSession = found.session, libraries = found.libraries, servers = emptyList(), selectingServer = false,
            librarySelection = ids.toSet().ifEmpty { found.libraries.filter { library -> library.name.equals("AudioBooks", true) }.map { library -> library.id }.toSet() }) }
    }
    fun toggleLibrary(id: String) { mutable.update { it.copy(librarySelection = if (id in it.librarySelection) it.librarySelection - id else it.librarySelection + id) } }
    fun saveLibraries() = work {
        val state = mutable.value
        val selected = state.libraries.filter { it.id in state.librarySelection }
        require(selected.isNotEmpty()) { "Choose at least one audiobook library." }
        val session = state.setupSession ?: return@work
        if (state.session?.serverId != session.serverId || playback.value.book?.summary?.libraryId?.let { id -> id.isNotBlank() && id !in state.librarySelection } == true) command(PlaybackCommands.CLOSE)
        attach(session.copy(libraryId = selected.first().id, libraryIds = selected.map { it.id }, libraryNames = selected.map { it.name }))
    }
    private suspend fun attach(session: Session) {
        withContext(Dispatchers.IO) { graph.vault.saveSession(session) }
        graph.backups.refreshProfile()
        mutable.update { it.copy(session = session, setupSession = null, libraries = emptyList(), servers = emptyList(), settingsOpen = false,
            selectingServer = false, browse = graph.preferences.browse(session.userKey), books = emptyList(), detailsBook = null, replayBook = null, conflicts = emptyList()) }
        fetchLibrary(session)
        graph.sync.wake(); graph.backups.request(true)
    }
    private suspend fun fetchLibrary(session: Session) {
        val cached = withContext(Dispatchers.IO) {
            graph.store.applySeries(session.userKey, (graph.store.cachedLibrary(session.userKey) + graph.store.offlineBooks(session.userKey).map { it.summary }).distinctBy { it.id }
                .filter { it.libraryId.isBlank() || it.libraryId in session.libraryIds })
        }
        val recent = withContext(Dispatchers.IO) { graph.store.recent(session.userKey).filter { it.book.summary.libraryId.isBlank() || it.book.summary.libraryId in session.libraryIds } }
        mutable.update { it.copy(books = orderedBooks(cached), recent = recent) }
        try {
            val fetched = withContext(Dispatchers.IO) { graph.plex.books(session) }
            val books = withContext(Dispatchers.IO) { graph.store.applySeries(session.userKey, fetched) }
            withContext(Dispatchers.IO) { graph.store.cacheLibrary(session.userKey, books) }
            mutable.update { it.copy(books = books) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (cached.isEmpty()) throw error
            mutable.update { it.copy(message = "Showing your saved library. Downloaded books are available offline.") }
        }
        graph.downloads.refresh()
        refreshLocal()
    }
    fun loadLibrary() = work {
        val session = mutable.value.session ?: return@work
        fetchLibrary(session)
    }
    fun showSettings() { mutable.update { it.copy(settingsOpen = true) } }
    fun closeSettings() { mutable.update { it.copy(settingsOpen = false, setupSession = null, selectingServer = false, servers = emptyList()) } }
    fun changeLibraries() = work {
        val session = mutable.value.session ?: return@work
        val resources = graph.plex.servers(session.profile)
        val server = resources.firstOrNull { it.id == session.serverId } ?: error("This server is no longer shared with this profile.")
        resolveServer(session.profile, server.copy(connections = (listOf(session.serverUrl) + server.connections).distinct()), true)
    }
    fun changeServer() = work {
        val session = mutable.value.session ?: return@work
        val servers = graph.plex.servers(session.profile)
        mutable.update { it.copy(selectedIdentity = session.profile, servers = servers, selectingServer = true, settingsOpen = false) }
    }
    fun updateBrowse(value: BrowseState) {
        mutable.update { it.copy(browse = value) }
        mutable.value.session?.let { graph.preferences.saveBrowse(it.userKey, value) }
    }
    fun search(value: String) = updateBrowse(mutable.value.browse.copy(query = value, index = 0, offset = 0))
    fun scroll(index: Int, offset: Int) = updateBrowse(mutable.value.browse.copy(index = index, offset = offset))
    fun filterDownloads(value: Boolean) = updateBrowse(mutable.value.browse.copy(downloadsOnly = value, index = 0, offset = 0))
    fun filterStatus(value: String) = updateBrowse(mutable.value.browse.copy(status = value, index = 0, offset = 0))
    private var beforeAuthor: BrowseState? = null
    fun showAuthor(author: String) {
        if (mutable.value.browse.author == null && mutable.value.browse.series == null) beforeAuthor = mutable.value.browse
        updateBrowse(BrowseState(author = author))
    }
    fun clearAuthor() { updateBrowse(beforeAuthor ?: mutable.value.browse.copy(author = null, query = "", index = 0, offset = 0)); beforeAuthor = null }
    fun showSeries(series: String) {
        if (mutable.value.browse.author == null && mutable.value.browse.series == null) beforeAuthor = mutable.value.browse
        updateBrowse(BrowseState(series = series))
    }
    fun clearGroup() { updateBrowse(beforeAuthor ?: BrowseState()); beforeAuthor = null }
    private suspend fun refreshLocal() {
        val session = mutable.value.session ?: return
        val data = withContext(Dispatchers.IO) { Triple(graph.store.reading(session.userKey), graph.store.recent(session.userKey), graph.store.conflicts(session.userKey)) }
        val pending = withContext(Dispatchers.IO) { graph.store.pendingProgress(session.userKey).size }
        if (mutable.value.session?.userKey == session.userKey) mutable.update { it.copy(reading = data.first,
            recent = data.second.filter { entry -> entry.book.summary.libraryId.isBlank() || entry.book.summary.libraryId in session.libraryIds },
            conflicts = data.third, preferredSleepMinutes = graph.preferences.sleepMinutes(session.userKey), pendingSync = pending) }
    }
    fun refreshRecent() { viewModelScope.launch {
        val session = mutable.value.session ?: return@launch
        val recent = withContext(Dispatchers.IO) { graph.store.recent(session.userKey).filter { it.book.summary.libraryId.isBlank() || it.book.summary.libraryId in session.libraryIds } }
        if (mutable.value.session?.userKey == session.userKey) mutable.update { it.copy(recent = recent) }
    } }
    fun playBook(summary: BookSummary) = openBook(summary, play = true)
    fun openBook(summary: BookSummary, play: Boolean = false) = work {
        val session = mutable.value.session ?: error("Choose a Plex profile first.")
        val offlineBook = offline.value[session.userKey]?.firstOrNull { it.book.summary.id == summary.id && it.ready }?.book
        val fetched = offlineBook ?: mutable.value.detailsBook?.takeIf { it.summary.id == summary.id && it.tracks.isNotEmpty() } ?: graph.plex.book(session, summary)
        val book = withContext(Dispatchers.IO) {
            graph.store.applyChapters(session.userKey, fetched).let { it.copy(summary = graph.store.applySeries(session.userKey, listOf(it.summary)).single()) }
        }
        mutable.update { it.copy(chapterEdition = null, detailsBook = null, books = it.books.map { item -> if (item.id == book.summary.id) book.summary else item }) }
        val local = withContext(Dispatchers.IO) { graph.store.progress(session.userKey, summary.id) }
        val status = withContext(Dispatchers.IO) { graph.store.reading(session.userKey)[summary.id]?.status }
        if (status == ReadingStatus.FINISHED) { mutable.update { it.copy(replayBook = book, replayPlay = play) }; return@work }
        if (withContext(Dispatchers.IO) { graph.store.explicitStatus(session.userKey, summary.id) } == ReadingStatus.NOT_STARTED) { open(book, 0, play); return@work }
        if (local != null && book.remoteUpdatedMs > local.updatedMs && kotlin.math.abs(local.positionMs - book.remotePositionMs) > 30000) {
            mutable.update { it.copy(resumeChoice = ResumeChoice(book, local, play)) }
        } else open(book, local?.positionMs ?: book.remotePositionMs, play)
    }
    fun chooseResume(remote: Boolean) = work {
        val choice = mutable.value.resumeChoice ?: return@work
        val session = mutable.value.session ?: return@work
        withContext(Dispatchers.IO) {
            graph.store.place(session.userKey, choice.book.summary.id, choice.local.positionMs, "This phone before resume choice")
            graph.store.place(session.userKey, choice.book.summary.id, choice.book.remotePositionMs, "Plex before resume choice")
            graph.store.acceptRemote(session.userKey, choice.book.summary.id, RemoteProgress(choice.book.remotePositionMs, choice.book.remoteUpdatedMs))
        }
        open(choice.book, if (remote) choice.book.remotePositionMs else choice.local.positionMs, choice.play)
        mutable.update { it.copy(resumeChoice = null) }
    }
    private fun open(book: AudioBook, position: Long, play: Boolean = false) = command(PlaybackCommands.OPEN, Bundle().apply {
        putString("book", book.json()); putLong("position", position); putBoolean("play", play)
    })
    fun replay(startOver: Boolean) = work {
        val book = mutable.value.replayBook ?: return@work
        val session = mutable.value.session ?: return@work
        val local = withContext(Dispatchers.IO) { graph.store.progress(session.userKey, book.summary.id) }
        if (startOver) withContext(Dispatchers.IO) {
            graph.store.place(session.userKey, book.summary.id, local?.positionMs ?: book.durationMs, "Before listening again")
            graph.store.setReadingStatus(session.userKey, book.summary.id, ReadingStatus.NOT_STARTED)
        }
        open(book, if (startOver) 0 else local?.positionMs ?: book.durationMs, startOver && mutable.value.replayPlay)
        mutable.update { it.copy(replayBook = null, replayPlay = false) }
    }
    fun dismissReplay() { mutable.update { it.copy(replayBook = null, replayPlay = false) } }
    fun showDetails(summary: BookSummary) = work {
        val session = mutable.value.session ?: return@work
        val snapshot = playback.value.book?.takeIf { it.summary.id == summary.id }
            ?: offline.value[session.userKey]?.firstOrNull { it.book.summary.id == summary.id }?.book
            ?: withContext(Dispatchers.IO) { graph.store.storedBook(session.userKey, summary.id) }
        val cached = snapshot?.let { withContext(Dispatchers.IO) { graph.store.applyChapters(session.userKey, it).copy(summary = graph.store.applySeries(session.userKey, listOf(it.summary)).single()) } }
        val local = withContext(Dispatchers.IO) { graph.store.progress(session.userKey, summary.id)?.positionMs ?: 0 }
        mutable.update { it.copy(detailsBook = cached ?: AudioBook(summary, emptyList(), emptyList()), detailsPositionMs = local) }
        if (cached == null) {
            val fetched = graph.plex.book(session, summary)
            val book = withContext(Dispatchers.IO) { graph.store.applyChapters(session.userKey, fetched).let { it.copy(summary = graph.store.applySeries(session.userKey, listOf(it.summary)).single()) } }
            mutable.update { it.copy(detailsBook = book, books = it.books.map { item -> if (item.id == summary.id) book.summary else item }) }
        }
    }.also { detailsJob = it }
    fun refreshDetails(summary: BookSummary) = work {
        val session = mutable.value.session ?: return@work
        val fetched = graph.plex.book(session, summary)
        val book = withContext(Dispatchers.IO) { graph.store.applyChapters(session.userKey, fetched).copy(summary = graph.store.applySeries(session.userKey, listOf(fetched.summary)).single()) }
        mutable.update { it.copy(detailsBook = book, books = it.books.map { value -> if (value.id == summary.id) book.summary else value }) }
        if (playback.value.book?.let { it.summary.id == summary.id && sameAudio(it.tracks, book.tracks) } == true)
            command(PlaybackCommands.CHAPTERS, Bundle().apply { putString("book", book.json()) })
        withContext(Dispatchers.IO) { graph.store.cacheLibrary(session.userKey, mutable.value.books) }
    }.also { detailsJob = it }
    fun closeDetails() { detailsJob?.cancel(); mutable.update { it.copy(detailsBook = null) } }
    fun setStatus(summary: BookSummary, status: ReadingStatus) = work {
        val session = mutable.value.session ?: return@work
        if (playback.value.book?.summary?.id == summary.id) command(PlaybackCommands.STATUS, Bundle().apply { putString("status", status.name) })
        else withContext(Dispatchers.IO) {
            graph.store.progress(session.userKey, summary.id)?.let { graph.store.place(session.userKey, summary.id, it.positionMs, "Before marking ${status.label.lowercase()}") }
            graph.store.setReadingStatus(session.userKey, summary.id, status)
            graph.backups.request(true)
        }
    }
    fun listenLater(summary: BookSummary, value: Boolean) = work {
        val session = mutable.value.session ?: return@work
        withContext(Dispatchers.IO) { graph.store.setLater(session.userKey, summary.id, value); graph.backups.request(true) }
    }
    fun saveSeries(book: AudioBook, name: String, number: String) = work {
        val session = mutable.value.session ?: return@work
        val index = number.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        require(number.isBlank() || index != null && index.isFinite() && index in 0.0..100000.0) { "Use a valid book number, such as 2 or 2.5." }
        require(name.trim().length in 1..256) { "Enter a series name." }
        val summary = book.summary.copy(series = listOf(SeriesRef(name.trim(), index)) + book.summary.series.filter { it.collection && !it.name.equals(name.trim(), true) })
        withContext(Dispatchers.IO) { graph.store.saveSeries(session.userKey, summary); graph.backups.request(true) }
        val updated = book.copy(summary = summary)
        if (playback.value.book?.summary?.id == summary.id) command(PlaybackCommands.CHAPTERS, Bundle().apply { putString("book", updated.json()) })
        mutable.update { it.copy(detailsBook = updated, books = it.books.map { item -> if (item.id == summary.id) summary else item }, message = "Series details saved on this phone") }
    }
    fun configureBackup(uri: android.net.Uri?) { graph.backups.configure(uri?.toString()) }
    fun backupNow() = work { withContext(Dispatchers.IO) { graph.backups.backupNow() }; mutable.update { it.copy(message = "Progress backup saved") } }
    fun exportBackup(uri: android.net.Uri) = work {
        val user = mutable.value.session?.userKey ?: return@work
        withContext(Dispatchers.IO) { graph.backups.export(uri, user) }
        mutable.update { it.copy(message = "Progress backup exported") }
    }
    fun previewRestore(uri: android.net.Uri) = work {
        val user = mutable.value.session?.userKey ?: return@work
        val archive = withContext(Dispatchers.IO) { graph.backups.preview(uri, user) }
        if (mutable.value.session?.userKey == user) mutable.update { it.copy(restorePreview = archive) }
    }
    fun dismissRestore() { mutable.update { it.copy(restorePreview = null) } }
    fun restoreBackup() = work {
        val archive = mutable.value.restorePreview ?: return@work
        require(archive.user == mutable.value.session?.userKey) { "Choose the backup's original Plex user first." }
        val result = withContext(Dispatchers.IO) { graph.store.restore(archive) }
        mutable.update { it.copy(restorePreview = null, message = "Restored ${result.restored} books. ${result.alternatives} different places were kept in History.") }
        refreshLocal(); graph.backups.request(true)
    }
    fun showError(message: String) { mutable.update { it.copy(error = message) } }
    fun resolveConflict(book: AudioBook, useRemote: Boolean) = work {
        val session = mutable.value.session ?: return@work
        playback.value.book?.takeIf { it.summary.id == book.summary.id }?.let {
            require(sameAudio(it.tracks, book.tracks)) { "This book's files have changed. Reopen the book before choosing its synced position." }
        }
        val position = playback.value.takeIf { it.book?.summary?.id == book.summary.id }?.positionMs
            ?: withContext(Dispatchers.IO) { graph.store.progress(session.userKey, book.summary.id)?.positionMs } ?: 0
        graph.sync.resolve(book, position, useRemote) { remote ->
            if (useRemote) {
                if (playback.value.book?.summary?.id == book.summary.id)
                    awaitCommand(PlaybackCommands.SEEK, Bundle().apply { putLong("position", remote.positionMs) })
                else withContext(Dispatchers.IO) { graph.store.save(session.userKey, book, remote.positionMs, graph.store.progress(session.userKey, book.summary.id)?.speed ?: 1f) }
                remote.positionMs
            } else playback.value.takeIf { it.book?.summary?.id == book.summary.id }?.positionMs
                ?: withContext(Dispatchers.IO) { graph.store.progress(session.userKey, book.summary.id)?.positionMs } ?: position
        }
        refreshLocal()
    }
    fun downloadCurrent() = work {
        val session = mutable.value.session ?: return@work
        val book = playback.value.book ?: return@work
        withContext(Dispatchers.IO) { graph.downloads.add(session, book) }
        mutable.update { it.copy(message = "Download queued. You can follow its progress in Downloads.") }
    }
    fun retryDownload(book: AudioBook) = work {
        val session = mutable.value.session ?: return@work
        withContext(Dispatchers.IO) { graph.downloads.add(session, book) }
    }
    fun removeDownload(book: AudioBook) = work {
        val session = mutable.value.session ?: return@work
        if (playback.value.book?.summary?.id == book.summary.id) command(PlaybackCommands.CLOSE)
        withContext(Dispatchers.IO) { graph.downloads.remove(session, book) }
    }
    fun wifiOnly(value: Boolean) { graph.downloads.wifiOnly(value); mutable.update { it.copy(wifiOnly = value) } }
    fun pauseDownloads() = graph.downloads.pauseAll()
    fun resumeDownloads() = graph.downloads.resumeAll()
    fun findChapters(value: String, region: String) = work {
        val book = playback.value.book ?: return@work
        val edition = graph.chapterLookup.find(value, region)
        editionChapters(book, edition) // Validate before showing an apply action.
        mutable.update { it.copy(chapterEdition = edition) }
    }
    fun clearChapterEdition() { mutable.update { it.copy(chapterEdition = null) } }
    fun applyEdition(adjustmentSeconds: Long) = work {
        val edition = mutable.value.chapterEdition ?: return@work
        val book = playback.value.book ?: return@work
        val session = mutable.value.session ?: return@work
        val updated = editionChapters(book, edition, Math.multiplyExact(adjustmentSeconds, 1000))
        withContext(Dispatchers.IO) { graph.store.saveChapters(session.userKey, updated) }
        command(PlaybackCommands.CHAPTERS, Bundle().apply { putString("book", updated.json()) })
        mutable.update { it.copy(chapterEdition = null, message = "Chapters updated. Your listening position has not moved.") }
    }
    fun seek(position: Long) = command(PlaybackCommands.SEEK, Bundle().apply { putLong("position", position) })
    fun undo() = command(PlaybackCommands.UNDO)
    fun speed(value: Float) = command(PlaybackCommands.SPEED, Bundle().apply { putFloat("speed", value) })
    fun timer(minutes: Int) { command(PlaybackCommands.TIMER, Bundle().apply { putInt("minutes", minutes) }); if (minutes != 0) mutable.update { it.copy(preferredSleepMinutes = minutes) } }
    fun extendTimer() = command(PlaybackCommands.TIMER, Bundle().apply { putInt("minutes", 10); putBoolean("extend", true) })
    fun returnToBedtime() = command(PlaybackCommands.RETURN_SLEEP)
    fun bookmark() { command(PlaybackCommands.BOOKMARK); mutable.update { it.copy(message = "Bookmark saved") } }
    fun dismissMessage() { mutable.update { it.copy(message = null, error = null) } }
    fun playPause() { controller?.let {
        if (it.playbackState == androidx.media3.common.Player.STATE_ENDED) { seek(0); it.play() }
        else if (it.playWhenReady) it.pause()
        else { if (it.playerError != null) it.prepare(); it.play() }
    } }
    fun places(book: AudioBook, bookmarks: Boolean) = mutable.value.session?.let { graph.store.places(it.userKey, book.summary.id, bookmarks) }.orEmpty()
    private fun command(action: String, arguments: Bundle = Bundle.EMPTY) {
        val current = controller
        if (current == null) { mutable.update { it.copy(error = "The audio player is starting. Please try again in a moment.") }; return }
        val result = current.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), arguments)
        result.addListener({
            // Return only a generic message: connection exceptions can contain sensitive URLs.
            runCatching { check(result.get().resultCode == SessionResult.RESULT_SUCCESS) }
                .onFailure { mutable.update { it.copy(error = "The audio player couldn't complete that action. Your previous place is saved; please try again.") } }
        }, ContextCompat.getMainExecutor(getApplication()))
    }
    private suspend fun awaitCommand(action: String, arguments: Bundle) = suspendCancellableCoroutine<Unit> { continuation ->
        val current = controller
        if (current == null) { continuation.resumeWithException(IllegalStateException("The audio player is starting. Please try again.")); return@suspendCancellableCoroutine }
        val future = current.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), arguments)
        future.addListener({
            if (continuation.isActive) runCatching { check(future.get().resultCode == SessionResult.RESULT_SUCCESS) }
                .onSuccess { continuation.resume(Unit) }.onFailure { continuation.resumeWithException(IllegalStateException("The player couldn't use that place. Both positions remain in History.")) }
        }, ContextCompat.getMainExecutor(getApplication()))
    }
    fun signOut() {
        authJob?.cancel(); command(PlaybackCommands.CLOSE)
        graph.downloads.pauseAll()
        work {
            withContext(Dispatchers.IO) { graph.vault.clear() }
            graph.backups.refreshProfile()
            mutable.value = LibraryState()
        }
    }
    override fun onCleared() { MediaController.releaseFuture(controllerFuture); super.onCleared() }
}
