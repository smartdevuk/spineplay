package dev.family.audiobooks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionCredentials {
    val clientId: String
    fun session(): Session?
    fun remembered(userKey: String): Session?
}
/** Tokens never enter source files, media URLs, logs, or Android backup. */
@android.annotation.SuppressLint("ApplySharedPref") // Credential changes must be durable before changing profiles.
class CredentialVault(context: Context) : SessionCredentials {
    private val preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    override val clientId: String = preferences.getString("client", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("client", it).commit()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("audiobooks.session", null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("audiobooks.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    @Synchronized private fun write(name: String, value: String?) {
        if (value == null) { preferences.edit().remove(name).commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(name, payload).commit()) { "Could not save sign-in securely" }
    }
    @Synchronized private fun read(name: String): String? {
        val value = preferences.getString(name, null) ?: return null
        return runCatching {
            val parts = value.split(':', limit = 2)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            }.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }
    fun owner(): Identity? = read("owner")?.let { runCatching { JSONObject(it).let { o -> Identity(o.getString("id"), o.getString("name"), o.getString("token")) } }.getOrNull() }
    fun saveOwner(identity: Identity) = write("owner", JSONObject().put("id", identity.id).put("name", identity.name).put("token", identity.token).toString())
    override fun session(): Session? = read("session")?.let { runCatching { Session.parse(JSONObject(it)) }.getOrNull() }
    fun saveSession(session: Session?) {
        if (session != null) {
            val remembered = read("remembered")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            remembered.put(session.userKey, session.json())
            write("remembered", remembered.toString())
        }
        write("session", session?.json()?.toString())
    }
    override fun remembered(userKey: String): Session? = read("remembered")?.let { runCatching { Session.parse(JSONObject(it).getJSONObject(userKey)) }.getOrNull() }
        ?: session()?.takeIf { it.userKey == userKey }
    fun clear() { write("session", null); write("owner", null); write("remembered", null) }
}

class ListeningStore(context: Context) : SQLiteOpenHelper(context, "listening.db", null, 3) {
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun changed() { revision.value = revision.value + 1 }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE progress (user_key TEXT NOT NULL, book_id TEXT NOT NULL, position INTEGER NOT NULL, updated INTEGER NOT NULL, speed REAL NOT NULL, book TEXT NOT NULL, duration INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE places (id INTEGER PRIMARY KEY AUTOINCREMENT,user_key TEXT NOT NULL,book_id TEXT NOT NULL,position INTEGER NOT NULL,created INTEGER NOT NULL,reason TEXT NOT NULL,bookmark INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX places_lookup ON places(user_key,book_id,bookmark,created)")
        createOfflineTables(db)
        createRecoveryTables(db)
    }
    private fun createOfflineTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS library_cache (user_key TEXT PRIMARY KEY, books TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS offline_books (user_key TEXT NOT NULL, book_id TEXT NOT NULL, book TEXT NOT NULL, PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS offline_assets (cache_key TEXT PRIMARY KEY, user_key TEXT NOT NULL, book_id TEXT NOT NULL, part_key TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chapter_overrides (user_key TEXT NOT NULL, book_id TEXT NOT NULL, book TEXT NOT NULL, PRIMARY KEY(user_key,book_id))")
    }
    private fun createRecoveryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_flags (user_key TEXT NOT NULL,book_id TEXT NOT NULL,status TEXT NOT NULL DEFAULT '',later INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL,PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_sessions (user_key TEXT NOT NULL,book_id TEXT NOT NULL,position INTEGER NOT NULL,created INTEGER NOT NULL,ended INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS book_metadata (user_key TEXT NOT NULL,book_id TEXT NOT NULL,book TEXT NOT NULL,PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (user_key TEXT NOT NULL,book_id TEXT NOT NULL,book TEXT NOT NULL,position INTEGER NOT NULL,playing INTEGER NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_anchors (user_key TEXT NOT NULL,book_id TEXT NOT NULL,remote_time INTEGER NOT NULL,remote_position INTEGER NOT NULL,last_sent INTEGER,PRIMARY KEY(user_key,book_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflicts (user_key TEXT NOT NULL,book_id TEXT NOT NULL,remote_time INTEGER NOT NULL,remote_position INTEGER NOT NULL,PRIMARY KEY(user_key,book_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createOfflineTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE progress ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
            db.rawQuery("SELECT user_key,book_id,book FROM progress", null).use { c -> while (c.moveToNext()) {
                val duration = runCatching { AudioBook.parse(c.getString(2)).durationMs }.getOrDefault(0)
                db.execSQL("UPDATE progress SET duration=? WHERE user_key=? AND book_id=?", arrayOf(duration, c.getString(0), c.getString(1)))
            } }
            createRecoveryTables(db)
        }
    }
    @Synchronized fun reading(user: String): Map<String, ReadingState> {
        val result = mutableMapOf<String, ReadingState>()
        readableDatabase.rawQuery("SELECT p.book_id,p.position,p.duration,f.status,f.later,f.updated FROM progress p LEFT JOIN book_flags f ON p.user_key=f.user_key AND p.book_id=f.book_id WHERE p.user_key=?", arrayOf(user)).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = ReadingState(readingStatus(c.getLong(1), c.getLong(2), c.getString(3)), c.getInt(4) == 1, c.getLong(5))
        }
        readableDatabase.rawQuery("SELECT book_id,status,later,updated FROM book_flags WHERE user_key=?", arrayOf(user)).use { c -> while (c.moveToNext()) {
            if (c.getString(0) !in result) result[c.getString(0)] = ReadingState(readingStatus(0, 0, c.getString(1)), c.getInt(2) == 1, c.getLong(3))
        } }
        return result
    }
    @Synchronized fun explicitStatus(user: String, book: String): ReadingStatus? = readableDatabase.rawQuery("SELECT status FROM book_flags WHERE user_key=? AND book_id=?", arrayOf(user, book)).use {
        if (it.moveToFirst()) runCatching { ReadingStatus.valueOf(it.getString(0)) }.getOrNull() else null
    }
    @Synchronized fun storedBook(user: String, book: String): AudioBook? = readableDatabase.rawQuery("SELECT book FROM progress WHERE user_key=? AND book_id=?", arrayOf(user, book)).use {
        if (it.moveToFirst()) runCatching { AudioBook.parse(it.getString(0)) }.getOrNull() else null
    }
    @Synchronized fun setReadingStatus(user: String, book: String, status: ReadingStatus) {
        ensureFlag(user, book)
        writableDatabase.execSQL("UPDATE book_flags SET status=?,updated=? WHERE user_key=? AND book_id=?", arrayOf(status.name, System.currentTimeMillis(), user, book)); changed()
    }
    @Synchronized fun setLater(user: String, book: String, later: Boolean) {
        ensureFlag(user, book)
        writableDatabase.execSQL("UPDATE book_flags SET later=?,updated=? WHERE user_key=? AND book_id=?", arrayOf(if (later) 1 else 0, System.currentTimeMillis(), user, book)); changed()
    }
    private fun ensureFlag(user: String, book: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO book_flags(user_key,book_id,updated) VALUES(?,?,?)", arrayOf(user, book, System.currentTimeMillis())) }
    @Synchronized fun sleep(user: String, book: String): SleepSession? = readableDatabase.rawQuery("SELECT position,created,ended FROM sleep_sessions WHERE user_key=? AND book_id=?", arrayOf(user, book)).use {
        if (it.moveToFirst()) SleepSession(it.getLong(0), it.getLong(1), it.getLong(2)) else null
    }
    @Synchronized fun startSleep(user: String, book: String, position: Long): SleepSession {
        val session = SleepSession(position, System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("sleep_sessions", null, ContentValues().apply { put("user_key", user); put("book_id", book); put("position", position); put("created", session.createdMs); put("ended", 0) }, SQLiteDatabase.CONFLICT_REPLACE)
        place(user, book, position, "Sleep timer started"); changed(); return session
    }
    @Synchronized fun endSleep(user: String, book: String) { writableDatabase.execSQL("UPDATE sleep_sessions SET ended=? WHERE user_key=? AND book_id=?", arrayOf(System.currentTimeMillis(), user, book)); changed() }
    @Synchronized fun saveSeries(user: String, summary: BookSummary) {
        writableDatabase.insertWithOnConflict("book_metadata", null, ContentValues().apply { put("user_key", user); put("book_id", summary.id); put("book", AudioBook(summary, emptyList(), emptyList()).json()) }, SQLiteDatabase.CONFLICT_REPLACE); changed()
    }
    @Synchronized fun applySeries(user: String, books: List<BookSummary>): List<BookSummary> {
        val overrides = mutableMapOf<String, List<SeriesRef>>()
        readableDatabase.rawQuery("SELECT book_id,book FROM book_metadata WHERE user_key=?", arrayOf(user)).use { c -> while (c.moveToNext()) {
            runCatching { AudioBook.parse(c.getString(1)).summary.series }.getOrNull()?.let { overrides[c.getString(0)] = it }
        } }
        return books.map { book -> overrides[book.id]?.let { book.copy(series = it) } ?: book }
    }
    @Synchronized fun recent(user: String): List<ListeningBook> = readableDatabase.rawQuery(
        "SELECT book,position,updated,speed FROM progress WHERE user_key=? AND position>0 ORDER BY updated DESC LIMIT 30", arrayOf(user)).use { c -> buildList {
            while (c.moveToNext()) runCatching { AudioBook.parse(c.getString(0)) }.getOrNull()?.let { book ->
                if (c.getLong(1) < book.durationMs - 10000) add(ListeningBook(book, SavedProgress(c.getLong(1), c.getLong(2), c.getFloat(3))))
            }
        } }
    @Synchronized fun cacheLibrary(user: String, books: List<BookSummary>) {
        val payload = org.json.JSONArray(books.map { AudioBook(it, emptyList(), emptyList()).json() }).toString()
        writableDatabase.insertWithOnConflict("library_cache", null, ContentValues().apply { put("user_key", user); put("books", payload) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun cachedLibrary(user: String): List<BookSummary> = readableDatabase.rawQuery("SELECT books FROM library_cache WHERE user_key=?", arrayOf(user)).use {
        if (!it.moveToFirst()) emptyList() else runCatching { org.json.JSONArray(it.getString(0)).strings().map { json -> AudioBook.parse(json).summary } }.getOrDefault(emptyList())
    }
    @Synchronized fun offlineBooks(user: String): List<AudioBook> = readableDatabase.rawQuery("SELECT book FROM offline_books WHERE user_key=?", arrayOf(user)).use { c -> buildList {
        while (c.moveToNext()) runCatching { AudioBook.parse(c.getString(0)) }.getOrNull()?.let(::add)
    } }
    @Synchronized fun saveOffline(user: String, book: AudioBook) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict("offline_books", null, ContentValues().apply { put("user_key", user); put("book_id", book.summary.id); put("book", book.json()) }, SQLiteDatabase.CONFLICT_REPLACE)
            book.tracks.forEach { track -> writableDatabase.insertWithOnConflict("offline_assets", null, ContentValues().apply {
                put("cache_key", offlineKey(user, book.summary.id, track)); put("user_key", user); put("book_id", book.summary.id); put("part_key", track.partKey)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun offlineAsset(key: String): Pair<String, String>? = readableDatabase.rawQuery("SELECT user_key,part_key FROM offline_assets WHERE cache_key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
    }
    @Synchronized fun forgetOffline(user: String, book: String) { writableDatabase.delete("offline_books", "user_key=? AND book_id=?", arrayOf(user, book)) }
    @Synchronized fun saveChapters(user: String, book: AudioBook) {
        writableDatabase.insertWithOnConflict("chapter_overrides", null, ContentValues().apply { put("user_key", user); put("book_id", book.summary.id); put("book", book.json()) }, SQLiteDatabase.CONFLICT_REPLACE)
        if (offlineBooks(user).any { it.summary.id == book.summary.id }) saveOffline(user, book)
    }
    @Synchronized fun applyChapters(user: String, book: AudioBook): AudioBook = readableDatabase.rawQuery("SELECT book FROM chapter_overrides WHERE user_key=? AND book_id=?", arrayOf(user, book.summary.id)).use {
        if (!it.moveToFirst()) book else runCatching { AudioBook.parse(it.getString(0)) }.getOrNull()?.takeIf { saved ->
            sameAudio(saved.tracks, book.tracks)
        }?.let { saved -> book.copy(chapters = saved.chapters, chapterSource = saved.chapterSource) } ?: book
    }
    @Synchronized fun progress(user: String, book: String): SavedProgress? = readableDatabase.rawQuery(
        "SELECT position,updated,speed FROM progress WHERE user_key=? AND book_id=?", arrayOf(user, book)).use {
        if (it.moveToFirst()) SavedProgress(it.getLong(0), it.getLong(1), it.getFloat(2)) else null
    }
    @Synchronized fun save(user: String, book: AudioBook, position: Long, speed: Float) {
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("user_key", user); put("book_id", book.summary.id); put("position", position.coerceIn(0, book.durationMs))
            put("updated", System.currentTimeMillis()); put("speed", speed); put("book", book.json()); put("duration", book.durationMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (position > 1000) writableDatabase.execSQL("UPDATE book_flags SET status='' WHERE user_key=? AND book_id=? AND status='NOT_STARTED'", arrayOf(user, book.summary.id))
        changed()
    }
    @Synchronized fun place(user: String, book: String, position: Long, reason: String, bookmark: Boolean = false) {
        writableDatabase.insertOrThrow("places", null, ContentValues().apply {
            put("user_key", user); put("book_id", book); put("position", position)
            put("created", System.currentTimeMillis()); put("reason", reason); put("bookmark", if (bookmark) 1 else 0)
        })
        if (!bookmark) writableDatabase.execSQL("DELETE FROM places WHERE user_key=? AND book_id=? AND bookmark=0 AND id NOT IN (SELECT id FROM places WHERE user_key=? AND book_id=? AND bookmark=0 ORDER BY created DESC LIMIT 500)", arrayOf(user, book, user, book))
    }
    @Synchronized fun places(user: String, book: String, bookmarks: Boolean = false): List<Place> = readableDatabase.rawQuery(
        "SELECT id,position,created,reason FROM places WHERE user_key=? AND book_id=? AND bookmark=? ORDER BY created DESC LIMIT 500",
        arrayOf(user, book, if (bookmarks) "1" else "0")).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Place(cursor.getLong(0),cursor.getLong(1),cursor.getLong(2),cursor.getString(3)))
        } }
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) { prefs.edit().putBoolean("wifiOnly", value).apply() }
    fun browse(user: String): BrowseState = BrowseState(prefs.getString("$user.query", "").orEmpty(),
        prefs.getString("$user.author", null), prefs.getInt("$user.index", 0), prefs.getInt("$user.offset", 0), prefs.getBoolean("$user.downloads", false),
        prefs.getString("$user.status", "all").orEmpty(), prefs.getString("$user.series", null))
    fun saveBrowse(user: String, state: BrowseState) { prefs.edit().putString("$user.query", state.query).putString("$user.author", state.author)
        .putInt("$user.index", state.index).putInt("$user.offset", state.offset).putBoolean("$user.downloads", state.downloadsOnly)
        .putString("$user.status", state.status).putString("$user.series", state.series).apply() }
    fun sleepMinutes(user: String) = prefs.getInt("$user.sleepMinutes", 30)
    fun sleepMinutes(user: String, minutes: Int) { if (minutes != 0) prefs.edit().putInt("$user.sleepMinutes", minutes).apply() }
    var backupFolder: String?
        get() = prefs.getString("backupFolder", null)
        set(value) { prefs.edit().putString("backupFolder", value).apply() }
    fun backupAt(user: String) = prefs.getLong("$user.backupAt", 0)
    fun backupAt(user: String, value: Long) { prefs.edit().putLong("$user.backupAt", value).apply() }
}
