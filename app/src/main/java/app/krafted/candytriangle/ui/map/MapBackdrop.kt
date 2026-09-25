package app.krafted.candytriangle.ui.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.ui.board.RenderMath
import app.krafted.candytriangle.ui.theme.BackdropScrim
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.NightVoid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The map's pixels: the lazily decoded world backdrops (§6.1, §13), the three small sprites every
 * node shares (`coin`, `crown_empty`, `triangle`, §9.1), and — in the second half of this file —
 * every number the map UI decides, as pure Android-free objects.
 *
 * The split mirrors `ui/board`'s `SpriteCache` + `RenderMath`: decoding is the only thing here that
 * touches `android.graphics`, and everything that can be a JUnit assertion is one
 * (`MapBackdropTest`). No composable can run in the §11 suite, so the composables in `MapNodes.kt`
 * and `LevelMapScreen.kt` only lay out and paint what these objects hand them.
 *
 * Owner: D3 Agent B (map UI).
 */

private const val TAG = "MapBackdrop"

/** How long a decoded backdrop takes to fade in over its loading background. */
private const val BACKDROP_FADE_MILLIS = 280

/**
 * The extra dim over a world still behind its §6.2 gate, on top of the §9.1 scrim. Its Sweet Room
 * and gate badge are drawn above it, undimmed: a jar can open a Sweet Room on a shut world's slice.
 */
private val LockedWorldDim = Color(0x66000000)

/**
 * One decode at a time, off the main thread.
 *
 * A `LazyRow` composes the visible slices and prefetches one more, so three backdrops can ask to
 * decode in the same frame. Serialised, the peak is one full-size decode plus the slices already
 * held; in parallel it is three full-size decodes at once. The order is also the right one:
 * visible slices compose, and so queue, before the prefetched one.
 */
private val MapDecodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

// ================================================================================================
// Backdrop
// ================================================================================================

/**
 * One world's backdrop slice: `world_N.png`, the §9.1 45% scrim over it and, while the world is
 * still behind its §6.2 gate, a further dim.
 *
 * ## Why not `painterResource`
 *
 * `painterResource` decodes a bitmap drawable synchronously on the main thread, per call site,
 * with no cache shared between call sites. For a 1080 x 1920 backdrop that is an 8.3 MB decode in
 * the middle of a fling, every time a slice scrolls in. Here the decode runs on
 * [MapDecodeDispatcher], sub-sampled to the laid-out size by [MapBackdropMath.sampleSize], and a
 * loading gradient stands in until it lands.
 *
 * ## Memory (§13)
 *
 * The bitmap lives exactly as long as this slice is composed. There is deliberately no cache: a
 * slice that scrolls out of the `LazyRow` forgets its `produceState`, and the bitmap goes with it.
 * It is dropped rather than `recycle()`d, because the render thread may still hold the last frame's
 * display list that draws it; the collector frees it once nothing does.
 *
 * @param widthPx the slice's laid-out width, from its own constraints.
 * @param heightPx the slice's laid-out height (the `LazyRow`'s height).
 * @param locked draws the locked-world dim over the scrim.
 */
@Composable
internal fun MapBackdrop(
    world: Int,
    widthPx: Int,
    heightPx: Int,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    val image = rememberDecodedImage(backdropDrawable(world), widthPx, heightPx, opaque = true)
    val reveal by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(durationMillis = BACKDROP_FADE_MILLIS),
        label = "backdropReveal",
    )
    Spacer(
        modifier = modifier.drawWithCache {
            val loadingBackdrop = Brush.verticalGradient(listOf(NightSurface, NightVoid))
            val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            // Centre-crop rather than stretch: the art is 0.5625 like the slice, but a retouched
            // backdrop of another shape should crop, never distort.
            val crop = image?.let {
                RenderMath.centreCrop(it.width, it.height, dstSize.width, dstSize.height, IntArray(4))
            }
            onDrawBehind {
                drawRect(loadingBackdrop)
                if (image != null && crop != null) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(crop[0], crop[1]),
                        srcSize = IntSize(crop[2] - crop[0], crop[3] - crop[1]),
                        dstSize = dstSize,
                        alpha = reveal,
                    )
                }
                drawRect(BackdropScrim)
                if (locked) drawRect(LockedWorldDim)
            }
        },
    )
}

