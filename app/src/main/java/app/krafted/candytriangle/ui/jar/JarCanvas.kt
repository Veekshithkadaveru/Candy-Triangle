package app.krafted.candytriangle.ui.jar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyGreen
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme

// Glass, not candy. Local `private val`s rather than additions to ui/theme/Color.kt, which is a
// shared file in this phase — these tints are only ever the apothecary jar's.
private val JarGlass = Color(0x2EBFE6FF)
private val JarGlassEdge = Color(0x66CFE9FF)
private val JarHighlight = Color(0x59FFFFFF)
private val JarShadow = Color(0x33000000)
private val JarTick = Color(0x73FFFFFF)
private val JarTickEarned = Color(0xCCFFFFFF)

/**
 * The §9.2 "glass apothecary jar contour rendered via Canvas paths; clipped gradient fill".
 *
 * There is no jar sprite in `plinko_dev`, so the whole vessel is drawn: a gold-collared lid, a
 * neck, curved shoulders and a rounded body, with the candy level painted as a vertical gradient
 * clipped to the body path. [fillFraction] animates with `animateFloatAsState` so banking candies
 * after a level visibly raises the level rather than snapping.
 *
 * Stateless and deliberately dumb — every number it draws (the fill, the tick positions) is decided
 * in [JarUiStateMapper], which is where the JVM-only suite can reach it. Nothing here is computed
 * beyond laying the shape out inside the given size.
 *
 * @param fillFraction 0f..1f; values outside are clamped.
 * @param tickFractions threshold marks from [JarUiState.tickFractions] — **never hardcoded here**.
 * @param candyColor the jar's branded candy tint (`CandyGreen`/`CandyPurple`/`CandyRose`/`CandyBlue`).
 */
@Composable
fun JarCanvas(
    fillFraction: Float,
    tickFractions: List<Float>,
    candyColor: Color,
    modifier: Modifier = Modifier,
) {
    val animatedFill by animateFloatAsState(
        targetValue = fillFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = FILL_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
        label = "jarFill",
    )
    Canvas(modifier = modifier) {
        drawJar(animatedFill, tickFractions, candyColor)
    }
}

/**
 * The whole vessel, in fractions of [DrawScope.size] so one path serves every jar size.
 *
 * Order: body glass, clipped candy fill, tick marks, body outline, highlight streak, then the lid
 * on top — the lid is last so its gold collar overlaps the neck rather than being cut by it.
 */
private fun DrawScope.drawJar(fill: Float, tickFractions: List<Float>, candyColor: Color) {
    val w = size.width
    val h = size.height
    val body = jarBodyPath(w, h)

    val interiorTop = h * BODY_TOP
    val interiorBottom = h * BODY_BOTTOM
    val fillTop = interiorBottom - (interiorBottom - interiorTop) * fill.coerceIn(0f, 1f)

    // Glass body: a faint cold wash so an empty jar still reads as a jar.
    drawPath(body, color = JarGlass)

    clipPath(body) {
        if (fill > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        candyColor.copy(alpha = 0.95f),
                        candyColor.copy(alpha = 0.72f),
                        candyColor.copy(alpha = 0.95f),
                    ),
                    startY = fillTop,
                    endY = interiorBottom,
                ),
                topLeft = Offset(0f, fillTop),
                size = Size(w, interiorBottom - fillTop),
            )
            // A lighter band right at the surface, so the candy level has a readable meniscus.
            drawRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, fillTop),
                size = Size(w, h * MENISCUS_HEIGHT),
            )
        }
        // Tick marks sit inside the glass and come straight from the mapper.
        for (tick in tickFractions) {
            val y = interiorBottom - (interiorBottom - interiorTop) * tick.coerceIn(0f, 1f)
            drawLine(
                color = if (tick <= fill) JarTickEarned else JarTick,
                start = Offset(w * TICK_INSET, y),
                end = Offset(w * (1f - TICK_INSET), y),
                strokeWidth = h * TICK_STROKE,
            )
        }
        // Curved shadow along the inner left wall — cheap glass volume.
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(JarShadow, Color.Transparent),
                startX = 0f,
                endX = w * 0.35f,
            ),
            topLeft = Offset(0f, interiorTop),
            size = Size(w * 0.35f, interiorBottom - interiorTop),
        )
    }

    drawPath(body, color = JarGlassEdge, style = Stroke(width = w * BODY_STROKE))

    // Specular streak down the left shoulder.
    drawPath(highlightPath(w, h), color = JarHighlight, style = Stroke(width = w * HIGHLIGHT_STROKE))

    // Lid: gold collar over a rounded cap.
    val lidWidth = w * (LID_RIGHT - LID_LEFT)
    drawRoundRect(
        color = CandyGold,
        topLeft = Offset(w * LID_LEFT, h * LID_TOP),
        size = Size(lidWidth, h * (LID_BOTTOM - LID_TOP)),
        cornerRadius = CornerRadius(h * LID_CORNER, h * LID_CORNER),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(w * LID_LEFT, h * LID_TOP),
        size = Size(lidWidth, h * (LID_BOTTOM - LID_TOP) * 0.45f),
        cornerRadius = CornerRadius(h * LID_CORNER, h * LID_CORNER),
    )
}

