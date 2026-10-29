@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.family.audiobooks

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

fun offlineKey(user: String, book: String, track: AudioTrack): String = MessageDigest.getInstance("SHA-256")
    .digest("$user\u0000$book\u0000${track.id}\u0000${track.partKey}".toByteArray()).joinToString("") { "%02x".format(it) }
data class OfflineBookStatus(val book: AudioBook, val ready: Boolean, val percent: Int, val bytes: Long,
    val failed: Boolean, val paused: Boolean, val waiting: Boolean)

class OfflineDownloads(private val context: Context, private val vault: SessionCredentials, private val store: ListeningStore,
    private val plex: PlexClient, private val preferences: AppPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "offline-audio"), NoOpCacheEvictor(), database)
    private val downloadSource = DataSource.Factory { AuthorisedDownloadSource() }
    val manager = DownloadManager(context, database, cache, downloadSource, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
        setRequirements(Requirements(if (preferences.wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK))
    }
    private val mutable = MutableStateFlow<Map<String, List<OfflineBookStatus>>>(emptyMap())
    val state = mutable.asStateFlow()
    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) { refresh() }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) { refresh() }
        })
        scope.launch { while (true) { update(); delay(1000) } }
    }
    private fun update() {
        val users = listOfNotNull(vault.session()?.userKey).toSet()
        mutable.value = users.associateWith { user -> store.offlineBooks(user).map { book ->
            val entries = book.tracks.map { manager.downloadIndex.getDownload(offlineKey(user, book.summary.id, it)) }
            val ready = entries.all { it?.state == Download.STATE_COMPLETED }
            val percent = entries.map { entry -> if (entry?.state == Download.STATE_COMPLETED) 100f else entry?.percentDownloaded?.coerceAtLeast(0f) ?: 0f }.average().toInt()
            OfflineBookStatus(book, ready, percent, entries.sumOf { it?.bytesDownloaded ?: 0L }, entries.any { it?.state == Download.STATE_FAILED },
                !ready && manager.downloadsPaused, !ready && manager.notMetRequirements != 0)
        } }
    }
    fun refresh() { scope.launch { update() } }
    fun status(user: String, book: String) = mutable.value[user]?.firstOrNull { it.book.summary.id == book }
    fun coverFile(user: String, book: String) = File(context.filesDir, "offline-covers").apply { mkdirs() }
        .resolve(MessageDigest.getInstance("SHA-256").digest("$user/$book".toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg")
    suspend fun add(session: Session, book: AudioBook) {
        store.saveOffline(session.userKey, book)
        book.tracks.forEach { track ->
            val key = offlineKey(session.userKey, book.summary.id, track)
            val request = DownloadRequest.Builder(key, Uri.parse(plex.url(session, track.partKey))).setCustomCacheKey(key).build()
            DownloadService.sendAddDownload(context, BookDownloadService::class.java, request, true)
        }
        DownloadService.sendResumeDownloads(context, BookDownloadService::class.java, true)
        refresh()
        runCatching { plex.saveCover(session, book.summary, coverFile(session.userKey, book.summary.id)) }
    }
    fun remove(session: Session, book: AudioBook) {
        book.tracks.forEach { DownloadService.sendRemoveDownload(context, BookDownloadService::class.java, offlineKey(session.userKey, book.summary.id, it), true) }
        store.forgetOffline(session.userKey, book.summary.id)
        coverFile(session.userKey, book.summary.id).delete()
        refresh()
    }
    fun wifiOnly(value: Boolean) {
        preferences.wifiOnly = value
        manager.requirements = Requirements(if (value) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        refresh()
    }
    fun pauseAll() { DownloadService.sendPauseDownloads(context, BookDownloadService::class.java, true); refresh() }
    fun resumeAll() { DownloadService.sendResumeDownloads(context, BookDownloadService::class.java, true); refresh() }
    internal fun release() { scope.cancel(); manager.release(); cache.release(); database.close() }
    fun playbackSource(upstream: DataSource.Factory): DataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(upstream)
    /** A resumed download resolves its own encrypted profile session; credentials never enter the download index. */
    private inner class AuthorisedDownloadSource : DataSource {
        private var source: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()
        override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; source?.addTransferListener(transferListener) }
        override fun open(dataSpec: DataSpec): Long {
            val asset = dataSpec.key?.let(store::offlineAsset) ?: throw IOException("Download metadata is unavailable")
            val session = vault.remembered(asset.first) ?: throw IOException("Sign in to resume this profile's downloads")
            val authenticated = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(false).setDefaultRequestProperties(mapOf("X-Plex-Token" to session.serverToken,
                    "X-Plex-Client-Identifier" to vault.clientId)).createDataSource()
            source = authenticated
            listeners.forEach(authenticated::addTransferListener)
            return authenticated.open(dataSpec.withUri(Uri.parse(plex.url(session, asset.second))))
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source?.read(buffer, offset, length) ?: throw IOException("Download is not open")
        override fun getUri(): Uri? = source?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
        override fun close() { source?.close(); source = null }
    }
}

class BookDownloadService : DownloadService(2001, 1000L, "audiobook-downloads", R.string.download_channel, 0) {
    private val downloads get() = (application as AudioBooksApp).graph.downloads
    override fun getDownloadManager(): DownloadManager = downloads.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, "audiobook-downloads").buildProgressNotification(this, android.R.drawable.stat_sys_download,
            null, "Saving your audiobooks", downloads, notMetRequirements)
    override fun onTimeout(startId: Int, fgsType: Int) { downloads.manager.pauseDownloads(); stopSelf() }
}
