package app.krafted.candytriangle.ui.map

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.ui.theme.CandyBlue
import app.krafted.candytriangle.ui.theme.CandyGreen
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyPurple
import app.krafted.candytriangle.ui.theme.CandyRose
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.CrownEmpty
import app.krafted.candytriangle.ui.theme.CrownGold
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingFaint
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.NightSurfaceHigh
import app.krafted.candytriangle.ui.theme.NightVoid
import app.krafted.candytriangle.ui.theme.StatusSuccess
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The map's furniture (D3): level nodes, their crown rows, the Sweet Rooms, the §6.2 gate badges,
 * the pulsing current-level marker, the dotted path and the world banners.
 *
 * Deliberately thin, like `GameHud` and `JarCanvas`. Every box these are drawn into comes out of
 * `MapGeometry`, every lit stretch and dot out of `MapPathMath`, every lock decision out of the
 * frozen `LevelMapUiState` — so nothing here decides a number, and what is left is paint.
 *
 * Text is sized to the box it sits in (`MapGeometry.textPx`), not to the type scale: map labels
 * are part of the illustration and live in bands `MapLayout` reserves, so a large font scale must
 * not push them into a neighbour's. Everything they say is also in the owning element's
 * `contentDescription`, which is what an accessibility service reads.
 *
 * Owner: D3 Agent B (map UI).
 */

// Map-only tints. Local `private val`s rather than additions to ui/theme/Color.kt, which is shared
// — the same rule `GameHud` and `JarCanvas` follow.
private val LockedFill = Color(0xF0302541)
private val LockedRing = Color(0x997A6A93)
private val LabelShadow = Color(0x99000000)
private val BandFill = Color(0xB30B0518)
private val KeyholeTint = Color(0x73000000)

private const val PULSE_MILLIS = 900

/** The current node breathes between `1 - this` and full size — never larger than its square. */
private const val NODE_PULSE_DEPTH = 0.07f

/** How far a playable node's glow reaches, in disc radii: past the rim, faint, beneath neighbours. */
private const val GLOW_REACH = 1.45f
private const val GLOW_ALPHA = 0.55f

/** Ring widths, as fractions of the disc radius. */
private const val NODE_RING = 0.13f
private const val SWEET_ROOM_RING = 0.17f
private const val GATE_RING = 0.11f

/** A locked disc's padlock and faint number, as fractions of its diameter. */
private const val LOCK_ICON_SCALE = 0.36f
private const val LOCKED_LABEL_SCALE = 0.22f

/** A gate's padlock, as a fraction of the badge's radius on each side of its centre. */
private const val GATE_LOCK_SCALE = 0.56f

/** The marker art's share of its square; the rest is the room it bobs in. */
private const val MARKER_ART_SCALE = 0.84f

private const val DIMMED_CROWN_ALPHA = 0.45f
private const val DIM_PATH_ALPHA = 0.30f
private const val PATH_HALO_ALPHA = 0.30f
private const val PATH_HALO_SCALE = 2.2f

/** The world banner's text and icons, as fractions of the banner height. */
private const val BANNER_LABEL_SCALE = 0.40f
private const val BANNER_ICON_SCALE = 0.42f

/** A gate requirement line's met/unmet mark and coin, as fractions of the line height. */
private const val MARK_SCALE = 0.62f

/** `autoSize` may shrink a label to this fraction of its box-derived size, never further. */
private const val MIN_TEXT_SCALE = 0.5f

// ------------------------------------------------------------------------------------ level nodes

/**
 * One of the 40 main levels (§6.1), glowing in its world's §6.1 peg tint:
 *
 * - [NodeStatus.AVAILABLE]: a dark core in a tint ring — open, not cleared yet.
 * - [NodeStatus.CLEARED]: filled with the tint.
 * - [NodeStatus.LOCKED]: a dim disc, a padlock over a faint number, no glow.
 *
 * Every status is tappable; what a tap *does* is [onClick]'s business (the intro, or a hint that
 * explains the lock). The current node pulses by shrinking, never growing, so it stays inside the
 * square `MapLayout` reserves for it; the pulse is a layer property, so it redraws without
 * recomposing.
 *
 * @param diameterPx the node's laid-out size — the text is sized from it.
 */
