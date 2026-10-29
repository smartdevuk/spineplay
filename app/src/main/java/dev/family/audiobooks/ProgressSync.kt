package dev.family.audiobooks

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class RemoteProgress(val positionMs: Long, val updatedMs: Long)
data class SyncAnchor(val remote: RemoteProgress, val lastSentMs: Long? = null)
data class PendingProgress(val book: AudioBook, val positionMs: Long, val playing: Boolean, val version: Long)
data class PositionConflict(val bookId: String, val remote: RemoteProgress)

/** Compare against the last observed/sent position, never the furthest position: deliberate rewinds are valid. */
fun progressConflict(pending: PendingProgress, remote: RemoteProgress, anchor: SyncAnchor): Boolean {
    val expected = anchor.lastSentMs ?: anchor.remote.positionMs
    val newer = remote.updatedMs > anchor.remote.updatedMs || remote.positionMs != anchor.remote.positionMs
    return newer && abs(remote.positionMs - expected) > 30000 && abs(remote.positionMs - pending.positionMs) > 30000
}
fun ListeningStore.queueProgress(user: String, book: AudioBook, position: Long, playing: Boolean) = synchronized(this) {
    val db = writableDatabase
    db.execSQL("INSERT OR IGNORE INTO sync_anchors(user_key,book_id,remote_time,remote_position) VALUES(?,?,?,?)", arrayOf(user, book.summary.id, book.remoteUpdatedMs, book.remotePositionMs))
    db.execSQL("INSERT OR IGNORE INTO sync_outbox(user_key,book_id,book,position,playing,version) VALUES(?,?,?,?,?,0)", arrayOf(user, book.summary.id, book.json(), position, if (playing) 1 else 0))
    db.execSQL("UPDATE sync_outbox SET book=?,position=?,playing=?,version=version+1 WHERE user_key=? AND book_id=?", arrayOf(book.json(), position, if (playing) 1 else 0, user, book.summary.id))
}
fun ListeningStore.pendingProgress(user: String): List<PendingProgress> = synchronized(this) {
    readableDatabase.rawQuery("SELECT book,position,playing,version FROM sync_outbox WHERE user_key=?", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) runCatching { AudioBook.parse(c.getString(0)) }.getOrNull()?.let { add(PendingProgress(it, c.getLong(1), c.getInt(2) == 1, c.getLong(3))) }
    } }
}
fun ListeningStore.syncAnchor(user: String, book: String): SyncAnchor = synchronized(this) {
    readableDatabase.rawQuery("SELECT remote_position,remote_time,last_sent FROM sync_anchors WHERE user_key=? AND book_id=?", arrayOf(user, book)).use {
        if (it.moveToFirst()) SyncAnchor(RemoteProgress(it.getLong(0), it.getLong(1)), if (it.isNull(2)) null else it.getLong(2)) else SyncAnchor(RemoteProgress(0, 0))
    }
}
fun ListeningStore.acceptRemote(user: String, book: String, remote: RemoteProgress) = synchronized(this) {
    writableDatabase.insertWithOnConflict("sync_anchors", null, ContentValues().apply { put("user_key", user); put("book_id", book); put("remote_position", remote.positionMs); put("remote_time", remote.updatedMs) }, SQLiteDatabase.CONFLICT_REPLACE)
    writableDatabase.delete("sync_conflicts", "user_key=? AND book_id=?", arrayOf(user, book)); changed()
}
fun ListeningStore.ackProgress(user: String, pending: PendingProgress, remote: RemoteProgress) = synchronized(this) {
    writableDatabase.beginTransaction()
    try {
        writableDatabase.execSQL("UPDATE sync_anchors SET remote_time=?,remote_position=?,last_sent=? WHERE user_key=? AND book_id=?", arrayOf(remote.updatedMs, remote.positionMs, pending.positionMs, user, pending.book.summary.id))
        writableDatabase.delete("sync_outbox", "user_key=? AND book_id=? AND version=?", arrayOf(user, pending.book.summary.id, pending.version.toString()))
        writableDatabase.setTransactionSuccessful()
    } finally { writableDatabase.endTransaction() }
    changed()
}
fun ListeningStore.conflicts(user: String): List<PositionConflict> = synchronized(this) {
    readableDatabase.rawQuery("SELECT book_id,remote_position,remote_time FROM sync_conflicts WHERE user_key=?", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) add(PositionConflict(c.getString(0), RemoteProgress(c.getLong(1), c.getLong(2))))
    } }
}
fun ListeningStore.saveConflict(user: String, pending: PendingProgress, remote: RemoteProgress) = synchronized(this) {
    writableDatabase.insertWithOnConflict("sync_conflicts", null, ContentValues().apply { put("user_key", user); put("book_id", pending.book.summary.id); put("remote_time", remote.updatedMs); put("remote_position", remote.positionMs) }, SQLiteDatabase.CONFLICT_REPLACE)
    place(user, pending.book.summary.id, remote.positionMs, "Plex position before sync conflict")
    place(user, pending.book.summary.id, pending.positionMs, "This phone before sync conflict"); changed()
}

class ProgressSync(private val vault: SessionCredentials, private val store: ListeningStore,
    private val readRemote: suspend (Session, AudioBook) -> RemoteProgress,
    private val writeRemote: suspend (Session, AudioBook, Long, Boolean) -> Unit,
    autoStart: Boolean = true,
    private val isPlaying: (String, String) -> Boolean = { _, _ -> false }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    init {
        if (autoStart) {
            scope.launch { for (signal in signals) drain() }
            scope.launch { while (isActive) { signals.trySend(Unit); delay(30000) } }
        }
    }
    fun wake() { signals.trySend(Unit) }
    suspend fun drain() = mutex.withLock {
        val session = vault.session() ?: return@withLock
        for (pending in store.pendingProgress(session.userKey).take(20)) {
            if (store.conflicts(session.userKey).any { it.bookId == pending.book.summary.id }) continue
            try {
                val remote = readRemote(session, pending.book)
                if (vault.session()?.userKey != session.userKey) return@withLock
                if (progressConflict(pending, remote, store.syncAnchor(session.userKey, pending.book.summary.id))) {
                    store.saveConflict(session.userKey, pending, remote); continue
                }
                writeRemote(session, pending.book, pending.positionMs, pending.playing && isPlaying(session.userKey, pending.book.summary.id))
                store.ackProgress(session.userKey, pending, remote)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { break } // Durable outbox remains intact; a later launch/connection retries it.
        }
    }
    suspend fun resolve(book: AudioBook, localPosition: Long, useRemote: Boolean,
        applyChoice: suspend (RemoteProgress) -> Long = { if (useRemote) it.positionMs else localPosition }): RemoteProgress = mutex.withLock {
        val session = vault.session() ?: error("Please sign in first.")
        val remote = readRemote(session, book)
        require(vault.session()?.userKey == session.userKey) { "The listening profile changed. Please try again." }
        store.place(session.userKey, book.summary.id, localPosition, "This phone before position choice")
        store.place(session.userKey, book.summary.id, remote.positionMs, "Plex before position choice")
        // Hold the sync lock until the player has actually moved and saved its
        // checkpoint, so an old queued position cannot race a user's choice.
        val chosen = applyChoice(remote)
        require(vault.session()?.userKey == session.userKey) { "The listening profile changed. Please try again." }
        store.acceptRemote(session.userKey, book.summary.id, remote)
        store.queueProgress(session.userKey, book, chosen, false)
        wake()
        remote
    }
    internal fun release() { scope.cancel() }
}
