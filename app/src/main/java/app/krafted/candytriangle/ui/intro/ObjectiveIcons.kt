package app.krafted.candytriangle.ui.intro

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.ui.theme.BallDefault
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyPinkContainer
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.CupRimGold
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.World3Violet

/**
 * The icons `LevelIntroDialog` draws (D3): one per §6.3 objective, plus the sprites for its gem row
 * and crown tiers.
 *
 * A sprite wherever the asset pack has one — a candy for a colour objective (`can_1`..`can_4`), a
 * gem for a gem objective, `coin.png` for SCORE — and a small Canvas glyph where it has none: the
 * Candy Cup (§9.2's pleated wrapper and gold rim, in the board's own colours), a chain link for
 * CHAIN, and the swinging row behind the "Moving pegs" chip. A `null` colour or gem ("any") gets
 * all four candies, or four of the gems, two by two.
 *
 * Every icon is decorative (`contentDescription = null`): the text beside each one is what TalkBack
 * reads. The sprites are 400 x 400 PNGs in `drawable-nodpi`, decoded by `painterResource` for the
 * life of the dialog only — the render loop's pre-scaled `SpriteCache` is not in play here.
 *
 * Owner: D3 Agent C (intro).
 */

/** Cupcake-wrapper taper, the base narrower than the rim — `BoardRenderer`'s cup proportion. */
private const val CUP_BASE_TAPER = 0.72f

/** Fewer pleats than the board's seven: at icon size seven read as a solid block. */
private const val CUP_PLEATS = 5

/** The three pegs of the moving-row glyph, as fractions of its width. */
private val MOVING_PEG_XS = floatArrayOf(0.32f, 0.5f, 0.68f)

/** "Any gem": four of the seven, picked for four clearly different colours at 18 dp. */
private val ANY_GEM_SAMPLE = listOf(GemType.SWEET, GemType.BLAST, GemType.SPLIT, GemType.EXTRA_BALL)

/** The icon for one objective row. [modifier] sizes it; every glyph fills the box it is given. */
@Composable
internal fun ObjectiveIcon(objective: IntroObjective, modifier: Modifier = Modifier) {
    when (objective.type) {
        ObjectiveType.COLLECT_CANDY,
        ObjectiveType.CLEAR_COLOR,
        -> {
            val color = objective.color
            if (color != null) CandySprite(color, modifier) else AnyCandyIcon(modifier)
        }

        ObjectiveType.COLLECT_GEM -> {
            val gem = objective.gem
            if (gem != null) GemSprite(gem, modifier) else AnyGemIcon(modifier)
        }

        ObjectiveType.SCORE -> Sprite(R.drawable.coin, modifier)
        // Chains are colour-blind in ObjectiveTracker, so a CHAIN's colour (if authored) is ignored.
        ObjectiveType.CHAIN -> ChainGlyph(modifier)
        ObjectiveType.CUP -> CupGlyph(modifier)
    }
}

@Composable
internal fun CandySprite(color: CandyColor, modifier: Modifier = Modifier) {
    Sprite(color.spriteRes(), modifier)
}

@Composable
internal fun GemSprite(gem: GemType, modifier: Modifier = Modifier) {
    Sprite(gem.spriteRes(), modifier)
}

/** One §5.3 crown: `coin.png` once earned, `crown_empty.png` (its grey-scale) until then. */
@Composable
internal fun CrownSprite(earned: Boolean, modifier: Modifier = Modifier) {
    Sprite(if (earned) R.drawable.coin else R.drawable.crown_empty, modifier)
}

@Composable
internal fun BallSprite(modifier: Modifier = Modifier) {
    Sprite(R.drawable.ball, modifier)
}

/**
 * The candy sprite for [this] colour (§4.1).
 *
 * An explicit `when`, never `Resources.getIdentifier`: resource shrinking cannot trace a name built
 * at runtime, and every call would be a reflective lookup. `LevelIntroMapperTest` pins this table
 * to [CandyColor.spriteName], the §9.1 source of truth, through the generated `R` class.
 */
@DrawableRes
internal fun CandyColor.spriteRes(): Int = when (this) {
    CandyColor.GREEN -> R.drawable.can_1
    CandyColor.PURPLE -> R.drawable.can_2
    CandyColor.PINK -> R.drawable.can_3
    CandyColor.BLUE -> R.drawable.can_4
}

/** The gem sprite for [this] type (§4.2); see [CandyColor.spriteRes] on why it is a `when`. */
@DrawableRes
internal fun GemType.spriteRes(): Int = when (this) {
    GemType.SWEET -> R.drawable.x5
    GemType.BLAST -> R.drawable.x10_1
    GemType.LINE -> R.drawable.x10_cyan
    GemType.SPLIT -> R.drawable.x15
    GemType.EXTRA_BALL -> R.drawable.x25
    GemType.MAGNET -> R.drawable.x35
    GemType.SUGAR_STORM -> R.drawable.x80
}

@Composable
private fun Sprite(@DrawableRes id: Int, modifier: Modifier) {
    Image(painter = painterResource(id), contentDescription = null, modifier = modifier)
}

/** "Any colour": all four candies, two by two. */
@Composable
private fun AnyCandyIcon(modifier: Modifier) {
    SpriteQuad(CandyColor.entries.map { it.spriteRes() }, modifier)
}

@Composable
private fun AnyGemIcon(modifier: Modifier) {
    SpriteQuad(ANY_GEM_SAMPLE.map { it.spriteRes() }, modifier)
}

