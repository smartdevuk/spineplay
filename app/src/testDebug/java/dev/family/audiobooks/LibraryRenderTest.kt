package dev.family.audiobooks

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `browsing a different book and explicitly resuming it use separate actions`() {
        val summary = BookSummary("next", "A different book", "Author", "/cover", libraryId = "4")
        val book = AudioBook(summary, listOf(AudioTrack("track", "Audio", "/audio", 600000)), listOf(Chapter("A saved chapter", 0, 600000)))
        val current = book.copy(summary = summary.copy(id = "current", title = "Already listening"))
        val session = Session(Identity("alice", "Alice", "test"), "server", "Server", "http://127.0.0.1", "test", "4")
        val state = LibraryState(session = session, books = listOf(summary), recent = listOf(ListeningBook(book, SavedProgress(120000, 123, 1f))))
        var opened: String? = null
        var played: String? = null
        var toggledCurrent = false
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) {
            LibraryContent(state, PlaybackState(current, 100000, playing = true), emptyList(),
                LibraryActions(open = { opened = it.id }, play = { played = it.id }, playPause = { toggledCurrent = true })) { _, modifier ->
                Image(painterResource(R.drawable.preview_cover), null, modifier)
            }
        } } }
        compose.onNode(hasText("A different book") and hasText("A saved chapter")).performClick()
        compose.runOnIdle { assertEquals("next", opened); assertNull(played); assertFalse(toggledCurrent) }
        compose.onNodeWithContentDescription("Resume A different book").performClick()
        compose.runOnIdle { assertEquals("next", played); assertFalse(toggledCurrent) }
    }
    @Test fun `resume shelf author browsing and a returning search remain usable`() {
        val summary = BookSummary("book", "The Impossible Fortune", "Richard Osman", "/fixture", 100, "4")
        val book = AudioBook(summary, listOf(AudioTrack("track", "Audio", "/fixture", 37739348)), listOf(Chapter("Chapter 1 · Joyce", 0, 37739348)))
        val session = Session(Identity("alice", "Alice", "test"), "server", "Family Plex", "http://127.0.0.1", "test", "4", libraryNames = listOf("AudioBooks"))
        var state by mutableStateOf(LibraryState(session = session, books = listOf(summary,
            summary.copy(id = "older", title = "The Thursday Murder Club", addedAt = 90),
            summary.copy(id = "unknown", title = "Unmatched CD1.disc", author = "[unknown]", addedAt = 999)),
            recent = listOf(ListeningBook(book, SavedProgress(331578, 123, 1f)))))
        var showLibrary by mutableStateOf(true)
        var beforeAuthor = BrowseState()
        var settingsOpened = false
        val actions = LibraryActions(search = { state = state.copy(browse = state.browse.copy(query = it, index = 0, offset = 0)) },
            scroll = { index, offset -> state = state.copy(browse = state.browse.copy(index = index, offset = offset)) },
            author = { beforeAuthor = state.browse; state = state.copy(browse = BrowseState(author = it)) },
            clearAuthor = { state = state.copy(browse = beforeAuthor) }, settings = { settingsOpened = true }, open = { showLibrary = false })
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) {
            if (showLibrary) LibraryContent(state, PlaybackState(), emptyList(), actions) { _, modifier -> Image(painterResource(R.drawable.preview_cover), null, modifier) }
            else Button(onClick = { showLibrary = true }) { Text("Back to library") }
        } } }
        compose.onNodeWithText("Continue listening").assertIsDisplayed()
        compose.onNodeWithText("Recently added").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/preview/library.png")
        compose.onNode(hasSetTextAction()).performTextInput("Impossible")
        compose.onNodeWithText("Continue listening").assertDoesNotExist()
        compose.onNodeWithText("The Impossible Fortune").performClick()
        compose.onNodeWithText("Back to library").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Impossible")
        compose.onNodeWithText("Richard Osman").performClick()
        compose.onNodeWithText("By Richard Osman").assertIsDisplayed()
        compose.onNodeWithText("The Thursday Murder Club").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/preview/author.png")
        compose.onNodeWithText("Back to all books").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Impossible")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
    }
    @Test fun `status filtering and listen-later selection do not mix books`() {
        val session = Session(Identity("alice", "Alice", "test"), "server", "Server", "http://127.0.0.1", "test", "4")
        val books = listOf(BookSummary("done", "Finished book", "Author", "/cover"), BookSummary("next", "Next book", "Author", "/cover"))
        var state by mutableStateOf(LibraryState(session = session, books = books, reading = mapOf("done" to ReadingState(ReadingStatus.FINISHED), "next" to ReadingState(later = true))))
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) {
            LibraryContent(state, PlaybackState(), emptyList(), LibraryActions(statusFilter = { state = state.copy(browse = state.browse.copy(status = it)) })) { _, modifier -> Image(painterResource(R.drawable.preview_cover), null, modifier) }
        } } }
        compose.onNodeWithTag("status-filter").performClick()
        compose.onNodeWithTag("status-option-FINISHED").performClick()
        compose.onNodeWithText("Finished book").assertIsDisplayed()
        compose.onNodeWithText("Next book").assertDoesNotExist()
        compose.onNodeWithTag("status-filter").performClick()
        compose.onNodeWithTag("status-option-later").performClick()
        compose.onNodeWithText("Next book").assertIsDisplayed()
        compose.onNodeWithText("Finished book").assertDoesNotExist()
    }
    @Test fun `a nonzero library scroll survives loading cached data and leaving the screen`() {
        val session = Session(Identity("alice", "Alice", "test"), "server", "Server", "http://127.0.0.1", "test", "4")
        var state by mutableStateOf(LibraryState(session = session, loading = true, browse = BrowseState(index = 22, offset = 12)))
        var visible by mutableStateOf(true)
        val actions = LibraryActions(scroll = { index, offset -> state = state.copy(browse = state.browse.copy(index = index, offset = offset)) })
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) {
            if (visible) LibraryContent(state, PlaybackState(), emptyList(), actions) { _, modifier -> Image(painterResource(R.drawable.preview_cover), null, modifier) }
            else Button(onClick = { visible = true }) { Text("Back to library") }
        } } }
        compose.onNodeWithText("Loading your saved library…").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false, books = (0..59).map { BookSummary("$it", "Book $it", "Author", "/cover", addedAt = (60 - it).toLong()) }) }
        compose.onNodeWithTag("library-books").performScrollToIndex(22)
        var previousIndex = 0; var previousOffset = 0
        compose.runOnIdle { previousIndex = state.browse.index; previousOffset = state.browse.offset; assertTrue(previousIndex >= 20); visible = false }
        compose.onNodeWithText("Back to library").performClick()
        compose.runOnIdle { assertEquals(previousIndex, state.browse.index); assertEquals(previousOffset, state.browse.offset) }
    }
}