/**
 * `world_1`..`world_4` (A1 deviation note 3). A `when` rather than `getIdentifier`, as in
 * `SpriteCache`: a renamed drawable is then a build failure instead of a silent 0 at runtime.
 */
@DrawableRes
private fun backdropDrawable(world: Int): Int = when (world) {
    2 -> R.drawable.world_2
    3 -> R.drawable.world_3
    4 -> R.drawable.world_4
    else -> R.drawable.world_1
}

// ================================================================================================
// Shared sprites
// ================================================================================================

/**
 * The sprites the map draws many times over: the §5.3 crowns (`coin` earned, `crown_empty` not)
 * and the `triangle.png` current-level marker (§9.1). `null` until decoded; every consumer has a
 * vector fallback, so the map is complete without them.
 *
 * Decoded once per screen and shared, because `painterResource` would decode a 400 x 400 sprite
 * per call site — three per node, two to three slices on screen — which is tens of megabytes.
 */
@Immutable
internal class MapSprites(
    val coin: ImageBitmap?,
    val crownEmpty: ImageBitmap?,
    val marker: ImageBitmap?,
) {
    companion object {
        val NONE = MapSprites(coin = null, crownEmpty = null, marker = null)
    }
}

/** [MapSprites] sub-sampled for a slice [sliceHeightPx] tall — see [MapGeometry.crownSpritePx]. */
@Composable
internal fun rememberMapSprites(sliceHeightPx: Int): MapSprites {
    val crownPx = MapGeometry.crownSpritePx(sliceHeightPx)
    val markerPx = MapGeometry.markerSpritePx(sliceHeightPx)
    val coin = rememberDecodedImage(R.drawable.coin, crownPx, crownPx, opaque = false)
    val crownEmpty = rememberDecodedImage(R.drawable.crown_empty, crownPx, crownPx, opaque = false)
    val marker = rememberDecodedImage(R.drawable.triangle, markerPx, markerPx, opaque = false)
    return remember(coin, crownEmpty, marker) { MapSprites(coin, crownEmpty, marker) }
}

// ================================================================================================
// Decoding
// ================================================================================================

/**
 * [resId] decoded off the main thread for a [targetWidthPx] x [targetHeightPx] box, or `null`
 * until it lands (and forever, if it cannot be decoded — callers draw a fallback).
 *
 * `@Preview`s decode synchronously instead: a static preview renders one frame and never sees an
 * asynchronous result, and previews are the only way to eyeball this screen without an emulator
 * (D1/D2 note 17). Inspection mode never changes within a composition, so the branch is stable.
 */
@Composable
private fun rememberDecodedImage(
    @DrawableRes resId: Int,
    targetWidthPx: Int,
    targetHeightPx: Int,
    opaque: Boolean,
): ImageBitmap? {
    val resources = LocalResources.current
    if (LocalInspectionMode.current) {
        return remember(resources, resId, targetWidthPx, targetHeightPx) {
            decodeImage(resources, resId, targetWidthPx, targetHeightPx, opaque)
        }
    }
    // Keys restart the decode; the old image stays up until the new one replaces it.
    val image by produceState<ImageBitmap?>(null, resources, resId, targetWidthPx, targetHeightPx) {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return@produceState
        value = withContext(MapDecodeDispatcher) {
            decodeImage(resources, resId, targetWidthPx, targetHeightPx, opaque)
        }
    }
    return image
}

/**
 * Decodes [resId] at the largest power-of-two sub-sample that still covers the target box.
 *
 * **`inScaled = false` is mandatory** (D1/D2 note 9): the art lives in `drawable-nodpi`, and density
 * scaling would silently resize it out from under every size computed here.
 *
 * [opaque] asks for `RGB_565`, half the memory of `ARGB_8888` for art with no transparency. The
 * shipped backdrops defeat the request on their own: they are palette PNGs whose `tRNS` chunk marks
 * index 255 transparent — a colour no pixel uses — and Skia reads any `tRNS` as "has alpha", so it
 * decodes `ARGB_8888` whatever is asked. An opaque decode that comes back in another config is
 * therefore converted here; if the assets are ever re-encoded without that chunk, the conversion is
 * simply never reached.
 */