@Composable
internal fun LevelNode(
    node: LevelNodeUiState,
    tint: Color,
    diameterPx: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = levelNodeDescription(node)
    val pulse = if (node.isCurrent) rememberPulse("currentNode") else null
    Box(
        modifier = modifier
            // Only the current node gets a layer; the other 39 draw straight into their slice.
            .then(
                if (pulse == null) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        val scale = 1f - NODE_PULSE_DEPTH * (1f - pulse.value)
                        scaleX = scale
                        scaleY = scale
                    }
                },
            )
            .drawWithCache {
                val mid = size.center
                val radius = size.minDimension / 2f
                val ring = radius * NODE_RING
                val glow = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = GLOW_ALPHA), Color.Transparent),
                    center = mid,
                    radius = radius * GLOW_REACH,
                )
                val fill = when (node.status) {
                    NodeStatus.CLEARED -> Brush.radialGradient(
                        colors = listOf(lerp(tint, Color.White, 0.18f), lerp(tint, Color.Black, 0.45f)),
                        center = mid + Offset(-radius * 0.3f, -radius * 0.3f),
                        radius = radius * 1.5f,
                    )
                    NodeStatus.AVAILABLE -> Brush.radialGradient(
                        colors = listOf(NightSurfaceHigh, NightVoid),
                        center = mid,
                        radius = radius,
                    )
                    NodeStatus.LOCKED -> SolidColor(LockedFill)
                }
                val ringColor = when (node.status) {
                    NodeStatus.CLEARED -> IcingWhite.copy(alpha = 0.85f)
                    NodeStatus.AVAILABLE -> tint
                    NodeStatus.LOCKED -> LockedRing
                }
                onDrawBehind {
                    if (node.isPlayable) {
                        // The pulse reads here, in the draw phase, so it only ever redraws.
                        val breath = pulse?.let { 0.7f + 0.3f * it.value } ?: 1f
                        drawCircle(glow, radius = radius * GLOW_REACH, alpha = breath)
                    }
                    drawCircle(fill, radius = radius)
                    drawCircle(ringColor, radius = radius - ring / 2f, style = Stroke(ring))
                }
            }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            // After `clickable`, so the click action survives; the number below is folded into this.
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (node.status == NodeStatus.LOCKED) {
            LockedLabel(
                text = node.levelId.toString(),
                diameterPx = diameterPx,
                iconColor = IcingDim,
                textColor = IcingFaint,
            )
        } else {
            MapText(
                text = node.levelId.toString(),
                sizePx = MapGeometry.textPx(diameterPx, MapGeometry.NODE_LABEL_SCALE),
                color = IcingWhite,
            )
        }
    }
}

/**
 * The three §5.3 crowns under a node or a Sweet Room: `coin` for each earned, `crown_empty` for the
 * rest, in the slots `MapGeometry.crownSlots` hands out.
 *
 * Drawn rather than composed as three `Image`s — one layout node per row instead of three — and
 * from the shared [MapSprites], never a decode per crown. Until the sprites land, gold and grey
 * discs stand in.
 */
@Composable
internal fun CrownRow(
    crowns: Int,
    sprites: MapSprites,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    Spacer(
        modifier = modifier.drawWithCache {
            val slots = MapGeometry.crownSlots(
                PxRect(0, 0, size.width.roundToInt(), size.height.roundToInt()),
            )
            val alpha = if (dimmed) DIMMED_CROWN_ALPHA else 1f
            onDrawBehind {
                slots.forEachIndexed { index, slot ->
                    drawCrown(earned = index < crowns, slot = slot, sprites = sprites, alpha = alpha)
                }
            }
        },
    )
}

