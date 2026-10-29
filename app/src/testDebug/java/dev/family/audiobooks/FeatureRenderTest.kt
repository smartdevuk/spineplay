package dev.family.audiobooks

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
class FeatureRenderTest {
    @get:Rule val compose = createComposeRule()
    private val book = AudioBook(BookSummary("book", "The Impossible Fortune", "Richard Osman", "/fixture", series = listOf(SeriesRef("Thursday Murder Club", 5.0))),
        listOf(AudioTrack("audio", "Book", "/fixture", 37739348, 200000000)), listOf(Chapter("Chapter 1 · Joyce", 0, 37739348)),
        details = BookDetails("An illustrative description for the native screen test. The production app uses the description supplied by your Plex library.", listOf("Sample narrator"), "Sample publisher", "2025"))
    @Test fun `branding is brief and does not repeat when content updates`() {
        compose.mainClock.autoAdvance = false
        var ready by mutableStateOf(false)
        compose.setContent { AudioBooksTheme { BrandedLaunch(true, ready) { Text("Library ready") } } }
        compose.mainClock.advanceTimeBy(450)
        compose.onNodeWithText("Spineplay").assertIsDisplayed()
        compose.onNodeWithText("Audiobooks for Plex").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/preview/brand.png")
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Library ready").assertIsDisplayed()
        compose.runOnIdle { ready = true }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Spineplay").assertDoesNotExist()
    }
    @Test fun `branding can be skipped immediately and respects disabled animations`() {
        var enabled by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AudioBooksTheme { BrandedLaunch(true, false, enabled) { Text("Library ready") } } }
        compose.onNodeWithContentDescription("Spineplay. Audiobooks for Plex. Tap to continue.").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Library ready").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("Spineplay").assertDoesNotExist()
    }
    @Test fun `warm launches show content without a brand interstitial`() {
        compose.setContent { AudioBooksTheme { BrandedLaunch(false, false) { Text("Library ready") } } }
        compose.onNodeWithText("Library ready").assertIsDisplayed(); compose.onNodeWithText("Spineplay").assertDoesNotExist()
    }
    @Test fun `disabled system animation setting skips the launch interstitial`() {
        compose.setContent { AudioBooksTheme { BrandedLaunch(true, false, false) { Text("Library ready") } } }
        compose.onNodeWithText("Library ready").assertIsDisplayed(); compose.onNodeWithText("Spineplay").assertDoesNotExist()
    }
    @Test fun `ready content dismisses branding before the full animation`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AudioBooksTheme { BrandedLaunch(true, true) { Text("Library ready") } } }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Library ready").assertIsDisplayed()
    }
    @Test fun `bedtime panel recalls the preferred timer and exposes exact recovery`() {
        var chosen = 0; var recovered = false
        val state = PlaybackState(book, 331578, sleepSession = SleepSession(80000, 1728000000000))
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) { TimerContent(book, state, 30, { chosen = it }, {}, { recovered = true }) } } }
        compose.onNodeWithText("Start 30-minute timer").assertIsDisplayed()
        compose.onNodeWithText("Return to 01:20").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/preview/bedtime.png")
        compose.onNodeWithText("Start 30-minute timer").performClick()
        compose.runOnIdle { assertEquals(30, chosen) }
        compose.onNodeWithText("Return to 01:20").performClick()
        compose.runOnIdle { assertTrue(recovered) }
    }
    @Test fun `book details expose status series narrator and listen-later without starting playback`() {
        var reading by mutableStateOf(ReadingState(ReadingStatus.IN_PROGRESS))
        var series = ""
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) {
            BookDetailContent(book, reading, null, 331578, false, null, DetailActions(series = { series = it }, status = { reading = reading.copy(status = it) }, later = { reading = reading.copy(later = it) })) {
                Image(painterResource(R.drawable.preview_cover), null, it)
            }
        } } }
        compose.onNodeWithText("Resume at 05:31").assertIsDisplayed()
        compose.onNodeWithText("Sample narrator").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/preview/details.png")
        compose.onNodeWithText("Listen later").performClick()
        compose.onNodeWithText("Saved for later").assertIsDisplayed()
        compose.onNodeWithText("Thursday Murder Club · Book 5").performClick()
        compose.runOnIdle { assertEquals("Thursday Murder Club", series) }
        compose.onNodeWithText("In progress").performClick()
        compose.onNodeWithText("Mark finished").performClick()
        compose.onNodeWithText("Listen again").assertIsDisplayed()
    }
}
