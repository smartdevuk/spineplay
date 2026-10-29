package dev.family.audiobooks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryTest {
    private val book = AudioBook(BookSummary("book", "A book", "Author", "/cover", libraryId = "4"),
        listOf(AudioTrack("track", "Audio", "/audio.mp3", 600000)), listOf(Chapter("One", 0, 300000), Chapter("Two", 300000, 600000)),
        remotePositionMs = 10000, remoteUpdatedMs = 1000)
    private fun store(): ListeningStore {
        val context = ApplicationProvider.getApplicationContext<Context>(); context.deleteDatabase("listening.db"); return ListeningStore(context)
    }
    private val alice = Session(Identity("alice", "Alice", "fake-account-secret"), "server", "Server", "http://127.0.0.1", "fake-server-secret", "4")
    private val bob = alice.copy(profile = Identity("bob", "Bob", "fake-bob-secret"))
    private class Credentials(var current: Session) : SessionCredentials {
        override val clientId = "test-client"
        override fun session() = current
        override fun remembered(userKey: String) = current.takeIf { it.userKey == userKey }
    }
    @Test fun `upgrading either shipped database version preserves progress bookmarks and speed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..2) {
            context.deleteDatabase("listening.db")
            context.openOrCreateDatabase("listening.db", Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE progress(user_key TEXT NOT NULL,book_id TEXT NOT NULL,position INTEGER NOT NULL,updated INTEGER NOT NULL,speed REAL NOT NULL,book TEXT NOT NULL,PRIMARY KEY(user_key,book_id))")
                db.execSQL("CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT,user_key TEXT NOT NULL,book_id TEXT NOT NULL,position INTEGER NOT NULL,created INTEGER NOT NULL,reason TEXT NOT NULL,bookmark INTEGER NOT NULL DEFAULT 0)")
                if (version == 2) {
                    db.execSQL("CREATE TABLE library_cache(user_key TEXT PRIMARY KEY,books TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE offline_books(user_key TEXT NOT NULL,book_id TEXT NOT NULL,book TEXT NOT NULL,PRIMARY KEY(user_key,book_id))")
                    db.execSQL("CREATE TABLE offline_assets(cache_key TEXT PRIMARY KEY,user_key TEXT NOT NULL,book_id TEXT NOT NULL,part_key TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE chapter_overrides(user_key TEXT NOT NULL,book_id TEXT NOT NULL,book TEXT NOT NULL,PRIMARY KEY(user_key,book_id))")
                }
                val legacy = JSONObject(book.json()).apply { remove("series"); remove("details"); remove("remotePosition"); remove("remoteUpdated") }.toString()
                db.execSQL("INSERT INTO progress VALUES(?,?,?,?,?,?)", arrayOf<Any>(alice.userKey, "book", 80000, 1000, 1.25, legacy))
                db.execSQL("INSERT INTO places(user_key,book_id,position,created,reason,bookmark) VALUES(?,?,?,?,?,?)", arrayOf<Any>(alice.userKey, "book", 75000, 1000, "Bookmark", 1))
                db.version = version
            }
            ListeningStore(context).use { store ->
                assertEquals(80000L, store.progress(alice.userKey, "book")!!.positionMs)
                assertEquals(1.25f, store.progress(alice.userKey, "book")!!.speed)
                assertEquals(ReadingStatus.IN_PROGRESS, store.reading(alice.userKey)["book"]!!.status)
                assertEquals(75000L, store.places(alice.userKey, "book", true).single().positionMs)
                assertEquals(book.durationMs, store.storedBook(alice.userKey, "book")!!.durationMs)
                store.queueProgress(alice.userKey, book, 80000, false)
                assertEquals(1, store.pendingProgress(alice.userKey).size)
            }
        }
    }
    @Test fun `backup round trip preserves places bookmarks flags series and bedtime without credentials`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = store().use { store ->
            store.save(alice.userKey, book, 80000, 1.25f); store.save(bob.userKey, book, 170000, 1f)
            store.place(alice.userKey, "book", 76000, "Bookmark", true)
            store.setReadingStatus(alice.userKey, "book", ReadingStatus.FINISHED); store.setLater(alice.userKey, "book", true)
            store.startSleep(alice.userKey, "book", 73000); store.endSleep(alice.userKey, "book")
            store.saveSeries(alice.userKey, book.summary.copy(series = listOf(SeriesRef("A series", 2.5))))
            store.saveChapters(alice.userKey, book.copy(chapterSource = "Imported"))
            store.archive(alice.userKey)
        }
        val text = archive.encode()
        assertFalse(text.contains("fake-account-secret")); assertFalse(text.contains("fake-server-secret")); assertFalse(text.contains("serverToken"))
        assertEquals(1, archive.progress.size)
        val parsed = ProgressArchive.parse(text, alice.userKey)
        context.deleteDatabase("listening.db")
        ListeningStore(context).use { store ->
            assertEquals(1, store.restore(parsed).restored)
            assertEquals(80000L, store.progress(alice.userKey, "book")!!.positionMs)
            assertEquals(1.25f, store.progress(alice.userKey, "book")!!.speed)
            assertEquals(76000L, store.places(alice.userKey, "book", true).single().positionMs)
            assertEquals(ReadingStatus.FINISHED, store.reading(alice.userKey)["book"]!!.status)
            assertTrue(store.reading(alice.userKey)["book"]!!.later)
            assertEquals(73000L, store.sleep(alice.userKey, "book")!!.positionMs)
            assertEquals("Imported", store.applyChapters(alice.userKey, book).chapterSource)
            assertEquals(2.5, store.applySeries(alice.userKey, listOf(book.summary)).single().series.single().number!!, 0.0)
            assertNull(store.progress(bob.userKey, "book"))
        }
    }
    @Test fun `restoring a different place keeps the current place and deduplicates history`() {
        store().use { store ->
            store.save(alice.userKey, book, 80000, 1f)
            val archive = store.archive(alice.userKey)
            store.save(alice.userKey, book, 15000, 1.5f)
            assertEquals(RestoreResult(0, 1), store.restore(archive))
            store.restore(archive)
            assertEquals(15000L, store.progress(alice.userKey, "book")!!.positionMs)
            assertEquals(1.5f, store.progress(alice.userKey, "book")!!.speed)
            assertEquals(1, store.places(alice.userKey, "book").count { it.reason == "Backup alternative" && it.positionMs == 80000L })
        }
    }
    @Test fun `damaged cross-profile future and excessively nested backups are rejected`() {
        val archive = store().use { it.save(alice.userKey, book, 80000, 1f); it.archive(alice.userKey) }
        val envelope = JSONObject(archive.encode()).put("sha256", "broken").toString()
        assertThrows(IllegalArgumentException::class.java) { ProgressArchive.parse(envelope, alice.userKey) }
        assertThrows(IllegalArgumentException::class.java) { ProgressArchive.parse(archive.encode(), bob.userKey) }
        assertThrows(IllegalArgumentException::class.java) { ProgressArchive.parse(archive.copy(created = System.currentTimeMillis() + 172800000).encode(), alice.userKey) }
        assertThrows(IllegalArgumentException::class.java) { ProgressArchive.parse("[".repeat(1000) + "0" + "]".repeat(1000), alice.userKey) }
    }
    @Test fun `outbox survives failure and reopening then acknowledges only the version sent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = Credentials(alice)
        var store = store()
        store.queueProgress(alice.userKey, book, 80000, true)
        var sync = ProgressSync(credentials, store, { _, _ -> throw java.io.IOException("Offline") }, { _, _, _, _ -> fail("Must not send while offline") }, autoStart = false)
        sync.drain(); assertEquals(80000L, store.pendingProgress(alice.userKey).single().positionMs)
        sync.release(); store.close()
        store = ListeningStore(context)
        var sent = 0L
        sync = ProgressSync(credentials, store, { _, _ -> RemoteProgress(10000, 1000) }, { _, _, position, playing ->
            sent = position; assertFalse(playing)
            store.queueProgress(alice.userKey, book, 90000, false)
        }, autoStart = false)
        try {
            sync.drain(); assertEquals(80000L, sent)
            assertEquals(90000L, store.pendingProgress(alice.userKey).single().positionMs)
        } finally { sync.release(); store.close() }
    }
    @Test fun `new Plex position causes a choice and a deliberate local rewind remains valid`() = runBlocking {
        store().use { store ->
            val credentials = Credentials(alice)
            store.queueProgress(alice.userKey, book, 80000, false)
            var sent = 0L
            val sync = ProgressSync(credentials, store, { _, _ -> RemoteProgress(180000, 2000) }, { _, _, position, _ -> sent = position }, autoStart = false)
            try {
                sync.drain(); assertEquals(0L, sent)
                assertEquals(180000L, store.conflicts(alice.userKey).single().remote.positionMs)
                assertEquals(2, store.places(alice.userKey, "book").size)
                sync.resolve(book, 80000, false)
                sync.drain(); assertEquals(80000L, sent)
                assertTrue(store.pendingProgress(alice.userKey).isEmpty()); assertTrue(store.conflicts(alice.userKey).isEmpty())
            } finally { sync.release() }
        }
    }
    @Test fun `changing profile during a network read cannot publish the previous user's position`() = runBlocking {
        store().use { store ->
            val credentials = Credentials(alice)
            store.queueProgress(alice.userKey, book, 80000, false)
            var writes = 0
            val sync = ProgressSync(credentials, store, { _, _ -> credentials.current = bob; RemoteProgress(10000, 1000) }, { _, _, _, _ -> writes++ }, autoStart = false)
            try { sync.drain(); assertEquals(0, writes); assertEquals(1, store.pendingProgress(alice.userKey).size) } finally { sync.release() }
        }
    }
    @Test fun `choosing Plex holds queued old positions until the player checkpoint is committed`() = runBlocking {
        store().use { store ->
            val credentials = Credentials(alice)
            val writes = mutableListOf<Long>()
            store.queueProgress(alice.userKey, book, 80000, false)
            val sync = ProgressSync(credentials, store, { _, _ -> RemoteProgress(180000, 2000) }, { _, _, position, _ -> writes += position }, autoStart = false)
            try {
                sync.drain(); assertTrue(writes.isEmpty())
                val applying = CompletableDeferred<Unit>(); val commit = CompletableDeferred<Unit>()
                val choice = launch { sync.resolve(book, 80000, true) { remote ->
                    store.queueProgress(alice.userKey, book, 81000, false)
                    applying.complete(Unit); commit.await()
                    store.save(alice.userKey, book, remote.positionMs, 1f)
                    store.queueProgress(alice.userKey, book, remote.positionMs, false)
                    remote.positionMs
                } }
                applying.await()
                val waitingDrain = async { sync.drain() }
                yield(); assertTrue(writes.isEmpty())
                commit.complete(Unit); choice.join(); waitingDrain.await()
                assertEquals(listOf(180000L), writes)
                assertEquals(180000L, store.progress(alice.userKey, "book")!!.positionMs)
            } finally { sync.release() }
        }
    }
    @Test fun `manual not-started status survives reopen and clears only once listening progresses`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store().use { store ->
            store.save(alice.userKey, book, 80000, 1f)
            store.setReadingStatus(alice.userKey, "book", ReadingStatus.NOT_STARTED)
            store.setLater(alice.userKey, "book", true)
        }
        ListeningStore(context).use { store ->
            assertEquals(ReadingStatus.NOT_STARTED, store.reading(alice.userKey)["book"]!!.status)
            assertEquals(80000L, store.progress(alice.userKey, "book")!!.positionMs)
            store.save(alice.userKey, book, 0, 1f)
            assertEquals(ReadingStatus.NOT_STARTED, store.explicitStatus(alice.userKey, "book"))
            store.save(alice.userKey, book, 3000, 1f)
            assertNull(store.explicitStatus(alice.userKey, "book"))
            assertEquals(ReadingStatus.IN_PROGRESS, store.reading(alice.userKey)["book"]!!.status)
            assertTrue(store.reading(alice.userKey)["book"]!!.later)
        }
    }
}