/** Neck -> shoulder -> body -> base -> shoulder, closed. Cubics, so the shoulders actually curve. */
private fun jarBodyPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.30f, h * NECK_BOTTOM)
    cubicTo(w * 0.30f, h * 0.24f, w * 0.08f, h * 0.24f, w * 0.07f, h * 0.38f)
    lineTo(w * 0.07f, h * 0.86f)
    cubicTo(w * 0.07f, h * 0.95f, w * 0.14f, h * BODY_BOTTOM, w * 0.22f, h * BODY_BOTTOM)
    lineTo(w * 0.78f, h * BODY_BOTTOM)
    cubicTo(w * 0.86f, h * BODY_BOTTOM, w * 0.93f, h * 0.95f, w * 0.93f, h * 0.86f)
    lineTo(w * 0.93f, h * 0.38f)
    cubicTo(w * 0.92f, h * 0.24f, w * 0.70f, h * 0.24f, w * 0.70f, h * NECK_BOTTOM)
    close()
}

/** A short arc hugging the inside of the left shoulder — the glass highlight. */
private fun highlightPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.20f, h * 0.34f)
    cubicTo(w * 0.15f, h * 0.44f, w * 0.15f, h * 0.60f, w * 0.18f, h * 0.72f)
}

// Vertical landmarks as fractions of the canvas height. The body's interior spans
// BODY_TOP..BODY_BOTTOM; the fill and every tick are placed inside that band.
private const val LID_TOP = 0.01f
private const val LID_BOTTOM = 0.10f
private const val LID_LEFT = 0.22f
private const val LID_RIGHT = 0.78f
private const val LID_CORNER = 0.03f
private const val NECK_BOTTOM = 0.14f
private const val BODY_TOP = 0.26f
private const val BODY_BOTTOM = 0.97f
private const val BODY_STROKE = 0.018f
private const val HIGHLIGHT_STROKE = 0.03f
private const val MENISCUS_HEIGHT = 0.012f
private const val TICK_INSET = 0.10f
private const val TICK_STROKE = 0.006f
private const val FILL_ANIMATION_MILLIS = 650

// ---------------------------------------------------------------------------
// Design documentation. Previews are not tests — §11 is JVM-only and no composable can run in
// this module's unit tests — but they are the only way to eyeball the jar without an emulator.
// ---------------------------------------------------------------------------

@Preview(name = "Jar — empty", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun JarCanvasEmptyPreview() {
    CandyTriangleTheme {
        JarCanvas(
            fillFraction = 0f,
            tickFractions = listOf(0.2f, 0.6f, 1f),
            candyColor = CandyGreen,
            modifier = Modifier.size(110.dp, 150.dp),
        )
    }
}

@Preview(name = "Jar — tier 2", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun JarCanvasPartialPreview() {
    CandyTriangleTheme {
        JarCanvas(
            fillFraction = 0.65f,
            tickFractions = listOf(0.2f, 0.6f, 1f),
            candyColor = CandyGreen,
            modifier = Modifier.size(110.dp, 150.dp),
        )
    }
}

@Preview(name = "Jar — full", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun JarCanvasFullPreview() {
    CandyTriangleTheme {
        JarCanvas(
            fillFraction = 1f,
            tickFractions = listOf(0.2f, 0.6f, 1f),
            candyColor = CandyGreen,
            modifier = Modifier.size(110.dp, 150.dp),
        )
    }
}