/** The current-level marker (§9.1): `triangle.png`, bobbing within its `MARKER_SIZE` square. */
@Composable
internal fun CurrentLevelMarker(
    sprite: ImageBitmap?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberPulse("currentMarker")
    Spacer(
        modifier = modifier.drawWithCache {
            val side = size.minDimension
            val art = side * MARKER_ART_SCALE
            // Fit the art's own aspect (400 x 374) inside an art x art square.
            val artWidth: Float
            val artHeight: Float
            if (sprite != null && sprite.width > 0 && sprite.height > 0) {
                val aspect = sprite.width.toFloat() / sprite.height
                artWidth = if (aspect >= 1f) art else art * aspect
                artHeight = if (aspect >= 1f) art / aspect else art
            } else {
                artWidth = art
                artHeight = art * 0.9f
            }
            val left = (size.width - artWidth) / 2f
            val travel = side - art
            val fallback = Path().apply {
                moveTo(left + artWidth / 2f, 0f)
                lineTo(left + artWidth, artHeight)
                lineTo(left, artHeight)
                close()
            }
            onDrawBehind {
                // 0 -> 1 dips the marker toward its node and back, never leaving the square.
                val top = travel * pulse.value + (art - artHeight)
                if (sprite != null) {
                    drawImage(
                        image = sprite,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(artWidth.roundToInt(), artHeight.roundToInt()),
                    )
                } else {
                    translate(top = top) { drawPath(fallback, color = tint) }
                }
            }
        },
    )
}

// ------------------------------------------------------------------------------------ Sweet Rooms

/**
 * A bonus Sweet Room (§7): a candy bubble with its "B1".."B4" code, ringed in its jar's colour.
 *
 * Its lock is its own, never its slice's: a jar can open a room on a world whose gate is still
 * shut, so it is drawn above the slice's locked-world dim and dimmed only when *it* is locked.
 */
@Composable
internal fun SweetRoomNode(
    room: SweetRoomNodeUiState,
    diameterPx: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val jar = room.jarColor.tint()
    val description = sweetRoomDescription(room)
    Box(
        modifier = modifier
            .drawWithCache {
                val mid = size.center
                val radius = size.minDimension / 2f
                val ring = radius * SWEET_ROOM_RING
                val glow = Brush.radialGradient(
                    colors = listOf(jar.copy(alpha = GLOW_ALPHA), Color.Transparent),
                    center = mid,
                    radius = radius * GLOW_REACH,
                )
                val fill = if (room.isPlayable) {
                    Brush.radialGradient(
                        colors = listOf(lerp(jar, Color.White, 0.4f), jar, lerp(jar, Color.Black, 0.5f)),
                        center = mid + Offset(-radius * 0.35f, -radius * 0.35f),
                        radius = radius * 1.7f,
                    )
                } else {
                    SolidColor(LockedFill)
                }
                val shine = Size(radius * 0.62f, radius * 0.34f)
                val shineAt = Offset(mid.x - radius * 0.58f, mid.y - radius * 0.66f)
                onDrawBehind {
                    if (room.isPlayable) drawCircle(glow, radius = radius * GLOW_REACH)
                    drawCircle(fill, radius = radius)
                    // The bubble's highlight: what makes it read as a sweet rather than a node.
                    if (room.isPlayable) drawOval(Color.White.copy(alpha = 0.38f), shineAt, shine)
                    drawCircle(
                        color = if (room.isPlayable) jar else jar.copy(alpha = 0.5f),
                        radius = radius - ring / 2f,
                        style = Stroke(ring),
                    )
                }
            }
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (room.isPlayable) {
            MapText(
                text = room.code,
                sizePx = MapGeometry.textPx(diameterPx, MapGeometry.SWEET_ROOM_LABEL_SCALE),
                color = IcingWhite,
            )
        } else {
            LockedLabel(text = room.code, diameterPx = diameterPx, iconColor = jar, textColor = IcingDim)
        }
    }
}

