package dev.family.audiobooks

import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    private val book = AudioBook(BookSummary("b", "Book", "Author", ""),
        listOf(AudioTrack("a", "Part 1", "/a", 600000), AudioTrack("b", "Part 2", "/b", 900000)),
        listOf(Chapter("One", 0, 600000), Chapter("Two", 600000, 1500000)))
    @Test fun `seeking across files preserves the exact book position`() {
        assertEquals(1 to 12345L, book.locate(612345))
        assertEquals(612345L, book.absolute(1, 12345))
        assertEquals(1 to 0L, book.locate(600000))
    }
    @Test fun `negative and past end seeks are clamped`() {
        assertEquals(0 to 0L, book.locate(-1))
        assertEquals(1 to 900000L, book.locate(Long.MAX_VALUE))
    }
    @Test fun `chapter selection changes on the exact boundary`() {
        assertEquals("One", book.chapterAt(599999)?.title)
        assertEquals("Two", book.chapterAt(600000)?.title)
        assertEquals("Two", book.chapterAt(book.durationMs)?.title)
    }
    @Test fun `malformed chapters cannot create overlaps or unreachable audio`() {
        val normalized = normalizeChapters(listOf(Chapter("Later", 500, 1100), Chapter("Broken", 600, 200), Chapter("First", -100, 300)), 1000, "Part 1")
        assertEquals(0L, normalized.first().startMs)
        assertEquals(1000L, normalized.last().endMs)
        assertTrue(normalized.all { it.endMs > it.startMs })
        normalized.zipWithNext().forEach { (left, right) -> assertEquals(left.endMs, right.startMs) }
    }
    @Test fun `missing chapter names remain clearly labelled sections`() {
        val chapters = normalizeChapters(listOf(Chapter("", 0, 1000)), 1000, "Part 1")
        assertEquals("Section 1", chapters.first().title)
        assertTrue(chapters.first().isPart)
    }
    @Test fun `rounding at chapter boundaries does not add phantom final chapters`() {
        val chapters = normalizeChapters(listOf(Chapter("One", 0, 5000), Chapter("Two", 5050, 10000)), 10032, "Book")
        assertEquals(2, chapters.size)
        assertEquals(5050L, chapters.first().endMs)
        assertEquals(10032L, chapters.last().endMs)
    }
    @Test fun `a chapter file preserves its named chapter rather than pretending to be a part`() {
        assertFalse(normalizeChapters(emptyList(), 1000, "Chapter One: Joyce").first().isPart)
        assertTrue(normalizeChapters(emptyList(), 1000, "Part 4").first().isPart)
    }
    @Test fun `exact time input validates minutes seconds overflow and signs`() {
        assertEquals(612345000L, parsePosition("170:05:45"))
        assertEquals(192000L, parsePosition("03:12"))
        assertNull(parsePosition("1:60:00"))
        assertNull(parsePosition("03:99"))
        assertNull(parsePosition("-1:30"))
        assertNull(parsePosition("9223372036854775807:00:00"))
    }
    @Test fun `a stored book round trip retains its chapter and track mapping`() {
        val restored = AudioBook.parse(book.json())
        assertEquals(book.tracks, restored.tracks)
        assertEquals(book.chapters, restored.chapters)
        assertEquals(book.summary, restored.summary)
        assertEquals(book.locate(780123), restored.locate(780123))
    }
    @Test fun `cleartext server connections are limited to actual private IP addresses`() {
        assertTrue(safeServerAddress("http://192.168.1.10:32400"))
        assertTrue(safeServerAddress("http://172.16.0.1:32400"))
        assertTrue(safeServerAddress("https://plex.example.org:32400"))
        assertFalse(safeServerAddress("http://192.168.attacker.example"))
        assertFalse(safeServerAddress("http://10.attacker.example"))
        assertFalse(safeServerAddress("http://192.168.999.1"))
        assertFalse(safeServerAddress("http://8.8.8.8"))
        assertFalse(safeServerAddress("https://user:secret@example.org"))
        assertFalse(safeServerAddress("https://example.org?token=secret"))
    }
}
