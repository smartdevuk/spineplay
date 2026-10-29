package dev.family.audiobooks

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
class PlayerRenderTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture(): AudioBook {
        val duration = 37739348L
        val sections = mutableListOf(Chapter("Intro", 0, 14988), Chapter("Epigraph", 14988, 139578),
            Chapter("Chapter 1 · Joyce", 139578, 896498), Chapter("Thursday · Chapter 2", 896498, 1476180),
            Chapter("Chapter 3", 1476180, 2131850), Chapter("Chapter 4", 2131850, 2533800))
        val tailStart = 2533800L
        repeat(75) { index -> sections += Chapter("Section ${index + 7}", tailStart + (duration - tailStart) * index / 75,
            tailStart + (duration - tailStart) * (index + 1) / 75) }
        return AudioBook(BookSummary("fixture", "The Impossible Fortune", "Richard Osman", ""),
            listOf(AudioTrack("fixture", "The Impossible Fortune", "/fixture", duration)), sections)
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun `player renders separate book and chapter progress and recovery controls`() {
        var state by mutableStateOf(PlaybackState(fixture(), 331578, playing = true, undoMs = 867578))
        var opened by mutableStateOf<String?>(null)
        compose.setContent {
            AudioBooksTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF18130F))) {
                    PlayerContent(state, {}, { state = state.copy(playing = !state.playing) },
                        { state = state.copy(positionMs = it, undoMs = state.positionMs) },
                        { state = state.copy(positionMs = state.undoMs ?: state.positionMs) }, {}, { opened = it }) { modifier ->
                        Image(painterResource(R.drawable.preview_cover), null, modifier)
                    }
                    if (opened == "chapters") ModalBottomSheet(onDismissRequest = { opened = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF251D16)) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Chapters & sections")
                            ChapterList(state.book!!, state, { state = state.copy(positionMs = it, undoMs = state.positionMs) }) { opened = null }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Whole book").assertIsDisplayed()
        compose.onNodeWithText("1% complete").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithText("Undo jump").assertIsDisplayed()
        compose.onNodeWithText("Chapters · 81").assertIsDisplayed()
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/preview/player.png")
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        compose.runOnIdle { assertEquals(361578L, state.positionMs) }
        compose.onNodeWithText("Undo jump").performClick()
        compose.runOnIdle { assertEquals(331578L, state.positionMs) }
        compose.onNodeWithContentDescription("Open chapters").performClick()
        compose.runOnIdle { assertEquals("chapters", opened) }
        compose.onNodeWithText("Thursday · Chapter 2").assertIsDisplayed()
        compose.onNodeWithText("Chapters & sections").onParent().captureRoboImage("build/outputs/preview/chapters.png")
        compose.onNodeWithText("Thursday · Chapter 2").performClick()
        compose.runOnIdle { assertEquals(896498L, state.positionMs); assertNull(opened) }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }
}
