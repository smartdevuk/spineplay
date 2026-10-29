package dev.family.audiobooks

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Public documentation uses fictional books and code-drawn covers, with no account data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicShowcaseTest {
    @get:Rule val compose = createComposeRule()
    private val first = BookSummary("lantern", "The Lantern Keepers", "Alex Rowan", "/sample", 30, "books")
    private val samples = listOf(first,
        first.copy(id = "sea", title = "Across the Quiet Sea", author = "Jamie Vale", addedAt = 20),
        first.copy(id = "map", title = "A Map of Elsewhere", addedAt = 10),
        first.copy(id = "winter", title = "The Shape of Winter", author = "Morgan Ash", addedAt = 5))
    private val duration = 31320000L
    private val book = AudioBook(first, listOf(AudioTrack("sample", "Audiobook", "/sample", duration)),
        (0 until 21).map { index -> Chapter("Chapter ${index + 1} · ${listOf("The letter", "A distant light", "The crossing", "Homeward", "The old harbour", "A promise", "The lighthouse")[index % 7]}",
            duration * index / 21, duration * (index + 1) / 21) })
    private val playback = PlaybackState(book, 10332000, playing = false, undoMs = 10302000)

    @Test fun `public gallery shows the native library player and chapters with sample content`() {
        var screen by mutableStateOf("library")
        val session = Session(Identity("sample", "Demo", "sample-not-a-credential"), "example", "Example Plex server", "https://plex.example.invalid", "sample-not-a-credential", "books", libraryNames = listOf("Audiobooks"))
        val state = LibraryState(session = session, books = samples,
            recent = listOf(ListeningBook(book, SavedProgress(playback.positionMs, 123, 1f))))
        compose.setContent { AudioBooksTheme { Box(Modifier.fillMaxSize().background(Ink)) {
            when (screen) {
                "library" -> LibraryContent(state, PlaybackState(), emptyList(), LibraryActions(open = { screen = "player" })) { summary, modifier -> SampleCover(summary, modifier) }
                "player" -> PlayerContent(playback, { screen = "library" }, {}, {}, {}, {}, { screen = it }) { modifier -> SampleCover(first, modifier) }
                "chapters" -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Chapters & sections", fontFamily = FontFamily.Serif, fontSize = 28.sp, modifier = Modifier.padding(bottom = 16.dp))
                    ChapterList(book, playback, {}) { screen = "player" }
                }
            }
        } } }
        compose.onNodeWithText("Continue listening").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/public-screenshots/library.png")
        compose.runOnIdle { screen = "player" }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithText("Whole book").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/public-screenshots/player.png")
        compose.onNodeWithContentDescription("Open chapters").performClick()
        compose.onNodeWithText("Chapters & sections").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/public-screenshots/chapters.png")
    }
}

@Composable
private fun SampleCover(summary: BookSummary, modifier: Modifier) {
    val paper = Color(0xFFFFD28C)
    val base = when (summary.id) { "sea" -> Color(0xFF214E58); "map" -> Color(0xFF60473E); "winter" -> Color(0xFF3B4A60); else -> Color(0xFF243C41) }
    Box(modifier.background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawCircle(paper.copy(alpha = .85f), w * .17f, Offset(w * .72f, h * .61f))
            val hills = Path().apply { moveTo(0f, h * .84f); lineTo(w * .3f, h * .65f); lineTo(w * .63f, h * .8f); lineTo(w, h * .7f); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(hills, Color(0xFF152A30))
            drawLine(paper, Offset(w * .28f, h * .58f), Offset(w * .28f, h * .87f), w * .025f)
            drawLine(paper, Offset(w * .2f, h * .59f), Offset(w * .36f, h * .59f), w * .03f)
            drawLine(paper.copy(alpha = .4f), Offset(w * .15f, h * .95f), Offset(w * .85f, h * .95f), 1f)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
            Text(summary.title, color = paper, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = (maxWidth.value * .125f).sp, lineHeight = (maxWidth.value * .14f).sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 10.dp))
            Text(summary.author.uppercase(), color = paper, fontSize = (maxWidth.value * .052f).sp,
                letterSpacing = .8.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }
}