private fun decodeImage(
    resources: Resources,
    @DrawableRes resId: Int,
    targetWidthPx: Int,
    targetHeightPx: Int,
    opaque: Boolean,
): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    runCatching { BitmapFactory.decodeResource(resources, resId, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.w(TAG, "resource $resId has no decodable bounds")
        return null
    }
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        inSampleSize = MapBackdropMath.sampleSize(
            srcWidth = bounds.outWidth,
            srcHeight = bounds.outHeight,
            dstWidth = targetWidthPx,
            dstHeight = targetHeightPx,
        )
    }
    val decoded = runCatching { BitmapFactory.decodeResource(resources, resId, options) }.getOrNull()
    if (decoded == null) {
        Log.w(TAG, "resource $resId failed to decode; its fallback stands in")
        return null
    }
    val bitmap = if (opaque && decoded.config != Bitmap.Config.RGB_565) toRgb565(decoded) else decoded
    return bitmap.asImageBitmap()
}

/** An `RGB_565` copy of [decoded], which is recycled — it was never handed to anything that draws. */
private fun toRgb565(decoded: Bitmap): Bitmap {
    val copy = runCatching { decoded.copy(Bitmap.Config.RGB_565, false) }.getOrNull() ?: return decoded
    decoded.recycle()
    return copy
}

// ================================================================================================
// Pure arithmetic — covered by MapBackdropTest.
//
// Everything below is Android-free: no Canvas, no Bitmap, no Resources. It is where every number the
// map UI paints is decided, so that it can be asserted on the JVM (D1/D2 note 17).
// ================================================================================================

/** Decode sizing for the backdrops and sprites. */
internal object MapBackdropMath {

    /**
     * The `BitmapFactory.Options.inSampleSize` for a [srcWidth] x [srcHeight] image drawn into a
     * [dstWidth] x [dstHeight] box: the largest power of two that keeps **both** decoded dimensions
     * at or above the box, so nothing is ever decoded smaller than it is drawn.
     *
     * The per-axis step is `RenderMath.sampleSizeFor`, so the map and the board sub-sample the same
     * way. A degenerate size means "not laid out yet" and decodes at 1.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) return 1
        return minOf(
            RenderMath.sampleSizeFor(srcWidth, dstWidth),
            RenderMath.sampleSizeFor(srcHeight, dstHeight),
        ).coerceAtLeast(1)
    }
}

/** A `LazyListState.scrollToItem(index, scrollOffset)` target, in slice indices and pixels. */
@Immutable
internal data class MapScrollTarget(val index: Int, val offsetPx: Int)

/** Where the map opens. */
internal object MapScrollMath {

    /** The panorama's left edge. */
    val START = MapScrollTarget(index = 0, offsetPx = 0)

    /**
     * The opening position: the current level's node centred horizontally.
     *
     * The node is found by [LevelMapUiState.currentLevelId], which also yields its slice; if no
     * slice holds that level (a malformed state), the map opens on the middle of
     * [LevelMapUiState.currentSliceIndex] instead.
     */
    fun openingScroll(
        state: LevelMapUiState,
        sliceWidthPx: Int,
        viewportWidthPx: Int,
    ): MapScrollTarget {
        state.slices.forEachIndexed { index, slice ->
            val node = slice.levels.firstOrNull { it.levelId == state.currentLevelId }
            if (node != null) {
                return centreOn(index, node.position.x, state.slices.size, sliceWidthPx, viewportWidthPx)
            }
        }
        return centreOn(state.currentSliceIndex, 0.5f, state.slices.size, sliceWidthPx, viewportWidthPx)
    }