/** A locked Sweet Room's jar progress toward tier 3, e.g. "143 / 200", in its jar's colour. */
@Composable
internal fun JarProgressLabel(
    room: SweetRoomNodeUiState,
    heightPx: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        MapText(
            text = stringResource(R.string.map_jar_progress, room.jarCount, room.jarThreshold),
            sizePx = MapGeometry.textPx(heightPx, MapGeometry.BAND_LABEL_SCALE),
            color = room.jarColor.tint(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------------------------ world gates

/**
 * A §6.2 world gate. Shut: a full-size disc with a padlock, ringed in the world's tint. Open: the
 * same disc at `MapGeometry.OPEN_GATE_SCALE` with the shackle raised — a gate the player has
 * already passed should not compete with the nodes.
 *
 * A shut gate is tappable (it explains itself through [onClick]); an open one is not.
 */
@Composable
internal fun GateBadge(
    gate: GateUiState,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = gateDescription(gate)
    Box(
        modifier = modifier
            .drawWithCache {
                val mid = size.center
                val scale = if (gate.open) MapGeometry.OPEN_GATE_SCALE else 1f
                val radius = size.minDimension / 2f * scale
                val ring = radius * GATE_RING
                val glow = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = GLOW_ALPHA), Color.Transparent),
                    center = mid,
                    radius = radius * GLOW_REACH,
                )
                // `Rect(centre, r)` takes a half-size: a padlock a little over half the disc.
                val lock = Rect(mid, radius * GATE_LOCK_SCALE)
                onDrawBehind {
                    if (!gate.open) drawCircle(glow, radius = radius * GLOW_REACH)
                    drawCircle(if (gate.open) NightSurface else NightSurfaceHigh, radius = radius)
                    drawCircle(tint, radius = radius - ring / 2f, style = Stroke(ring))
                    drawPadlock(color = if (gate.open) tint else IcingWhite, area = lock, open = gate.open)
                }
            }
            .then(
                if (gate.open) {
                    Modifier
                } else {
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClick)
                },
            )
            .clearAndSetSemantics { contentDescription = description },
    )
}

/**
 * The shut gate's two §6.2 halves, one line each, in the band `MapLayout` reserves below the badge:
 * "Clear Level 10" and the player's crowns against the requirement ("12 / 15"). A met half is
 * ticked in [StatusSuccess]; an unmet one keeps an empty ring and stays white, since it is the call
 * to action.
 *
 * Silent to accessibility services: the badge's own description already says all of it.
 */
