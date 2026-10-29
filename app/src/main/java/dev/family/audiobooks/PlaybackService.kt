package dev.family.audiobooks

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

class AudioBooksApp : Application() {
    lateinit var graph: AppGraph
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
class AppGraph(application: Application) {
    val vault = CredentialVault(application)
    val store = ListeningStore(application)
    val plex = PlexClient(vault)
    val preferences = AppPreferences(application)
    val chapterLookup = ChapterLookup()
    val downloads by lazy { OfflineDownloads(application, vault, store, plex, preferences) }
    val playback = MutableStateFlow(PlaybackState())
    val backups by lazy { ProgressBackup(application, vault, store, preferences) }
    val sync by lazy { ProgressSync(vault, store, plex::remoteProgress, plex::timeline) { user, book ->
        vault.session()?.userKey == user && playback.value.book?.summary?.id == book && playback.value.playing
    } }
}
object PlaybackCommands {
    const val OPEN = "audiobooks.OPEN"
    const val SEEK = "audiobooks.SEEK"
    const val UNDO = "audiobooks.UNDO"
    const val SPEED = "audiobooks.SPEED"
    const val TIMER = "audiobooks.TIMER"
    const val BOOKMARK = "audiobooks.BOOKMARK"
    const val CLOSE = "audiobooks.CLOSE"
    const val CHAPTERS = "audiobooks.CHAPTERS"
    const val RETURN_SLEEP = "audiobooks.RETURN_SLEEP"
    const val STATUS = "audiobooks.STATUS"
    val all = listOf(OPEN, SEEK, UNDO, SPEED, TIMER, BOOKMARK, CLOSE, CHAPTERS, RETURN_SLEEP, STATUS)
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var graph: AppGraph
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storageDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var book: AudioBook? = null
    private var session: Session? = null
    private var undoMs: Long? = null
    private var sleepDeadline: Long? = null
    private var sleepChapterEnd: Long? = null
    private var internalSeek = false
    private var lastSeekAt = 0L
    private var pausedAt = 0L
    private var ticks = 0
    private var failure: String? = null
    private var sleepSession: SleepSession? = null
    private var timerGeneration = 0

