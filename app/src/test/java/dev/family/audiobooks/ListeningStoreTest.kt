package dev.family.audiobooks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningStoreTest {
    private val book = AudioBook(BookSummary("book", "A book", "An author", ""),
        listOf(AudioTrack("track", "Part", "/part", 100000)), listOf(Chapter("A chapter", 0, 100000)))
    @Test fun `family profiles retain separate progress and a deliberate rewind is kept`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("listening.db")
        ListeningStore(context).use { store ->
            store.save("server/alice", book, 80000, 1.25f)
            store.save("server/bob", book, 60000, 1f)
            store.save("server/alice", book, 15000, 1.25f)
            store.place("server/alice", "book", 80000, "Before jump")
            store.place("server/bob", "book", 60000, "Bookmark", true)
            assertEquals(15000L, store.progress("server/alice", "book")!!.positionMs)
            assertEquals(60000L, store.progress("server/bob", "book")!!.positionMs)
            assertEquals(80000L, store.places("server/alice", "book").single().positionMs)
            assertTrue(store.places("server/alice", "book", true).isEmpty())
            assertTrue(store.places("server/bob", "book").isEmpty())
            assertEquals(60000L, store.places("server/bob", "book", true).single().positionMs)
        }
        ListeningStore(context).use { reopened ->
            assertEquals(15000L, reopened.progress("server/alice", "book")!!.positionMs)
            assertEquals(1.25f, reopened.progress("server/alice", "book")!!.speed)
        }
    }
    @Test fun `library browsing and offline metadata survive reopen without mixing profiles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("listening.db")
        val browse = BrowseState("murder", "Richard Osman", 17, 42, true)
        AppPreferences(context).saveBrowse("server/alice", browse)
        assertEquals(browse, AppPreferences(context).browse("server/alice"))
        assertEquals(BrowseState(), AppPreferences(context).browse("server/bob"))
        ListeningStore(context).use { store ->
            store.cacheLibrary("server/alice", listOf(book.summary))
            store.saveOffline("server/alice", book)
            store.save("server/alice", book, 5000, 1f)
            store.saveChapters("server/alice", book.copy(chapters = listOf(Chapter("New", 0, 100000)), chapterSource = "Imported"))
            assertTrue(store.offlineBooks("server/bob").isEmpty())
            assertTrue(store.cachedLibrary("server/bob").isEmpty())
            assertEquals("Imported", store.applyChapters("server/alice", book).chapterSource)
            assertEquals("Plex", store.applyChapters("server/bob", book).chapterSource)
            assertEquals(5000L, store.recent("server/alice").single().progress.positionMs)
        }
        ListeningStore(context).use { store ->
            assertEquals(book.summary, store.cachedLibrary("server/alice").single())
            assertEquals("Imported", store.offlineBooks("server/alice").single().chapterSource)
            assertEquals("/part", store.offlineAsset(offlineKey("server/alice", "book", book.tracks.single()))?.second)
            store.forgetOffline("server/alice", "book")
            assertTrue(store.offlineBooks("server/alice").isEmpty())
            assertEquals(5000L, store.progress("server/alice", "book")!!.positionMs)
        }
    }
}