@Composable
private fun SpriteQuad(sprites: List<Int>, modifier: Modifier) {
    Column(modifier) {
        for (pair in sprites.chunked(2)) {
            Row(Modifier.weight(1f)) {
                for (id in pair) Sprite(id, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/**
 * CHAIN: two links threaded through each other on the diagonal, in the gold the HUD gives a chain
 * objective's progress bar. No sprite in the asset pack says "chain".
 */
@Composable
private fun ChainGlyph(modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val unit = size.minDimension
            val linkLength = unit * 0.56f
            val linkThickness = unit * 0.30f
            val linkSize = Size(linkLength, linkThickness)
            val corner = CornerRadius(linkThickness / 2f)
            val stroke = Stroke(width = unit * 0.11f, cap = StrokeCap.Round)
            val cx = size.width / 2f
            val top = size.height / 2f - linkThickness / 2f
            // Overlapping by 28% of a link is what reads as interlocked rather than end to end.
            val first = Offset(cx - linkLength * 0.86f, top)
            val second = Offset(cx - linkLength * 0.14f, top)
            onDrawBehind {
                rotate(degrees = -45f) {
                    drawRoundRect(CandyGold, first, linkSize, corner, style = stroke)
                    drawRoundRect(CandyGold, second, linkSize, corner, style = stroke)
                }
            }
        },
    )
}

/**
 * CUP: the Candy Cup as the board draws it (§9.2) — a pink pleated cupcake wrapper under a gold
 * rim — with a ball dropping into it, so "catch" reads without the text.
 */
@Composable
private fun CupGlyph(modifier: Modifier) {
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val rimY = h * 0.48f
            val baseY = h * 0.9f
            val halfRim = w * 0.4f
            val halfBase = halfRim * CUP_BASE_TAPER
            val wrapper = Path().apply {
                moveTo(cx - halfRim, rimY)
                lineTo(cx + halfRim, rimY)
                lineTo(cx + halfBase, baseY)
                lineTo(cx - halfBase, baseY)
                close()
            }
            val pleatWidth = w * 0.035f
            val rimWidth = w * 0.09f
            val ballRadius = w * 0.13f
            onDrawBehind {
                drawPath(wrapper, CandyPink)
                for (i in 1 until CUP_PLEATS) {
                    val t = i / CUP_PLEATS.toFloat()
                    drawLine(
                        color = CandyPinkContainer,
                        start = Offset(cx - halfRim + 2f * halfRim * t, rimY),
                        end = Offset(cx - halfBase + 2f * halfBase * t, baseY),
                        strokeWidth = pleatWidth,
                        alpha = 0.6f,
                    )
                }
                drawLine(
                    color = CupRimGold,
                    start = Offset(cx - halfRim, rimY),
                    end = Offset(cx + halfRim, rimY),
                    strokeWidth = rimWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(BallDefault, radius = ballRadius, center = Offset(cx, h * 0.2f))
            }
        },
    )
}

/**
 * The "Moving pegs" chip's glyph: three pegs in a row with a chevron at each end — a row that
 * swings side to side (§3.3). Drawn in [color], the level's world tint.
 */
@Composable
internal fun MovingPegsGlyph(color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val pegRadius = h * 0.14f
            val arm = h * 0.22f
            val stroke = h * 0.11f
            val leftTip = w * 0.08f
            val rightTip = w * 0.92f
            onDrawBehind {
                for (x in MOVING_PEG_XS) drawCircle(color, radius = pegRadius, center = Offset(w * x, cy))
                drawLine(color, Offset(leftTip, cy), Offset(leftTip + arm, cy - arm), stroke, StrokeCap.Round)
                drawLine(color, Offset(leftTip, cy), Offset(leftTip + arm, cy + arm), stroke, StrokeCap.Round)
                drawLine(color, Offset(rightTip, cy), Offset(rightTip - arm, cy - arm), stroke, StrokeCap.Round)
                drawLine(color, Offset(rightTip, cy), Offset(rightTip - arm, cy + arm), stroke, StrokeCap.Round)
            }
        },
    )
}

// ------------------------------------------------------------------ preview

/** Every objective icon at dialog size, "any" variants included, then the moving-rows glyph. */
@Preview(name = "Objective icons", widthDp = 440, showBackground = true, backgroundColor = 0xFF1A0B2E)
@Composable
private fun ObjectiveIconsPreview() {
    val objectives = listOf(
        IntroObjective(ObjectiveType.COLLECT_CANDY, CandyColor.PINK, null, target = 14, times = 1),
        IntroObjective(ObjectiveType.COLLECT_CANDY, null, null, target = 11, times = 1),
        IntroObjective(ObjectiveType.CLEAR_COLOR, CandyColor.BLUE, null, target = 0, times = 1),
        IntroObjective(ObjectiveType.COLLECT_GEM, null, GemType.LINE, target = 2, times = 1),
        IntroObjective(ObjectiveType.COLLECT_GEM, null, null, target = 3, times = 1),
        IntroObjective(ObjectiveType.SCORE, null, null, target = 8_000, times = 1),
        IntroObjective(ObjectiveType.CHAIN, null, null, target = 3, times = 2),
        IntroObjective(ObjectiveType.CUP, null, null, target = 2, times = 1),
    )
    CandyTriangleTheme {
        Row(
            modifier = Modifier
                .background(NightSurface)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            objectives.forEach { ObjectiveIcon(it, Modifier.size(36.dp)) }
            MovingPegsGlyph(World3Violet, Modifier.size(width = 32.dp, height = 16.dp))
        }
    }
}