    /**
     * The scroll position that puts [nodeX] (a fraction of slice [sliceIndex]'s width) at the
     * horizontal centre of a [viewportWidthPx]-wide viewport over [sliceCount] slices of
     * [sliceWidthPx] each — clamped so the panorama never scrolls past either end.
     *
     * Always a valid `scrollToItem` target: `index` in `0 until sliceCount`, `offsetPx` >= 0 and
     * `index * sliceWidthPx + offsetPx` the clamped scroll distance. Unusable inputs (nothing laid
     * out yet) open at [START].
     */
    fun centreOn(
        sliceIndex: Int,
        nodeX: Float,
        sliceCount: Int,
        sliceWidthPx: Int,
        viewportWidthPx: Int,
    ): MapScrollTarget {
        if (sliceCount <= 0 || sliceWidthPx <= 0 || viewportWidthPx <= 0) return START
        val slice = sliceIndex.coerceIn(0, sliceCount - 1)
        val x = if (nodeX.isFinite()) nodeX.coerceIn(0f, 1f) else 0.5f
        val nodePx = slice.toLong() * sliceWidthPx + (x * sliceWidthPx).roundToInt()
        val maxScroll = (sliceCount.toLong() * sliceWidthPx - viewportWidthPx).coerceAtLeast(0L)
        val scroll = (nodePx - viewportWidthPx / 2).coerceIn(0L, maxScroll)
        val index = (scroll / sliceWidthPx).toInt().coerceIn(0, sliceCount - 1)
        return MapScrollTarget(index, (scroll - index.toLong() * sliceWidthPx).toInt())
    }
}

/** An axis-aligned box in one slice's pixel space, measured from the slice's top-left corner. */
@Immutable
internal data class PxRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val centreX: Float get() = left + width / 2f
    val centreY: Float get() = top + height / 2f
}

/**
 * Every box the map paints, in a slice's pixels: `MapPoint`s times the slice's laid-out size for
 * positions, `MapLayout`'s fractions times the slice **height** for sizes (the one dimension the
 * `LazyRow` fixes).
 *
 * The regions are exactly the ones `MapLayout` reserves — a node, the crown row directly below it,
 * the marker square directly above the current one, the gate badge and its requirement band, the
 * Sweet Room and the band below it, and the world banner under `SAFE_BOTTOM` — so `MapLayoutTest`'s
 * no-overlap proof covers what is actually drawn. Nothing here has a size of its own.
 */
internal object MapGeometry {

    /**
     * The gate's requirement band: this tall, [GATE_LABEL_WIDTH_IN_DIAMETERS] gate diameters wide,
     * directly below the badge.
     *
     * The one reserved size outside `MapLayout`'s frozen names. Aliased to
     * `MapLayout.GATE_LABEL_HEIGHT` / `GATE_LABEL_WIDTH`, the footprint `MapLayoutTest` keeps clear,
     * so the band drawn here and the band the layout reserves cannot drift apart.
     */
    const val GATE_LABEL_HEIGHT: Float = MapLayout.GATE_LABEL_HEIGHT
    const val GATE_LABEL_WIDTH_IN_DIAMETERS: Float = MapLayout.GATE_LABEL_WIDTH / MapLayout.GATE_DIAMETER

    /** An open gate is drawn this fraction of the badge — "the gate is behind you". */
    const val OPEN_GATE_SCALE: Float = 0.6f

    /** The world banner, as fractions of the band below `SAFE_BOTTOM`: a gap, then the pill. The
     *  rest of the band is left for a transiently revealed navigation bar. */
    const val BANNER_INSET: Float = 0.08f
    const val BANNER_FILL: Float = 0.55f

    /** Three crowns per node (§5.3). */
    const val CROWNS_PER_ROW: Int = 3

    /** Gap between two crowns in a row, as a fraction of the row height. */
    const val CROWN_GAP: Float = 0.12f

    /** The dotted path, as fractions of slice height. */
    const val PATH_DOT_DIAMETER: Float = 0.0085f
    const val PATH_DOT_SPACING: Float = 0.026f

