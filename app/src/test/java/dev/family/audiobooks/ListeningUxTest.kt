package dev.family.audiobooks

import org.junit.Assert.*
import org.junit.Test

class ListeningUxTest {
    @Test fun `Audnexus series tags yield explicit numeric order without turning every collection into a series`() {
        val tags = seriesFromPlex(listOf("Richard Osman", "Series: Thursday Murder Club"), "Thursday Murder Club, Book 2.5 - A short story", listOf("Family favourites"))
        assertEquals(SeriesRef("Thursday Murder Club", 2.5), tags[0])
        assertTrue(tags[1].collection); assertNull(tags[1].number)
        assertTrue(seriesFromPlex(listOf("Author"), "Book 1 - Something", emptyList()).isEmpty())
        assertNull(seriesFromPlex(listOf("Series: A series"), "A series, Book 1-3 - Omnibus", emptyList()).single().number)
    }
    @Test fun `series sort handles decimal volumes and puts unspecified order last`() {
        fun book(id: String, number: Double?) = BookSummary(id, id, "Author", "/cover", series = listOf(SeriesRef("Series", number)))
        assertEquals(listOf("one", "half", "two", "unknown"), orderedSeries(listOf(book("unknown", null), book("two", 2.0), book("half", 1.5), book("one", 1.0)), "Series").map { it.id })
    }
    @Test fun `sleep fade respects playback speed and returns to full volume without an active timer`() {
        assertEquals(1f, sleepVolume(null, null, 0, 1f, 0))
        assertEquals(1f, sleepVolume(30000, null, 0, 1f, 0))
        assertEquals(.5f, sleepVolume(7500, null, 0, 1f, 0))
        assertEquals(0f, sleepVolume(0, null, 0, 1f, 1))
        assertEquals(.5f, sleepVolume(null, 15000, 0, 2f, 0))
        assertEquals(1f, sleepVolume(607500, null, 0, 1f, 0))
    }
    @Test fun `progress conflicts distinguish another device from this phone's last push and deliberate rewinds`() {
        val book = AudioBook(BookSummary("b", "Book", "Author", ""), emptyList(), emptyList())
        val pending = PendingProgress(book, 10000, false, 1)
        val anchor = SyncAnchor(RemoteProgress(60000, 1000), 180000)
        assertFalse(progressConflict(pending, RemoteProgress(180000, 2000), anchor))
        assertTrue(progressConflict(pending, RemoteProgress(270000, 1000), anchor))
    }
}
