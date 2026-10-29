package dev.family.audiobooks

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryBehaviourTest {
    private val summary = BookSummary("book", "Unleashed", "Boris Johnson", "/cover", 5, "4")
    private val book = AudioBook(summary, listOf(AudioTrack("one", "Audio", "/one", 92465000)), navigationSections(92465000))
    @Test fun `new additions appear first and incomplete Plex items appear last`() {
        val recent = summary.copy(id = "recent", addedAt = 20)
        val incomplete = summary.copy(id = "bad", author = "[unknown]", addedAt = 999)
        val disc = summary.copy(id = "disc", title = "Devil May Care CD007.disc", addedAt = 998)
        assertEquals(listOf("recent", "book", "bad", "disc"), orderedBooks(listOf(incomplete, summary, recent, disc)).map { it.id })
    }
    @Test fun `long unchaptered books have bounded honest navigation sections`() {
        assertEquals(103, book.chapters.size)
        assertTrue(book.chapters.all { it.isPart && it.durationMs <= 900000 })
        assertEquals(0L, book.chapters.first().startMs)
        assertEquals(book.durationMs, book.chapters.last().endMs)
        book.chapters.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }
    @Test fun `old single-library sessions migrate and multiple library choices persist`() {
        val session = Session(Identity("alice", "Alice", "test-account"), "server", "Server", "http://127.0.0.1:32400", "test-server", "4")
        val old = session.json().apply { remove("libraryIds"); remove("libraryNames") }
        assertEquals(listOf("4"), Session.parse(old).libraryIds)
        val multiple = session.copy(libraryIds = listOf("4", "8"), libraryNames = listOf("Books", "Family stories"))
        assertEquals(multiple, Session.parse(JSONObject(multiple.json().toString())))
    }
    @Test fun `published chapters preserve the track timeline and tolerate a small credits difference`() {
        val edition = ChapterEdition("B0D7MFC16J", "uk", "Unleashed", "Boris Johnson", 92472417,
            listOf(Chapter("Opening", 0, 11493), Chapter("One", 11493, 92472417)))
        val updated = editionChapters(book, edition)
        assertEquals(book.tracks, updated.tracks)
        assertEquals(11493L, updated.chapters[1].startMs)
        assertEquals(book.durationMs, updated.chapters.last().endMs)
        assertEquals(book.locate(1234567), updated.locate(1234567))
        assertEquals(13493L, editionChapters(book, edition, 2000).chapters.last().startMs)
    }
    @Test fun `wrong editions and overlapping published chapters are rejected`() {
        val base = ChapterEdition("B0D7MFC16J", "uk", "Unleashed", "Boris Johnson", book.durationMs,
            listOf(Chapter("One", 0, 40000), Chapter("Two", 40000, book.durationMs)))
        listOf(base.copy(title = "Another book"), base.copy(author = "Another author"), base.copy(durationMs = book.durationMs + 60000),
            base.copy(chapters = listOf(Chapter("One", 0, 50000), Chapter("Two", 40000, book.durationMs)))).forEach {
            assertThrows(IllegalArgumentException::class.java) { editionChapters(book, it) }
        }
    }
    @Test fun `Audible links and edition IDs are parsed without accepting arbitrary text`() {
        assertEquals("B0D7MFC16J", audibleId("https://www.audible.co.uk/pd/Unleashed-Audiobook/B0D7MFC16J?source=test"))
        assertEquals("B0D7MFC16J", audibleId("b0d7mfc16j"))
        assertNull(audibleId("Unleashed"))
    }
    @Test fun `download cache keys isolate profiles and different media revisions`() {
        val track = book.tracks.single()
        assertNotEquals(offlineKey("server/alice", "book", track), offlineKey("server/bob", "book", track))
        assertNotEquals(offlineKey("server/alice", "book", track), offlineKey("server/alice", "book", track.copy(partKey = "/new")))
    }
}
