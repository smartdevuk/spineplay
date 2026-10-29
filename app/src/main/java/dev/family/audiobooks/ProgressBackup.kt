package dev.family.audiobooks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
data class ProgressSnapshot(val book: AudioBook, val progress: SavedProgress)
data class ArchivedPlace(val bookId: String, val position: Long, val created: Long, val reason: String, val bookmark: Boolean)
data class ArchivedFlag(val bookId: String, val status: String, val later: Boolean, val updated: Long)
data class ArchivedSleep(val bookId: String, val sleep: SleepSession)
data class ProgressArchive(val user: String, val created: Long, val progress: List<ProgressSnapshot>, val places: List<ArchivedPlace>,
    val flags: List<ArchivedFlag>, val sleep: List<ArchivedSleep>, val chapters: List<AudioBook>, val metadata: List<AudioBook>) {
    fun encode(): String {
        val payload = JSONObject().put("format", "spineplay-progress").put("version", 1).put("user", user).put("created", created)
            .put("progress", JSONArray(progress.map { JSONObject().put("book", JSONObject(it.book.json())).put("position", it.progress.positionMs).put("updated", it.progress.updatedMs).put("speed", it.progress.speed) }))
            .put("places", JSONArray(places.map { JSONObject().put("book", it.bookId).put("position", it.position).put("created", it.created).put("reason", it.reason).put("bookmark", it.bookmark) }))
            .put("flags", JSONArray(flags.map { JSONObject().put("book", it.bookId).put("status", it.status).put("later", it.later).put("updated", it.updated) }))
            .put("sleep", JSONArray(sleep.map { JSONObject().put("book", it.bookId).put("position", it.sleep.positionMs).put("created", it.sleep.createdMs).put("ended", it.sleep.endedMs) }))
            .put("chapters", JSONArray(chapters.map { JSONObject(it.json()) })).put("metadata", JSONArray(metadata.map { JSONObject(it.json()) })).toString()
        return JSONObject().put("payload", payload).put("sha256", digest(payload)).toString().also { require(it.toByteArray().size <= MAX_ARCHIVE_BYTES) { "This backup is too large to export." } }
    }
    companion object {
        fun parse(text: String, user: String, now: Long = System.currentTimeMillis()): ProgressArchive {
            require(text.toByteArray().size <= MAX_ARCHIVE_BYTES) { "This file is too large to be a Spineplay backup." }
            checkNesting(text)
            val envelope = JSONObject(text)
            val raw = envelope.getString("payload")
            checkNesting(raw)
            require(MessageDigest.isEqual(digest(raw).toByteArray(), envelope.getString("sha256").toByteArray())) { "This backup is incomplete or damaged." }
            val data = JSONObject(raw)
            require(data.getString("format") == "spineplay-progress" && data.getInt("version") == 1) { "This backup format is not supported." }
            require(data.getString("user") == user) { "This backup belongs to another Plex server or user. Choose that user before restoring it." }
            fun timestamp(value: Long): Long { require(value in 0..now + 86400000) { "This backup has invalid dates." }; return value }
            fun id(value: String): String { require(value.isNotBlank() && value.length <= 512); return value }
            fun book(o: JSONObject, hasAudio: Boolean = true): AudioBook {
                val result = AudioBook.parse(o.toString()); id(result.summary.id)
                require(result.summary.series.all { it.number == null || it.number.isFinite() && it.number >= 0 })
                if (hasAudio) {
                    require(result.tracks.size in 1..10000 && result.durationMs in 1..31536000000L)
                    require(result.tracks.fold(0L) { total, track -> Math.addExact(total, track.durationMs) } == result.durationMs)
                    require(result.tracks.all { it.durationMs > 0 && it.partKey.startsWith('/') && !it.partKey.startsWith("//") })
                    require(result.chapters.size <= 10000 && result.chapters.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= result.durationMs })
                    require(result.chapters.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
                }
                return result
            }
            fun array(name: String) = data.optJSONArray(name)?.objects().orEmpty().also { require(it.size <= 20000) }
            val progress = array("progress").map { o ->
                val b = book(o.getJSONObject("book")); val position = o.getLong("position"); val speed = o.getDouble("speed").toFloat()
                require(position in 0..b.durationMs && speed.isFinite() && speed in .5f..3f)
                ProgressSnapshot(b, SavedProgress(position, timestamp(o.getLong("updated")), speed))
            }
            require(progress.map { it.book.summary.id }.distinct().size == progress.size)
            val places = array("places").map { ArchivedPlace(id(it.getString("book")), it.getLong("position").also { value -> require(value in 0..31536000000L) }, timestamp(it.getLong("created")), it.getString("reason").take(512), it.getBoolean("bookmark")) }
            val flags = array("flags").map { o -> ArchivedFlag(id(o.getString("book")), o.getString("status").also { require(it.isEmpty() || ReadingStatus.entries.any { status -> status.name == it }) }, o.getBoolean("later"), timestamp(o.getLong("updated"))) }
            val sleeps = array("sleep").map { o -> ArchivedSleep(id(o.getString("book")), SleepSession(o.getLong("position").also { require(it in 0..31536000000L) }, timestamp(o.getLong("created")), timestamp(o.getLong("ended")))) }
            return ProgressArchive(user, timestamp(data.getLong("created")), progress, places, flags, sleeps, array("chapters").map { book(it) }, array("metadata").map { book(it, false) })
        }
    }
}
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun checkNesting(text: String) {
    var depth = 0; var quoted = false; var escaped = false
    text.forEach { char ->
        if (escaped) escaped = false
        else if (quoted && char == '\\') escaped = true
        else if (char == '"') quoted = !quoted
        else if (!quoted) when (char) { '{', '[' -> { depth++; require(depth <= 32) { "This file is not a supported progress backup." } }; '}', ']' -> depth-- }
    }
}
fun ListeningStore.archive(user: String): ProgressArchive = synchronized(this) {
    val progress = readableDatabase.rawQuery("SELECT book,position,updated,speed FROM progress WHERE user_key=?", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) add(ProgressSnapshot(AudioBook.parse(c.getString(0)), SavedProgress(c.getLong(1), c.getLong(2), c.getFloat(3))))
    } }
    val places = readableDatabase.rawQuery("SELECT book_id,position,created,reason,bookmark FROM places WHERE user_key=? ORDER BY bookmark DESC,created DESC LIMIT 20000", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) add(ArchivedPlace(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4) == 1))
    } }
    val flags = readableDatabase.rawQuery("SELECT book_id,status,later,updated FROM book_flags WHERE user_key=?", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) add(ArchivedFlag(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getLong(3)))
    } }
    val sleep = readableDatabase.rawQuery("SELECT book_id,position,created,ended FROM sleep_sessions WHERE user_key=?", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) add(ArchivedSleep(c.getString(0), SleepSession(c.getLong(1), c.getLong(2), c.getLong(3))))
    } }
    fun snapshots(table: String) = readableDatabase.rawQuery("SELECT book FROM $table WHERE user_key=?", arrayOf(user)).use { c -> buildList { while (c.moveToNext()) add(AudioBook.parse(c.getString(0))) } }
    ProgressArchive(user, System.currentTimeMillis(), progress, places, flags, sleep, snapshots("chapter_overrides"), snapshots("book_metadata"))
}
data class RestoreResult(val restored: Int, val alternatives: Int)
fun ListeningStore.restore(archive: ProgressArchive): RestoreResult = synchronized(this) {
    var restored = 0; var alternatives = 0
    val db = writableDatabase
    fun place(p: ArchivedPlace) {
        db.execSQL("INSERT INTO places(user_key,book_id,position,created,reason,bookmark) SELECT ?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM places WHERE user_key=? AND book_id=? AND position=? AND created=? AND reason=? AND bookmark=?)",
            arrayOf(archive.user, p.bookId, p.position, p.created, p.reason, if (p.bookmark) 1 else 0, archive.user, p.bookId, p.position, p.created, p.reason, if (p.bookmark) 1 else 0))
    }
    db.beginTransaction()
    try {
        archive.progress.forEach { entry ->
            val id = entry.book.summary.id
            val current = progress(archive.user, id)
            if (current == null) {
                db.insertOrThrow("progress", null, ContentValues().apply {
                    put("user_key", archive.user); put("book_id", id); put("position", entry.progress.positionMs); put("updated", entry.progress.updatedMs)
                    put("speed", entry.progress.speed); put("book", entry.book.json()); put("duration", entry.book.durationMs)
                }); restored++
            } else if (kotlin.math.abs(current.positionMs - entry.progress.positionMs) > 1000) {
                place(ArchivedPlace(id, entry.progress.positionMs, entry.progress.updatedMs, "Backup alternative", false)); alternatives++
            }
        }
        archive.places.forEach(::place)
        archive.flags.forEach { flag ->
            db.execSQL("INSERT OR IGNORE INTO book_flags(user_key,book_id,updated) VALUES(?,?,0)", arrayOf(archive.user, flag.bookId))
            db.execSQL("UPDATE book_flags SET status=?,later=?,updated=? WHERE user_key=? AND book_id=? AND updated<?", arrayOf(flag.status, if (flag.later) 1 else 0, flag.updated, archive.user, flag.bookId, flag.updated))
        }
        archive.sleep.forEach { value ->
            val current = sleep(archive.user, value.bookId)
            if (current == null || current.createdMs < value.sleep.createdMs) db.insertWithOnConflict("sleep_sessions", null, ContentValues().apply {
                put("user_key", archive.user); put("book_id", value.bookId); put("position", value.sleep.positionMs); put("created", value.sleep.createdMs); put("ended", value.sleep.endedMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        // Existing metadata/chapter corrections win. Restoring never changes currently playing audio.
        listOf("chapter_overrides" to archive.chapters, "book_metadata" to archive.metadata).forEach { (table, books) -> books.forEach { book ->
            db.insertWithOnConflict(table, null, ContentValues().apply { put("user_key", archive.user); put("book_id", book.summary.id); put("book", book.json()) }, SQLiteDatabase.CONFLICT_IGNORE)
        } }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    changed(); RestoreResult(restored, alternatives)
}

data class BackupState(val configured: Boolean = false, val lastSavedMs: Long = 0, val error: String? = null, val saving: Boolean = false)
class ProgressBackup(private val context: Context, private val vault: SessionCredentials, private val store: ListeningStore, private val preferences: AppPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Boolean>(Channel.CONFLATED)
    private val forceQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mutex = Mutex()
    private var lastRevision = -1L
    private var lastUser: String? = null
    @Volatile private var savingUser: String? = null
    private val mutable = MutableStateFlow(BackupState(preferences.backupFolder != null, vault.session()?.let { preferences.backupAt(it.userKey) } ?: 0))
    val state = mutable.asStateFlow()
    init { scope.launch { for (signal in signals) {
        val force = forceQueued.getAndSet(false)
        if (!force && lastUser == vault.session()?.userKey && System.currentTimeMillis() - mutable.value.lastSavedMs < 300000) continue
        val requestedUser = vault.session()?.userKey
        runCatching { save(force) }.onFailure { if (requestedUser == vault.session()?.userKey && preferences.backupFolder != null)
            mutable.value = mutable.value.copy(error = "Backup couldn't be saved. Your places remain on this phone; check the chosen folder.", saving = false) }
    } } }
    fun request(force: Boolean = false) { if (preferences.backupFolder != null) { if (force) forceQueued.set(true); signals.trySend(true) } }
    fun configure(uri: String?) {
        preferences.backupFolder = uri; lastRevision = -1
        refreshProfile()
        if (uri != null) request(true)
    }
    fun refreshProfile() {
        val user = vault.session()?.userKey
        mutable.value = BackupState(preferences.backupFolder != null, user?.let { preferences.backupAt(it) } ?: 0, saving = user != null && user == savingUser)
    }
    suspend fun backupNow() = save(true)
    private suspend fun save(force: Boolean) = mutex.withLock {
        val folder = preferences.backupFolder?.let(Uri::parse) ?: return@withLock
        val session = vault.session() ?: return@withLock
        if (!force && lastRevision == store.revision.value && lastUser == session.userKey) return@withLock
        savingUser = session.userKey
        mutable.value = mutable.value.copy(saving = true, error = null)
        try {
        val (archive, revision) = synchronized(store) { store.archive(session.userKey) to store.revision.value }
        val text = archive.encode()
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val prefix = "Spineplay-${digest(session.userKey).take(12)}-"
        val name = "$prefix${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.json"
        val created = DocumentsContract.createDocument(context.contentResolver, parent, "application/json", name) ?: error("Backup folder is unavailable")
        try {
            context.contentResolver.openOutputStream(created, "w")?.use { it.write(text.toByteArray()) } ?: error("Cannot write backup")
            val readback = read(created)
            require(readback == text) { "Backup verification failed" }
            ProgressArchive.parse(readback, session.userKey)
        } catch (error: Exception) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }; throw error }
        if (preferences.backupFolder != folder.toString()) return@withLock
        // Append first, verify, then retain the newest three files belonging to this profile only.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val existing = mutableListOf<Pair<String, String>>()
        runCatching { context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), android.os.Bundle.EMPTY, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1).matches(Regex("${Regex.escape(prefix)}[0-9]{13}-[0-9a-f]{6}\\.json"))) existing += c.getString(1) to c.getString(0)
        } }
        var kept = 1
        existing.sortedByDescending { it.first }.filter { it.second != DocumentsContract.getDocumentId(created) }.forEach { (_, id) ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(folder, id)
            val valid = runCatching { ProgressArchive.parse(read(uri), session.userKey) }.isSuccess
            if (valid && kept < 3) kept++ else runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
        lastRevision = revision; lastUser = session.userKey
        val saved = System.currentTimeMillis()
        preferences.backupAt(session.userKey, saved)
        if (vault.session()?.userKey == session.userKey) mutable.value = BackupState(true, saved)
        } finally { savingUser = null; mutable.value = mutable.value.copy(saving = false) }
    }
    fun export(uri: Uri, user: String) { context.contentResolver.openOutputStream(uri, "w")?.use { it.write(store.archive(user).encode().toByteArray()) } ?: error("Could not write that backup file.") }
    fun preview(uri: Uri, user: String): ProgressArchive = ProgressArchive.parse(read(uri), user)
    private fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (output.size() <= MAX_ARCHIVE_BYTES) { val size = input.read(buffer); if (size < 0) break; output.write(buffer, 0, size) }
        require(output.size() <= MAX_ARCHIVE_BYTES) { "This backup file is too large." }; output.toString("UTF-8")
    } ?: error("Could not read that backup file.")
    internal fun release() { scope.cancel() }
}