@Composable
internal fun GateRequirements(
    gate: GateUiState,
    sprites: MapSprites,
    heightPx: Int,
    modifier: Modifier = Modifier,
) {
    val lineHeightPx = heightPx / 2
    val density = LocalDensity.current
    val markSize = with(density) { (lineHeightPx * MARK_SCALE).toDp() }
    val textPx = MapGeometry.textPx(lineHeightPx, MapGeometry.BAND_LABEL_SCALE)
    val crownsMet = gate.crownsHave >= gate.crownsRequired
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 30))
            .background(BandFill)
            .clearAndSetSemantics { },
    ) {
        RequirementLine(met = gate.levelCleared, markSize = markSize, modifier = Modifier.weight(1f)) {
            MapText(
                text = stringResource(R.string.map_gate_clear_level, gate.requiresLevelCleared),
                sizePx = textPx,
                color = if (gate.levelCleared) StatusSuccess else IcingWhite,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        RequirementLine(met = crownsMet, markSize = markSize, modifier = Modifier.weight(1f)) {
            CoinIcon(coin = sprites.coin, modifier = Modifier.size(markSize))
            Spacer(Modifier.width(markSize / 4))
            MapText(
                text = stringResource(R.string.map_gate_crowns, gate.crownsHave, gate.crownsRequired),
                sizePx = textPx,
                color = if (crownsMet) StatusSuccess else IcingWhite,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun RequirementLine(
    met: Boolean,
    markSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Spacer(
            modifier = Modifier
                .size(markSize)
                .drawBehind { drawRequirementMark(met) },
        )
        Spacer(Modifier.width(markSize / 3))
        content()
    }
}

// ------------------------------------------------------------------------------------ the path

/**
 * The dotted path through a slice, in the world's §6.1 peg tint — the map's "world peg tinting".
 *
 * Stretches that lead into a playable node are lit (a soft halo, the tint, a bright core); the
 * rest are faint, so the lit path stops at the player's frontier. Which stretch is which, and where
 * every dot falls, is `MapPathMath`'s decision; the dots are computed once per slice size and
 * progress, never per frame.
 */
@Composable
internal fun MapPath(
    slice: WorldSliceUiState,
    exitLit: Boolean,
    widthPx: Int,
    heightPx: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val points = remember(slice.path, slice.levels, slice.unlocked, exitLit, widthPx, heightPx) {
        val lit = MapPathMath.litSegments(slice, exitLit)
        val dots = MapPathMath.dots(
            path = slice.path,
            widthPx = widthPx.toFloat(),
            heightPx = heightPx.toFloat(),
            spacingPx = MapGeometry.PATH_DOT_SPACING * heightPx,
            clearRadiusPx = MapGeometry.px(MapLayout.NODE_DIAMETER, heightPx) / 2f +
                MapGeometry.PATH_DOT_DIAMETER * heightPx,
        )
        val (litDots, dimDots) = dots.partition { lit.getOrElse(it.segment) { false } }
        PathPoints(
            lit = litDots.map { Offset(it.x, it.y) },
            dim = dimDots.map { Offset(it.x, it.y) },
        )
    }
    val dotPx = MapGeometry.PATH_DOT_DIAMETER * heightPx
    Spacer(
        modifier = modifier.drawBehind {
            drawPoints(points.dim, PointMode.Points, tint.copy(alpha = DIM_PATH_ALPHA), dotPx, StrokeCap.Round)
            drawPoints(points.lit, PointMode.Points, tint.copy(alpha = PATH_HALO_ALPHA), dotPx * PATH_HALO_SCALE, StrokeCap.Round)
            drawPoints(points.lit, PointMode.Points, tint, dotPx, StrokeCap.Round)
            drawPoints(points.lit, PointMode.Points, Color.White.copy(alpha = 0.55f), dotPx * 0.45f, StrokeCap.Round)
        },
    )
}

private class PathPoints(val lit: List<Offset>, val dim: List<Offset>)

// ------------------------------------------------------------------------------------ banner

/**
 * A world's banner in the band below `SAFE_BOTTOM` (§6.1): its name in its peg tint, and the
 * crowns earned there out of those available. A world still behind its gate shows a padlock and
 * goes grey — its banner is the first thing that says so from a distance.
 */
@Composable
internal fun WorldBanner(
    slice: WorldSliceUiState,
    tint: Color,
    coin: ImageBitmap?,
    heightPx: Int,
    modifier: Modifier = Modifier,
) {
    val name = worldDisplayName(slice.world)
    val description = stringResource(
        if (slice.unlocked) R.string.map_cd_world_banner else R.string.map_cd_world_banner_locked,
        name,
        slice.crownsEarned,
        slice.crownsAvailable,
    )
    val density = LocalDensity.current
    val icon = with(density) { (heightPx * BANNER_ICON_SCALE).toDp() }
    val padding = with(density) { (heightPx * 0.45f).toDp() }
    val textPx = MapGeometry.textPx(heightPx, BANNER_LABEL_SCALE)
    val shape = RoundedCornerShape(percent = 50)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(shape)
                .background(BandFill)
                .border(1.dp, tint.copy(alpha = if (slice.unlocked) 0.7f else 0.3f), shape)
                .padding(horizontal = padding)
                .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!slice.unlocked) {
                Spacer(Modifier.size(icon).drawBehind { drawPadlock(IcingDim) })
                Spacer(Modifier.width(icon / 3))
            }
            MapText(text = name, sizePx = textPx, color = if (slice.unlocked) tint else IcingDim)
            Spacer(Modifier.width(icon / 2))
            CoinIcon(coin = coin, modifier = Modifier.size(icon))
            Spacer(Modifier.width(icon / 4))
            MapText(
                text = stringResource(R.string.map_banner_crowns, slice.crownsEarned, slice.crownsAvailable),
                sizePx = textPx,
                color = IcingWhite,
            )
        }
    }
}

/** A crown (`coin.png`) sized to its modifier; a gold disc until the sprite lands. */
@Composable
internal fun CoinIcon(coin: ImageBitmap?, modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier.drawBehind {
            if (coin != null) {
                drawImage(image = coin, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            } else {
                drawCircle(CrownGold, radius = size.minDimension * 0.42f)
            }
        },
    )
}

/** §6.1's world names, by world number. */
@Composable
internal fun worldDisplayName(world: Int): String = when (world) {
    1 -> stringResource(R.string.world_1_name)
    2 -> stringResource(R.string.world_2_name)
    3 -> stringResource(R.string.world_3_name)
    4 -> stringResource(R.string.world_4_name)
    else -> stringResource(R.string.map_world_fallback, world)
}

// ------------------------------------------------------------------------------------ shared bits

/**
 * One line of map text, at most [sizePx] tall. `autoSize` shrinks a long — or translated — label
 * to fit its box's width, down to [MIN_TEXT_SCALE] of that, and never grows it past [sizePx].
 */
@Composable
private fun MapText(
    text: String,
    sizePx: Float,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    // `toSp` divides out the font scale on purpose: this text is part of the drawing (see the file
    // KDoc). Floored at one pixel so a not-yet-laid-out box never asks for a zero-size font.
    val maxSize = with(LocalDensity.current) { sizePx.coerceAtLeast(1f).toSp() }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        autoSize = TextAutoSize.StepBased(
            minFontSize = maxSize * MIN_TEXT_SCALE,
            maxFontSize = maxSize,
            stepSize = maxSize * 0.05f,
        ),
        fontWeight = fontWeight,
        textAlign = TextAlign.Center,
        lineHeight = 1.15.em,
        maxLines = 1,
        style = MaterialTheme.typography.labelLarge.copy(
            shadow = Shadow(
                color = LabelShadow,
                offset = Offset(0f, sizePx * 0.06f),
                blurRadius = sizePx * 0.2f,
            ),
        ),
    )
}

