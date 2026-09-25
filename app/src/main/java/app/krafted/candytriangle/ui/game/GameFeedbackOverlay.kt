package app.krafted.candytriangle.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.ui.board.BoardTransform
import app.krafted.candytriangle.ui.theme.CandyBlue
import app.krafted.candytriangle.ui.theme.CandyCyan
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyGreen
import app.krafted.candytriangle.ui.theme.CandyPurple
import app.krafted.candytriangle.ui.theme.CandyRose
import app.krafted.candytriangle.ui.theme.GemBlast
import app.krafted.candytriangle.ui.theme.GemExtraBall
import app.krafted.candytriangle.ui.theme.GemLine
import app.krafted.candytriangle.ui.theme.GemMagnet
import app.krafted.candytriangle.ui.theme.GemSplit
import app.krafted.candytriangle.ui.theme.GemSugarStorm
import app.krafted.candytriangle.ui.theme.GemSweet
import app.krafted.candytriangle.ui.theme.StatusSuccess
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow

private const val MaxConcurrentBursts = 12

/** Particles, short synthesized SFX, optional haptics, and a restrained full-screen flash. */
@Composable
internal fun GameFeedbackOverlay(
    feedback: Flow<GameFeedbackCue>,
    transform: BoardTransform?,
    vibrationEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val bursts = remember { mutableStateListOf<GameFeedbackCue>() }
    val haptics = LocalHapticFeedback.current
    val vibrationOn by rememberUpdatedState(vibrationEnabled)

    LaunchedEffect(feedback) {
        feedback.collect { cue ->
            if (vibrationOn && cue.type != GameFeedbackType.CANDY) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            while (bursts.size >= MaxConcurrentBursts) bursts.removeAt(0)
            bursts += cue
        }
    }

    bursts.forEach { cue ->
        key(cue.sequence) {
            FeedbackBurst(
                cue = cue,
                transform = transform,
                onFinished = { bursts.remove(cue) },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FeedbackBurst(
    cue: GameFeedbackCue,
    transform: BoardTransform?,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(cue.sequence) { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(cue.sequence) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = cue.durationMillis(), easing = FastOutSlowInEasing),
        )
        finish()
    }

    val color = cue.feedbackColor()
    Canvas(modifier.fillMaxSize()) {
        val p = progress.value.coerceIn(0f, 1f)
        val fade = (1f - p) * (1f - p)
        val origin = if (cue.x.isFinite() && cue.y.isFinite() && transform != null) {
            Offset(transform.sx(cue.x), transform.sy(cue.y))
        } else {
            Offset(size.width * 0.5f, size.height * 0.13f)
        }

        val flash = cue.flashAlpha() * fade
        if (flash > 0f) drawRect(color.copy(alpha = flash))

        val baseRadius = when (cue.type) {
            GameFeedbackType.CANDY -> 18.dp.toPx()
            GameFeedbackType.CHAIN -> 28.dp.toPx()
            GameFeedbackType.GEM -> 34.dp.toPx()
            GameFeedbackType.CUP -> 38.dp.toPx()
            GameFeedbackType.OBJECTIVE -> 42.dp.toPx()
        }
        drawCircle(
            color = color.copy(alpha = 0.88f * fade),
            radius = baseRadius * (0.55f + p * 1.9f),
            center = origin,
            style = Stroke(width = (3.5f - p * 2f).dp.toPx()),
        )

        val count = cue.particleCount()
        val travel = when (cue.type) {
            GameFeedbackType.CANDY -> 34.dp.toPx()
            GameFeedbackType.CHAIN -> 72.dp.toPx()
            GameFeedbackType.GEM -> 88.dp.toPx()
            GameFeedbackType.CUP -> 78.dp.toPx()
            GameFeedbackType.OBJECTIVE -> 96.dp.toPx()
        }
        for (index in 0 until count) {
            val fraction = index.toFloat() / count.coerceAtLeast(1)
            val angle = if (cue.type == GameFeedbackType.CUP) {
                PI.toFloat() + fraction * PI.toFloat()
            } else {
                fraction * 2f * PI.toFloat() + (cue.sequence % 11) * 0.09f
            }
            val stagger = 0.72f + (index % 4) * 0.09f
            val distance = travel * p * stagger
            val particle = Offset(
                x = origin.x + cos(angle) * distance,
                y = origin.y + sin(angle) * distance,
            )
            val radius = (if (index % 3 == 0) 4.5f else 3f).dp.toPx() * (0.45f + fade)
            drawCircle(color.copy(alpha = 0.92f * fade), radius, particle)
        }
    }
}

private fun GameFeedbackCue.durationMillis(): Int = when (type) {
    GameFeedbackType.CANDY -> 360
    GameFeedbackType.CHAIN -> 520
    GameFeedbackType.GEM -> 620
    GameFeedbackType.CUP -> 580
    GameFeedbackType.OBJECTIVE -> 680
}

private fun GameFeedbackCue.particleCount(): Int = when (type) {
    GameFeedbackType.CANDY -> 6
    GameFeedbackType.CHAIN -> (8 + amount.coerceIn(0, 6)).coerceAtMost(14)
    GameFeedbackType.GEM -> 14
    GameFeedbackType.CUP -> 12
    GameFeedbackType.OBJECTIVE -> 16
}

private fun GameFeedbackCue.flashAlpha(): Float = when (type) {
    GameFeedbackType.CANDY -> 0f
    GameFeedbackType.CHAIN -> 0.035f
    GameFeedbackType.GEM -> 0.075f
    GameFeedbackType.CUP -> 0.065f
    GameFeedbackType.OBJECTIVE -> 0.085f
}

private fun GameFeedbackCue.feedbackColor(): Color = when (type) {
    GameFeedbackType.CANDY,
    GameFeedbackType.CHAIN,
    -> when (candyColor) {
        CandyColor.GREEN -> CandyGreen
        CandyColor.PURPLE -> CandyPurple
        CandyColor.PINK -> CandyRose
        CandyColor.BLUE -> CandyBlue
        null -> CandyRose
    }

    GameFeedbackType.GEM -> when (gemType) {
        GemType.SWEET -> GemSweet
        GemType.BLAST -> GemBlast
        GemType.LINE -> GemLine
        GemType.SPLIT -> GemSplit
        GemType.EXTRA_BALL -> GemExtraBall
        GemType.MAGNET -> GemMagnet
        GemType.SUGAR_STORM -> GemSugarStorm
        null -> CandyCyan
    }

    GameFeedbackType.CUP -> CandyGold
    GameFeedbackType.OBJECTIVE -> StatusSuccess
}