    /** Text size as a fraction of the box it sits in (single line; line height ~ 1.2 x size). */
    const val NODE_LABEL_SCALE: Float = 0.40f
    const val SWEET_ROOM_LABEL_SCALE: Float = 0.34f
    const val BAND_LABEL_SCALE: Float = 0.72f

    /**
     * Smallest decode target for a crown sprite. The top bar's crown chip is sized in dp, not in
     * slice fractions, so on a short low-density screen it can want more pixels than a node's row.
     */
    const val MIN_SPRITE_PX: Int = 48

    /** [fraction] of [heightPx], rounded; never negative. */
    fun px(fraction: Float, heightPx: Int): Int {
        if (!fraction.isFinite() || heightPx <= 0) return 0
        return (fraction * heightPx).roundToInt().coerceAtLeast(0)
    }

    /** A square [diameter] (fraction of slice height) across, centred on [point]. */
    fun centred(point: MapPoint, diameter: Float, widthPx: Int, heightPx: Int): PxRect {
        val side = px(diameter, heightPx)
        val cx = finite(point.x) * widthPx
        val cy = finite(point.y) * heightPx
        return PxRect((cx - side / 2f).roundToInt(), (cy - side / 2f).roundToInt(), side, side)
    }

    fun levelNode(point: MapPoint, widthPx: Int, heightPx: Int): PxRect =
        centred(point, MapLayout.NODE_DIAMETER, widthPx, heightPx)

    fun sweetRoom(point: MapPoint, widthPx: Int, heightPx: Int): PxRect =
        centred(point, MapLayout.SWEET_ROOM_DIAMETER, widthPx, heightPx)

    fun gate(point: MapPoint, widthPx: Int, heightPx: Int): PxRect =
        centred(point, MapLayout.GATE_DIAMETER, widthPx, heightPx)

    /**
     * A node's three-crown row: `CROWN_ROW_HEIGHT` tall, directly below it and no wider than it, so
     * it can never reach a neighbour's region whatever width `MapLayout` reserves for it.
     */
    fun crownRow(node: PxRect, heightPx: Int): PxRect =
        below(node, node.width, px(MapLayout.CROWN_ROW_HEIGHT, heightPx))

    /** The band below a Sweet Room: its crowns, or its jar progress while locked. */
    fun sweetRoomBand(room: PxRect, heightPx: Int): PxRect =
        below(room, room.width, px(MapLayout.CROWN_ROW_HEIGHT, heightPx))

    /** The `MARKER_SIZE` square directly above the current node. */
    fun marker(node: PxRect, heightPx: Int): PxRect {
        val side = px(MapLayout.MARKER_SIZE, heightPx)
        return PxRect((node.centreX - side / 2f).roundToInt(), node.top - side, side, side)
    }

    /** The shut gate's requirement band, directly below the badge. */
    fun gateLabel(gate: PxRect, heightPx: Int): PxRect = below(
        anchor = gate,
        width = px(MapLayout.GATE_DIAMETER * GATE_LABEL_WIDTH_IN_DIAMETERS, heightPx),
        height = px(GATE_LABEL_HEIGHT, heightPx),
    )

    /** The world banner: full slice width, inside the band below `SAFE_BOTTOM`. */
    fun banner(widthPx: Int, heightPx: Int): PxRect {
        val safeBottom = px(MapLayout.SAFE_BOTTOM, heightPx).coerceAtMost(heightPx.coerceAtLeast(0))
        val band = (heightPx - safeBottom).coerceAtLeast(0)
        val top = safeBottom + (band * BANNER_INSET).roundToInt()
        val height = (band * BANNER_FILL).roundToInt().coerceAtMost((heightPx - top).coerceAtLeast(0))
        return PxRect(0, top, widthPx.coerceAtLeast(0), height)
    }