    override fun onCreate() {
        super.onCreate()
        graph = (application as AudioBooksApp).graph
        val dataSource = DataSource.Factory {
            DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(mapOf("X-Plex-Token" to (session?.serverToken ?: ""), "X-Plex-Client-Identifier" to graph.vault.clientId))
                .createDataSource()
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(graph.downloads.playbackSource(dataSource)))
            .setSeekBackIncrementMs(15000).setSeekForwardIncrementMs(30000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { publish() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) { pausedAt = SystemClock.elapsedRealtime(); checkpoint("Paused"); sync() }
                    else {
                        val pauseLength = if (pausedAt == 0L) 0 else SystemClock.elapsedRealtime() - pausedAt
                        if (pauseLength >= 30000 && SystemClock.elapsedRealtime() - lastSeekAt > 10000)
                            seek((position() - if (pauseLength >= 300000) 10000 else 3000).coerceAtLeast(0), "Smart rewind", false)
                        checkpoint("Started listening")
                        pausedAt = 0
                    }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK && !internalSeek) {
                        book?.let { savePlace(it.absolute(oldPosition.mediaItemIndex, oldPosition.positionMs), "Before skip") }
                        lastSeekAt = SystemClock.elapsedRealtime()
                    }
                    persist()
                }
                override fun onPlayerError(error: PlaybackException) {
                    failure = "Playback stopped. Your place is saved. Check the connection, then try play again."
                    persist(); publish()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val current = book ?: return
                        val user = session?.userKey ?: return
                        persist()
                        scope.launch(storageDispatcher) { graph.store.setReadingStatus(user, current.summary.id, ReadingStatus.FINISHED); graph.backups.request(true) }
                        sync()
                    }
                }
            })
        }
        val activityIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun seekBack() { seek(position() - 15000, "Before headset skip") }
            override fun seekForward() { seek(position() + 30000, "Before headset skip") }
        }
        mediaSession = MediaSession.Builder(this, sessionPlayer).setSessionActivity(activityIntent).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                // Headsets and lock-screen controls may control this book, but may
                // not inject another URL into a player with Plex credentials.
                accepted.setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build())
                if (controller.uid == Process.myUid()) {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    PlaybackCommands.all.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
                    accepted.setAvailableSessionCommands(commands.build())
                }
                return accepted.build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                return try {
                    when (command.customAction) {
                        PlaybackCommands.OPEN -> open(AudioBook.parse(args.getString("book") ?: error("Missing book")), args.getLong("position"), args.getBoolean("play", false))
                        PlaybackCommands.SEEK -> return seek(args.getLong("position"), "Before jump")
                        PlaybackCommands.UNDO -> return undoMs?.let { seek(it, "Before return") } ?: Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        PlaybackCommands.SPEED -> { player.setPlaybackSpeed(args.getFloat("speed", 1f).coerceIn(.5f, 3f)); persist() }
                        PlaybackCommands.TIMER -> armTimer(args.getInt("minutes"), args.getBoolean("extend"))
                        PlaybackCommands.RETURN_SLEEP -> sleepSession?.let { saved ->
                            armTimer(0, false); return seek(saved.positionMs, "Before returning to bedtime start")
                        }
                        PlaybackCommands.BOOKMARK -> checkpoint("Bookmark", true)
                        PlaybackCommands.STATUS -> {
                            val current = book ?: error("No book")
                            val user = this@PlaybackService.session?.userKey ?: error("No profile")
                            val status = ReadingStatus.valueOf(args.getString("status").orEmpty())
                            checkpoint("Before marking ${status.label.lowercase()}")
                            player.pause()
                            if (status == ReadingStatus.NOT_STARTED) {
                                book = null; this@PlaybackService.session = null; timerGeneration++
                                sleepDeadline = null; sleepChapterEnd = null; sleepSession = null; undoMs = null
                                player.stop(); player.clearMediaItems(); player.volume = 1f
                            }
                            scope.launch(storageDispatcher) { graph.store.setReadingStatus(user, current.summary.id, status); graph.backups.request(true) }
                        }
                        PlaybackCommands.CHAPTERS -> {
                            val updated = AudioBook.parse(args.getString("book") ?: error("Missing chapters"))
                            require(updated.summary.id == book?.summary?.id && book?.tracks?.let { sameAudio(updated.tracks, it) } == true)
                            book = updated; persist()
                        }
                        PlaybackCommands.CLOSE -> {
                            checkpoint("Closed book"); sync()
                            book = null; this@PlaybackService.session = null
                            player.stop(); player.clearMediaItems()
                            undoMs = null; sleepDeadline = null; sleepChapterEnd = null; sleepSession = null; timerGeneration++; player.volume = 1f
                        }
                        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    publish()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                } catch (_: Exception) { Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) }
            }
        }).build()
        graph.sync.wake()
        scope.launch {
            while (true) {
                delay(500)
                player.volume = sleepVolume(sleepDeadline, sleepChapterEnd, position(), player.playbackParameters.speed, SystemClock.elapsedRealtime())
                if (sleepDeadline?.let { SystemClock.elapsedRealtime() >= it } == true || sleepChapterEnd?.let { position() >= it } == true) {
                    player.pause(); sleepDeadline = null; sleepChapterEnd = null; player.volume = 1f
                    sleepSession = sleepSession?.copy(endedMs = System.currentTimeMillis())
                    val current = book; val user = session?.userKey
                    if (current != null && user != null) scope.launch(storageDispatcher) { graph.store.endSleep(user, current.summary.id) }
                    checkpoint("Sleep timer stopped playback")
                }
                publish()
                ticks++
                if (ticks % 10 == 0 && player.playWhenReady && player.playbackState != Player.STATE_ENDED) persist()
                if (ticks % 30 == 0 && player.isPlaying) sync()
            }
        }
    }
    private fun open(value: AudioBook, positionMs: Long, play: Boolean) {
        checkpoint("Changed book"); sync()
        player.pause()
        session = graph.vault.session() ?: error("Sign in first")
        book = value
        failure = null; undoMs = null; sleepDeadline = null; sleepChapterEnd = null
        timerGeneration++; player.volume = 1f
        sleepSession = graph.store.sleep(session!!.userKey, value.summary.id)
        lastSeekAt = SystemClock.elapsedRealtime(); pausedAt = 0
        val items = value.tracks.map { track -> MediaItem.Builder().setMediaId(track.id)
            .setUri(graph.plex.url(session!!, track.partKey))
            .setCustomCacheKey(offlineKey(session!!.userKey, value.summary.id, track))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(value.summary.title).setArtist(value.summary.author).setAlbumTitle(track.title).build()).build() }
        val (index, local) = value.locate(positionMs)
        internalSeek = true
        player.setMediaItems(items, index, local)
        internalSeek = false
        player.setPlaybackSpeed(graph.store.progress(session!!.userKey, value.summary.id)?.speed ?: 1f)
        player.prepare()
        player.playWhenReady = play
        checkpoint("Book opened")
        publish()
    }
    private fun position(): Long = book?.absolute(player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0)) ?: 0
    private fun savePlace(position: Long, reason: String) {
        val currentBook = book ?: return
        val currentSession = session ?: return
        undoMs = position
        scope.launch(storageDispatcher) { graph.store.place(currentSession.userKey, currentBook.summary.id, position, reason) }
    }
    private fun seek(value: Long, reason: String, recoverable: Boolean = true): ListenableFuture<SessionResult> {
        val currentBook = book ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val currentSession = session ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val result = SettableFuture.create<SessionResult>()
        val previousPosition = position()
        lastSeekAt = SystemClock.elapsedRealtime()
        scope.launch {
            try {
            // Commit the recovery point before moving the player. If the process
            // stops during this write, the audio is still at the original place.
            if (recoverable) withContext(storageDispatcher) {
                graph.store.place(currentSession.userKey, currentBook.summary.id, previousPosition, reason)
            }
            if (book !== currentBook || session?.userKey != currentSession.userKey) { result.set(SessionResult(SessionError.ERROR_BAD_VALUE)); return@launch }
            if (recoverable) undoMs = previousPosition
            val (index, local) = currentBook.locate(value)
            internalSeek = true
            player.seekTo(index, local)
            internalSeek = false
            failure = null
            persist()?.let { it.join(); check(!it.isCancelled) }; publish()
            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { result.cancel(false); throw cancelled }
            catch (_: Exception) { failure = "The player couldn't move to that place. Your previous position is in History."; publish(); result.set(SessionResult(SessionError.ERROR_UNKNOWN)) }
        }
        return result
    }
    private fun checkpoint(reason: String, bookmark: Boolean = false) {
        val currentBook = book ?: return
        val currentSession = session ?: return
        val place = position()
        scope.launch(storageDispatcher) { graph.store.place(currentSession.userKey, currentBook.summary.id, place, reason, bookmark) }
        persist(reason in setOf("Paused", "Closed book", "Changed book", "Sleep timer stopped playback"))
    }
    private fun persist(forceBackup: Boolean = false): kotlinx.coroutines.Job? {
        val currentBook = book ?: return null
        val currentSession = session ?: return null
        val currentPosition = position()
        val speed = player.playbackParameters.speed
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        return scope.launch(storageDispatcher) {
            graph.store.save(currentSession.userKey, currentBook, currentPosition, speed)
            graph.store.queueProgress(currentSession.userKey, currentBook, currentPosition, playing)
            graph.backups.request(forceBackup)
        }
    }
    private fun sync() {
        if (book != null && session != null) { persist(); scope.launch(storageDispatcher) { graph.sync.wake() } }
    }
    private fun publish() {
        graph.playback.value = PlaybackState(book, position(), player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
            player.playbackParameters.speed, undoMs, sleepDeadline, sleepChapterEnd != null, failure, sleepSession)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession
    override fun onDestroy() {
        val currentBook = book
        val currentSession = session
        val savedPosition = position()
        val savedSpeed = player.playbackParameters.speed
        scope.cancel()
        if (currentBook != null && currentSession != null) runBlocking(Dispatchers.IO) {
            graph.store.save(currentSession.userKey, currentBook, savedPosition, savedSpeed)
            graph.store.queueProgress(currentSession.userKey, currentBook, savedPosition, false)
            graph.backups.request(true)
        }
        book = null; session = null
        mediaSession.release(); player.release(); graph.playback.value = PlaybackState(); super.onDestroy()
    }
    private fun armTimer(minutes: Int, extend: Boolean) {
        require(minutes == -1 || minutes in 0..240)
        val current = book ?: return
        val user = session?.userKey ?: return
        val generation = ++timerGeneration
        if (minutes == 0) {
            sleepDeadline = null; sleepChapterEnd = null; player.volume = 1f
            sleepSession = sleepSession?.copy(endedMs = System.currentTimeMillis())
            scope.launch(storageDispatcher) { graph.store.endSleep(user, current.summary.id) }
            return
        }
        if (extend && sleepDeadline != null) {
            sleepDeadline = sleepDeadline!! + minutes * 60000L; player.volume = 1f; publish(); return
        }
        val start = position()
        scope.launch {
            try {
                val saved = withContext(storageDispatcher) { graph.store.startSleep(user, current.summary.id, start) }
                if (book !== current || session?.userKey != user || generation != timerGeneration) return@launch
                sleepSession = saved
                sleepDeadline = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60000L else null
                sleepChapterEnd = if (minutes == -1) current.chapterAt(position())?.endMs else null
                graph.preferences.sleepMinutes(user, minutes); player.volume = 1f; publish()
                graph.backups.request(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failure = "The sleep timer couldn't start. Please try again."; publish() }
        }
    }
}