/** A padlock over a faint label: how a locked level or Sweet Room reads at a glance. */
@Composable
private fun LockedLabel(text: String, diameterPx: Int, iconColor: Color, textColor: Color) {
    val icon = with(LocalDensity.current) { (diameterPx * LOCK_ICON_SCALE).toDp() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.size(icon).drawBehind { drawPadlock(iconColor) })
        MapText(
            text = text,
            sizePx = MapGeometry.textPx(diameterPx, LOCKED_LABEL_SCALE),
            color = textColor,
        )
    }
}

/** 0 -> 1 -> 0, forever: the current level's heartbeat. Read it in a layer or draw lambda only. */
@Composable
private fun rememberPulse(label: String): State<Float> =
    rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = label,
    )

private fun DrawScope.drawCrown(earned: Boolean, slot: PxRect, sprites: MapSprites, alpha: Float) {
    val sprite = if (earned) sprites.coin else sprites.crownEmpty
    if (sprite != null) {
        drawImage(
            image = sprite,
            dstOffset = IntOffset(slot.left, slot.top),
            dstSize = IntSize(slot.width, slot.height),
            alpha = alpha,
        )
    } else {
        drawCircle(
            color = if (earned) CrownGold else CrownEmpty,
            radius = slot.width * 0.42f,
            center = Offset(slot.centreX, slot.centreY),
            alpha = alpha,
        )
    }
}

