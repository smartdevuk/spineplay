package dev.family.audiobooks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

internal const val INTRO_MAX_MS = 900L
@Composable
internal fun SpineplayMark(modifier: Modifier = Modifier, reveal: Float = 1f) {
    Canvas(modifier) {
        val unit = size.minDimension / 100f
        val heights = listOf(48f, 66f, 56f)
        heights.forEachIndexed { i, height ->
            val phase = ((reveal - i * .09f) / .65f).coerceIn(0f, 1f)
            val x = (16 + i * 15) * unit
            drawRoundRect(Amber.copy(alpha = phase), Offset(x, (82 - height * phase) * unit), Size(8 * unit, height * phase * unit), CornerRadius(3 * unit))
        }
        val alpha = ((reveal - .22f) / .55f).coerceIn(0f, 1f)
        val play = Path().apply { moveTo(65 * unit, 29 * unit); lineTo(65 * unit, 75 * unit); lineTo(91 * unit, 52 * unit); close() }
        drawPath(play, Amber.copy(alpha = alpha))
    }
}
@Composable
internal fun BrandFrame(progress: Float = 1f, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 32.dp)) {
            SpineplayMark(Modifier.size(94.dp), progress)
            Spacer(Modifier.height(20.dp))
            val textAlpha = ((progress - .12f) / .65f).coerceIn(0f, 1f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
                alpha = textAlpha; translationY = (1 - textAlpha) * 9.dp.toPx()
            }) {
                Text("Spineplay", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 54.sp, color = Ivory)
                Text("Audiobooks for Plex", fontSize = 14.sp, letterSpacing = .7.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}
@Composable
internal fun BrandedLaunch(coldStart: Boolean, ready: Boolean, animationsEnabled: Boolean = true, content: @Composable () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(coldStart && animationsEnabled) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (visible) { progress.animateTo(1f, tween(INTRO_MAX_MS.toInt(), easing = FastOutSlowInEasing)); visible = false }
    }
    LaunchedEffect(ready) { if (ready && visible) { snapshotFlow { progress.value }.first { it >= .35f }; visible = false } }
    if (visible) BrandFrame(progress.value, Modifier.clickable { visible = false }.semantics { contentDescription = "Spineplay. Audiobooks for Plex. Tap to continue." })
    else content()
}