    /**
     * [count] equal square slots, centred in [row] and separated by [CROWN_GAP] — as large as the
     * row's height allows, shrunk only if the row is too narrow to hold them.
     */
    fun crownSlots(row: PxRect, count: Int = CROWNS_PER_ROW): List<PxRect> {
        if (count <= 0 || row.width <= 0 || row.height <= 0) return emptyList()
        val gap = (row.height * CROWN_GAP).roundToInt()
        val side = minOf(row.height, (row.width - gap * (count - 1)) / count)
        if (side <= 0) return emptyList()
        val total = side * count + gap * (count - 1)
        val left = row.left + (row.width - total) / 2
        val top = row.top + (row.height - side) / 2
        return List(count) { i -> PxRect(left + i * (side + gap), top, side, side) }
    }

    /**
     * The decode target for the crown sprites: the tallest place a crown is drawn — a crown row, or
     * one line of the gate band — and never below [MIN_SPRITE_PX].
     */
    fun crownSpritePx(heightPx: Int): Int = max(
        px(max(MapLayout.CROWN_ROW_HEIGHT, GATE_LABEL_HEIGHT / 2f), heightPx),
        MIN_SPRITE_PX,
    )

    /** The decode target for the `triangle.png` marker. */
    fun markerSpritePx(heightPx: Int): Int = max(px(MapLayout.MARKER_SIZE, heightPx), MIN_SPRITE_PX)

    /** A text size, in pixels, for a single line filling [fraction] of a [boxPx]-tall box. */
    fun textPx(boxPx: Int, fraction: Float): Float =
        if (boxPx <= 0 || !fraction.isFinite()) 0f else (boxPx * fraction).coerceAtLeast(0f)

    private fun below(anchor: PxRect, width: Int, height: Int): PxRect =
        PxRect((anchor.centreX - width / 2f).roundToInt(), anchor.bottom, width, height)

    private fun finite(value: Float): Float = if (value.isFinite()) value else 0f
}

/** One dot of the dotted path, in slice pixels, on [segment] (`path[segment]` -> `path[segment + 1]`). */
@Immutable
internal data class PathDot(val x: Float, val y: Float, val segment: Int)

/** The dotted path through a slice (`WorldSliceUiState.path`). */
internal object MapPathMath {

    /**
     * Which stretches of [slice]'s path are lit: segment `k` runs from `path[k]` to `path[k + 1]` and
     * is lit when the node it leads **into** is playable, so the lit path stops exactly at the
     * player's frontier. The last segment leads off the slice's right edge and is lit per
     * [exitLit].
     *
     * The contract promises `path.size == levels.size + 2` (entry, every node, exit). A path of any
     * other shape cannot be matched to its nodes, so it is lit or dimmed as a whole with the world.
     */
    fun litSegments(slice: WorldSliceUiState, exitLit: Boolean): List<Boolean> {
        val segments = slice.path.size - 1
        if (segments <= 0) return emptyList()
        if (slice.path.size != slice.levels.size + 2) return List(segments) { slice.unlocked }
        return List(segments) { k ->
            if (k < slice.levels.size) slice.levels[k].isPlayable else exitLit
        }
    }

    /**
     * Whether slice [sliceIndex]'s exit stretch is lit: it continues into the next slice, so it
     * matches that slice's entry stretch (lit when the next world's first level is playable), and
     * the two halves meet at the seam in the same state. The last slice leads nowhere; its exit is
     * lit once its own last level is cleared.
     */
    fun exitLit(state: LevelMapUiState, sliceIndex: Int): Boolean {
        val next = state.slices.getOrNull(sliceIndex + 1)
        if (next != null) return next.levels.firstOrNull()?.isPlayable ?: next.unlocked
        return state.slices.getOrNull(sliceIndex)?.levels?.lastOrNull()?.status == NodeStatus.CLEARED
    }