/**
 * A padlock filling the largest square in [area]: a rounded body in the lower half and a shackle
 * arc above it. [open] lifts the shackle's right leg clear of the body.
 *
 * Drawn rather than loaded — no padlock ships in the asset pack, and `material-icons` is not a
 * dependency (and may not become one).
 */
private fun DrawScope.drawPadlock(
    color: Color,
    area: Rect = Rect(Offset.Zero, size),
    open: Boolean = false,
) {
    val side = min(area.width, area.height)
    if (side <= 0f) return
    val left = area.left + (area.width - side) / 2f
    val top = area.top + (area.height - side) / 2f
    val cx = left + side / 2f
    val stroke = side * 0.12f
    val bodyWidth = side * 0.74f
    val bodyHeight = side * 0.52f
    val bodyTop = top + side - bodyHeight
    val r = side * 0.24f
    val arcCentreY = top + stroke / 2f + r
    val legBottom = bodyTop + stroke / 2f
    val rightLegBottom = if (open) arcCentreY + (bodyTop - arcCentreY) * 0.25f else legBottom

    val shackle = Path().apply {
        moveTo(cx - r, legBottom)
        lineTo(cx - r, arcCentreY)
        arcTo(
            rect = Rect(cx - r, arcCentreY - r, cx + r, arcCentreY + r),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        lineTo(cx + r, rightLegBottom)
    }
    drawPath(shackle, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - bodyWidth / 2f, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(side * 0.1f),
    )
    drawCircle(KeyholeTint, radius = side * 0.065f, center = Offset(cx, bodyTop + bodyHeight * 0.45f))
}

/** A met half: a green disc with a dark tick. An unmet half: an empty ring. */
private fun DrawScope.drawRequirementMark(met: Boolean) {
    val radius = size.minDimension / 2f
    if (met) {
        drawCircle(StatusSuccess, radius = radius)
        val w = size.width
        val h = size.height
        val tick = Path().apply {
            moveTo(w * 0.27f, h * 0.53f)
            lineTo(w * 0.44f, h * 0.70f)
            lineTo(w * 0.75f, h * 0.34f)
        }
        drawPath(
            path = tick,
            color = NightVoid,
            style = Stroke(width = radius * 0.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    } else {
        val ring = radius * 0.22f
        drawCircle(IcingDim, radius = radius - ring / 2f, style = Stroke(ring))
    }
}

// ------------------------------------------------------------------------------------ copy

@Composable
private fun levelNodeDescription(node: LevelNodeUiState): String {
    val base = when (node.status) {
        NodeStatus.LOCKED -> stringResource(R.string.map_cd_level_locked, node.levelId)
        NodeStatus.AVAILABLE -> stringResource(R.string.map_cd_level_available, node.levelId)
        NodeStatus.CLEARED ->
            pluralStringResource(R.plurals.map_cd_level_cleared, node.crowns, node.levelId, node.crowns)
    }
    return if (node.isCurrent) stringResource(R.string.map_cd_current_format, base) else base
}

@Composable
private fun sweetRoomDescription(room: SweetRoomNodeUiState): String = when (room.status) {
    NodeStatus.LOCKED -> stringResource(
        R.string.map_cd_sweet_room_locked,
        room.code,
        stringResource(room.jarColor.nameRes()),
        room.jarCount,
        room.jarThreshold,
    )
    NodeStatus.AVAILABLE -> stringResource(R.string.map_cd_sweet_room_available, room.code)
    NodeStatus.CLEARED ->
        pluralStringResource(R.plurals.map_cd_sweet_room_cleared, room.crowns, room.code, room.crowns)
}

@Composable
private fun gateDescription(gate: GateUiState): String {
    val name = worldDisplayName(gate.world)
    if (gate.open) return stringResource(R.string.map_cd_gate_open, name)
    return stringResource(
        R.string.map_cd_gate_shut,
        name,
        gate.requiresLevelCleared,
        requirementWord(gate.levelCleared),
        gate.crownsHave,
        gate.crownsRequired,
        requirementWord(gate.crownsHave >= gate.crownsRequired),
    )
}

@Composable
private fun requirementWord(met: Boolean): String =
    stringResource(if (met) R.string.map_cd_requirement_met else R.string.map_cd_requirement_unmet)

/** The branded §4.1 candy tints, as `CandyJarScreen` maps them — a room wears its jar's colour. */
private fun CandyColor.tint(): Color = when (this) {
    CandyColor.GREEN -> CandyGreen
    CandyColor.PURPLE -> CandyPurple
    CandyColor.PINK -> CandyRose
    CandyColor.BLUE -> CandyBlue
}

private fun CandyColor.nameRes(): Int = when (this) {
    CandyColor.GREEN -> R.string.candy_name_green
    CandyColor.PURPLE -> R.string.candy_name_purple
    CandyColor.PINK -> R.string.candy_name_pink
    CandyColor.BLUE -> R.string.candy_name_blue
}

// ---------------------------------------------------------------------------
// Design documentation — see the note in JarCanvas.kt on why these are previews and not tests.
// ---------------------------------------------------------------------------

@Preview(name = "Map nodes", widthDp = 420, heightDp = 240, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun MapNodesPreview() {
    CandyTriangleTheme {
        val nodeDp = 56.dp
        val nodePx = with(LocalDensity.current) { nodeDp.roundToPx() }
        val sprites = rememberMapSprites(sliceHeightPx = 2400)
        val nodes = listOf(
            LevelNodeUiState(7, NodeStatus.CLEARED, 3, 18_200, false, MapPoint(0f, 0f)),
            LevelNodeUiState(8, NodeStatus.CLEARED, 1, 6_400, false, MapPoint(0f, 0f)),
            LevelNodeUiState(9, NodeStatus.AVAILABLE, 0, 0, true, MapPoint(0f, 0f)),
            LevelNodeUiState(10, NodeStatus.LOCKED, 0, 0, false, MapPoint(0f, 0f)),
        )
        val rooms = listOf(
            SweetRoomNodeUiState(101, "B1", NodeStatus.AVAILABLE, 0, 0, CandyColor.GREEN, 212, 200, MapPoint(0f, 0f)),
            SweetRoomNodeUiState(102, "B2", NodeStatus.LOCKED, 0, 0, CandyColor.PURPLE, 131, 200, MapPoint(0f, 0f)),
        )
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                nodes.forEach { node ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LevelNode(node, CandyPink, nodePx, onClick = {}, modifier = Modifier.size(nodeDp))
                        CrownRow(
                            crowns = node.crowns,
                            sprites = sprites,
                            modifier = Modifier.size(nodeDp, 20.dp),
                            dimmed = !node.isPlayable,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                rooms.forEach { room ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SweetRoomNode(room, nodePx, onClick = {}, modifier = Modifier.size(nodeDp))
                        if (room.isPlayable) {
                            CrownRow(0, sprites, Modifier.size(nodeDp, 20.dp))
                        } else {
                            JarProgressLabel(room, heightPx = nodePx / 3, modifier = Modifier.size(nodeDp, 20.dp))
                        }
                    }
                }
                val gate = GateUiState(2, 10, true, 15, 12, false, MapPoint(0f, 0f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GateBadge(gate, CandyPink, onClick = {}, modifier = Modifier.size(nodeDp))
                    GateRequirements(gate, sprites, heightPx = nodePx * 3 / 4, modifier = Modifier.size(120.dp, 42.dp))
                }
            }
        }
    }
}