    /**
     * Dot centres every [spacingPx] along [path] (scaled to [widthPx] x [heightPx]), measured along
     * the whole polyline so the rhythm carries across corners rather than restarting at each node.
     *
     * Dots within [clearRadiusPx] of an interior vertex — a node, since `path` is entry, nodes,
     * exit — are dropped (the rhythm still advances), so the path meets each node's rim instead of
     * showing through a dimmed disc. The entry and exit points on the slice edges are not cleared:
     * the path runs straight through the seam into the next slice.
     */
    fun dots(
        path: List<MapPoint>,
        widthPx: Float,
        heightPx: Float,
        spacingPx: Float,
        clearRadiusPx: Float,
    ): List<PathDot> {
        if (path.size < 2) return emptyList()
        if (!(widthPx > 0f) || !(heightPx > 0f) || !widthPx.isFinite() || !heightPx.isFinite()) {
            return emptyList()
        }
        if (!(spacingPx > 0f) || !spacingPx.isFinite()) return emptyList()
        // A sub-pixel spacing would only produce a solid line at great cost.
        val spacing = max(spacingPx, MIN_DOT_SPACING_PX)
        val clear = if (clearRadiusPx.isFinite()) clearRadiusPx.coerceAtLeast(0f) else 0f
        val interior = 1..(path.size - 2)

        val dots = ArrayList<PathDot>()
        var lead = 0f // how far into the next segment its first dot falls
        for (k in 0 until path.size - 1) {
            val ax = path[k].x * widthPx
            val ay = path[k].y * heightPx
            val bx = path[k + 1].x * widthPx
            val by = path[k + 1].y * heightPx
            val length = hypot(bx - ax, by - ay)
            if (!length.isFinite()) {
                lead = 0f
                continue
            }
            var d = lead
            while (d < length) {
                val t = d / length
                val x = ax + (bx - ax) * t
                val y = ay + (by - ay) * t
                val nearStart = k in interior && hypot(x - ax, y - ay) < clear
                val nearEnd = (k + 1) in interior && hypot(x - bx, y - by) < clear
                if (!nearStart && !nearEnd) dots += PathDot(x, y, k)
                d += spacing
            }
            lead = d - length
        }
        return dots
    }

    private const val MIN_DOT_SPACING_PX: Float = 1f
}

/**
 * What a tap on something locked explains. The screen turns it into copy (`strings_map.xml`); the
 * decision of *which* explanation applies is made here, where it can be tested.
 */
internal sealed interface MapHint {

    /** The world is open; the level before this one is not cleared yet. */
    data class ClearPrevious(val levelId: Int) : MapHint

    /**
     * [world]'s §6.2 gate is shut. [levelToClear] is `null` when that half is already met, and
     * [crownsShort] is 0 when the crown half is; never both, or the gate would be open.
     */
    data class OpenGate(val world: Int, val levelToClear: Int?, val crownsShort: Int) : MapHint

    /** A Sweet Room whose jar is [candiesShort] (> 0) candies short of tier 3 (§7). */
    data class FillJar(val code: String, val jarColor: CandyColor, val candiesShort: Int) : MapHint
}

/** Picks the [MapHint] for a locked node. `null` means there is nothing to explain. */
internal object MapHints {

    /**
     * A locked level: its world's gate if the world is shut (the bigger blocker), otherwise the
     * previous level in play order.
     */
    fun forLevel(slice: WorldSliceUiState, node: LevelNodeUiState): MapHint? {
        if (node.isPlayable) return null
        if (!slice.unlocked) slice.gate?.let(::forGate)?.let { return it }
        return if (node.levelId > LevelIds.MAIN_FIRST) MapHint.ClearPrevious(node.levelId - 1) else null
    }

    /** A shut gate: whichever of its two §6.2 halves are still unmet. */
    fun forGate(gate: GateUiState): MapHint? {
        if (gate.open) return null
        val levelToClear = gate.requiresLevelCleared.takeUnless { gate.levelCleared }
        val crownsShort = (gate.crownsRequired - gate.crownsHave).coerceAtLeast(0)
        if (levelToClear == null && crownsShort == 0) return null
        return MapHint.OpenGate(gate.world, levelToClear, crownsShort)
    }

    /** A locked Sweet Room: how far its jar is from tier 3. World gates never apply to it. */
    fun forSweetRoom(room: SweetRoomNodeUiState): MapHint? {
        if (room.isPlayable) return null
        val short = room.jarThreshold - room.jarCount
        return if (short > 0) MapHint.FillJar(room.code, room.jarColor, short) else null
    }
}
