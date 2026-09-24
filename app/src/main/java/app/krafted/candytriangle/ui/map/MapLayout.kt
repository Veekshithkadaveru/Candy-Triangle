package app.krafted.candytriangle.ui.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where everything sits on the 4320 x 1920 panorama (§6.1): four 1080 x 1920 world slices laid
 * side by side, scrolled horizontally.
 *
 * Pure arithmetic, no Android — `MapLayoutTest` is the proof that no two nodes overlap and that
 * everything stays clear of the screen chrome, because no composable can run in the JVM suite.
 *
 * ## Units
 *
 * Positions are [MapPoint]s: fractions of the slice's width (x) and height (y). Sizes are fractions
 * of the slice **height**, the one dimension the `LazyRow` fixes (each slice is laid out
 * `fillMaxHeight().aspectRatio(SLICE_ASPECT)`), so a horizontal distance of `dx` in slice-width
 * fractions is `dx * SLICE_ASPECT` in height fractions.
 *
 * ## Contract with `LevelMapScreen`
 *
 * The screen draws with these constants and never hard-codes a size of its own, so retuning a
 * value here moves the drawing and the overlap test together. The constant **names** are frozen
 * (the D3 lead's skeleton); their **values** belong to this file's owner.
 *
 * ## The road: a three-lane meander per slice
 *
 * Each slice carries its world's levels along a serpentine of three horizontal lanes joined by two
 * U-turns. The path enters through the slice's left edge, runs right along the *entry* lane, turns,
 * runs back left along the *middle* lane, turns again and leaves through the right edge along the
 * *exit* lane. Even slices climb (entry lane at the bottom, exit lane at the top) and odd slices
 * descend, so slice `k`'s exit lane **is** slice `k + 1`'s entry lane: the road crosses every seam
 * horizontally at one shared y, and the four slices read as one line across the panorama.
 *
 * - **The gate badge** sits on the entry lane between the entry point and the first node, so the
 *   road into a world visibly runs through its gate (§6.2). Slice 0's gate is computed the same way
 *   and simply not drawn.
 * - **The Sweet Room** sits off the road, in the pocket between the middle and the exit lane on the
 *   side that opens to the right, vertically centred between the two lanes' footprints. (With two
 *   levels or fewer the drawn polyline is one diagonal across the slice and can pass over that
 *   pocket; the room does not chase it.)
 * - **The lanes** are derived from [SAFE_TOP], [SAFE_BOTTOM] and the footprint sizes rather than
 *   written down, so retuning a size moves the lanes with it.
 *
 * ## Why nodes cannot overlap
 *
 * Any node can be the current one, so every node reserves the same box: its square, the crown row
 * below it and the marker square above it. Two such boxes are disjoint once their centres are
 * [KEEP_OUT_X] apart horizontally **or** [KEEP_OUT_Y] apart vertically — and [KEEP_OUT_Y] is more
 * than twice [KEEP_OUT_X], because of the marker. So nodes are spaced evenly along the road not in
 * plain distance but in *keep-out units*: a horizontal run spends one unit per [KEEP_OUT_X], a
 * vertical one per [KEEP_OUT_Y]. Evenly spaced in plain distance, two nodes could share the steep
 * side of a U-turn and collide from 12 levels a slice; in keep-out units every level count up to
 * [NODE_CAPACITY] stays disjoint. Each U-turn is a semicircle *in that metric*, which is what makes
 * its length exactly `PI * r` and keeps [sliceLayout] closed-form, with no numeric integration.
 *
 * Owner: D3 Agent A (map logic).
 */
object MapLayout {

    /** One backdrop slice, in source pixels (`world_1.png`..`world_4.png`). */
    const val SLICE_WIDTH_PX: Int = 1080
    const val SLICE_HEIGHT_PX: Int = 1920

    /** Four worlds, four slices (§6.1). */
    const val SLICE_COUNT: Int = 4

    /** Width / height of one slice — what `Modifier.aspectRatio` takes. */
    const val SLICE_ASPECT: Float = 1080f / 1920f

    /** A main-level node's diameter, as a fraction of slice height. */
    const val NODE_DIAMETER: Float = 0.062f

    /** A Sweet Room node's diameter — a little larger, it is a bonus. */
    const val SWEET_ROOM_DIAMETER: Float = 0.075f

    /** The gate badge's diameter (the padlock disc; its requirement text sits just below it). */
    const val GATE_DIAMETER: Float = 0.080f

    /** The three-crown row drawn directly below every node. */
    const val CROWN_ROW_HEIGHT: Float = 0.022f

    /** The pulsing `triangle.png` current-level marker, drawn directly above the current node. */
    const val MARKER_SIZE: Float = 0.050f

    /** Nothing interactive may sit above this — the top bar overlays it. */
    const val SAFE_TOP: Float = 0.12f

    /** Nothing interactive may sit below this — the world banner overlays it. */
    const val SAFE_BOTTOM: Float = 0.90f

    /**
     * The gate's requirement band, directly below the badge: this tall and [GATE_LABEL_WIDTH] wide.
     *
     * Not one of the frozen names, but part of the footprint every placement here keeps clear, so
     * it lives beside them. `MapGeometry.GATE_LABEL_HEIGHT` restates it for drawing; the two must
     * stay equal.
     */
    internal const val GATE_LABEL_HEIGHT: Float = 0.045f

    /** The requirement band's width: two badge diameters, centred under the badge. */
    internal const val GATE_LABEL_WIDTH: Float = 2f * GATE_DIAMETER

    /**
     * The most main-level nodes one slice holds with every footprint disjoint. `MapLayoutTest`
     * proves `0..NODE_CAPACITY`; beyond it the nodes crowd along the same road and may overlap.
     */
    internal const val NODE_CAPACITY: Int = 16

    /**
     * The layout of slice [sliceIndex] (0-based, world `sliceIndex + 1`) holding [levelsInSlice]
     * main-level nodes.
     *
     * Deterministic, and a function of its two arguments only. [levelsInSlice] is 10 for every
     * shipped world, but comes from the config's `levelFrom..levelTo` so a retuned world still
     * lays out.
     *
     * Degenerate arguments are not errors:
     * - [levelsInSlice] `<= 0` places no nodes; the entry, exit, gate and Sweet Room still stand.
     * - A single node sits where the first of many would, just past the gate.
     * - Above [NODE_CAPACITY] the nodes keep the same even spacing along the same road, so they
     *   stay inside the slice and in order, but their footprints may overlap.
     * - [sliceIndex] outside `0 until SLICE_COUNT` lays out by its parity (floor-mod, so negative
     *   indices work too): even climbs, odd descends. That is exactly how a fifth world would carry
     *   the road on, since slice 3's exit then meets slice 4's entry. [SliceLayout.sliceIndex]
     *   echoes the argument unchanged.
     */
    fun sliceLayout(sliceIndex: Int, levelsInSlice: Int): SliceLayout {
        val climbing = sliceIndex.mod(2) == 0
        val entryY = if (climbing) BOTTOM_LANE else TOP_LANE
        val exitY = if (climbing) TOP_LANE else BOTTOM_LANE
        val count = levelsInSlice.coerceAtLeast(0)
        val nodes = List(count) { i ->
            val along = if (count == 1) {
                FIRST_NODE_AT
            } else {
                FIRST_NODE_AT + (LAST_NODE_AT - FIRST_NODE_AT) * i / (count - 1)
            }
            pointAlong(along, entryY, exitY)
        }
        return SliceLayout(
            sliceIndex = sliceIndex,
            nodes = nodes,
            entry = MapPoint(0f, entryY),
            exit = MapPoint(1f, exitY),
            gate = point(GATE_X, entryY),
            sweetRoom = MapPoint(SWEET_ROOM_X, sweetRoomY(exitY)),
        )
    }

    // ------------------------------------------------------------------ geometry
    //
    // Everything below is in slice-HEIGHT units on both axes (an x of `SLICE_WIDTH` is the right
    // edge); `point` converts to a MapPoint's width fraction on the way out.

    /** The slice's width in height units. */
    private const val SLICE_WIDTH: Float = SLICE_ASPECT

    private const val NODE_RADIUS: Float = NODE_DIAMETER / 2f

    /** Node centres at least this far apart horizontally can never overlap: the node square is the
     *  widest part of a node's box (the marker is narrower). */
    private const val KEEP_OUT_X: Float = NODE_DIAMETER

    /** ...or at least this far apart vertically: the marker above, the node and its crown row. */
    private const val KEEP_OUT_Y: Float = MARKER_SIZE + NODE_DIAMETER + CROWN_ROW_HEIGHT

    /**
     * Every footprint keeps this far from the slice's left and right edges, so the neighbouring
     * slice's footprints are at least twice this away across the seam — the two U-turns that face
     * each other there cannot touch.
     */
    private const val SIDE_INSET: Float = 0.025f

    /** Breathing room between the outermost lanes' footprints and [SAFE_TOP] / [SAFE_BOTTOM]. */
    private const val SAFE_INSET: Float = 0.009f

    /** Clear space between the gate's requirement band and the first node. */
    private const val GATE_CLEARANCE: Float = 0.014f

    /** From the last node's right edge to the slice's right edge: the stretch of road that visibly
     *  leads on into the next world. */
    private const val EXIT_RUN: Float = 0.065f

    /** The Sweet Room's centre, as a fraction of slice **width**: inside the pocket that opens to
     *  the right. */
    private const val SWEET_ROOM_X: Float = 0.78f

    /** The top lane: its markers — or, on a descending slice, the gate badge — just inside
     *  [SAFE_TOP]. */
    private val TOP_LANE: Float =
        SAFE_TOP + SAFE_INSET + max(NODE_RADIUS + MARKER_SIZE, GATE_DIAMETER / 2f)

    /** The bottom lane: its crown rows — or, on a climbing slice, the gate's requirement band —
     *  just inside [SAFE_BOTTOM]. */
    private val BOTTOM_LANE: Float = SAFE_BOTTOM - SAFE_INSET -
        max(NODE_RADIUS + CROWN_ROW_HEIGHT, GATE_DIAMETER / 2f + GATE_LABEL_HEIGHT)

    private val MIDDLE_LANE: Float = (TOP_LANE + BOTTOM_LANE) / 2f

    /** A U-turn's radius in keep-out units: half a lane gap, measured in [KEEP_OUT_Y]s. */
    private val TURN_RADIUS: Float = (MIDDLE_LANE - TOP_LANE) / 2f / KEEP_OUT_Y

    /** The same turn's horizontal reach in height units — what makes it a semicircle in keep-out
     *  units (and an ellipse on screen). */
    private val TURN_REACH_X: Float = TURN_RADIUS * KEEP_OUT_X

    /** The outermost node centres; each U-turn's apex sits exactly on one of them. */
    private val NODE_X_MIN: Float = SIDE_INSET + NODE_RADIUS
    private val NODE_X_MAX: Float = SLICE_WIDTH - SIDE_INSET - NODE_RADIUS

    /** Where the lanes end and the U-turns begin. */
    private val RIGHT_TURN_X: Float = NODE_X_MAX - TURN_REACH_X
    private val LEFT_TURN_X: Float = NODE_X_MIN + TURN_REACH_X

    /** The gate badge's centre: its requirement band flush with [SIDE_INSET]. */
    private val GATE_X: Float = SIDE_INSET + GATE_LABEL_WIDTH / 2f

    private val FIRST_NODE_X: Float = GATE_X + GATE_LABEL_WIDTH / 2f + GATE_CLEARANCE + NODE_RADIUS
    private val LAST_NODE_X: Float = SLICE_WIDTH - EXIT_RUN - NODE_RADIUS

    // The road's five pieces, in keep-out units: entry lane, right U-turn, middle lane, left
    // U-turn, exit lane. Lanes are horizontal, so a lane's length is its width over KEEP_OUT_X.
    private val ENTRY_RUN: Float = RIGHT_TURN_X / KEEP_OUT_X
    private val TURN_LENGTH: Float = PI.toFloat() * TURN_RADIUS
    private val MIDDLE_RUN: Float = (RIGHT_TURN_X - LEFT_TURN_X) / KEEP_OUT_X

    /** Where along the road the first and last nodes sit, in keep-out units. */
    private val FIRST_NODE_AT: Float = FIRST_NODE_X / KEEP_OUT_X
    private val LAST_NODE_AT: Float = ENTRY_RUN + TURN_LENGTH + MIDDLE_RUN + TURN_LENGTH +
        (LAST_NODE_X - LEFT_TURN_X) / KEEP_OUT_X

    /** The point [along] keep-out units from the entry point, walking the five pieces in order. */
    private fun pointAlong(along: Float, entryY: Float, exitY: Float): MapPoint {
        var rest = along
        if (rest <= ENTRY_RUN) return point(rest * KEEP_OUT_X, entryY)
        rest -= ENTRY_RUN
        if (rest <= TURN_LENGTH) {
            return uTurn(RIGHT_TURN_X, side = 1f, entryY, MIDDLE_LANE, rest / TURN_RADIUS)
        }
        rest -= TURN_LENGTH
        if (rest <= MIDDLE_RUN) return point(RIGHT_TURN_X - rest * KEEP_OUT_X, MIDDLE_LANE)
        rest -= MIDDLE_RUN
        if (rest <= TURN_LENGTH) {
            return uTurn(LEFT_TURN_X, side = -1f, MIDDLE_LANE, exitY, rest / TURN_RADIUS)
        }
        rest -= TURN_LENGTH
        return point(min(LEFT_TURN_X + rest * KEEP_OUT_X, SLICE_WIDTH), exitY)
    }

    /**
     * The point [angle] radians into a U-turn from lane [fromY] to lane [toY], bulging to the right
     * ([side] `1f`) or the left (`-1f`) of [baseX]. Moving at one keep-out unit per unit of arc is
     * what [TURN_REACH_X] was chosen for, so `angle = distance / TURN_RADIUS`.
     */
    private fun uTurn(baseX: Float, side: Float, fromY: Float, toY: Float, angle: Float): MapPoint {
        val midY = (fromY + toY) / 2f
        return point(baseX + side * TURN_REACH_X * sin(angle), midY + (fromY - midY) * cos(angle))
    }

    /**
     * The Sweet Room's centre y: its square plus the crown row below it, centred between the middle
     * and exit lanes' footprints (the nearer lane's crown rows above, the farther lane's markers
     * below).
     */
    private fun sweetRoomY(exitY: Float): Float {
        val pocketTop = min(MIDDLE_LANE, exitY) + NODE_RADIUS + CROWN_ROW_HEIGHT
        val pocketBottom = max(MIDDLE_LANE, exitY) - NODE_RADIUS - MARKER_SIZE
        return (pocketTop + pocketBottom) / 2f - CROWN_ROW_HEIGHT / 2f
    }

    /** Height units to a [MapPoint]. */
    private fun point(x: Float, y: Float): MapPoint = MapPoint(x / SLICE_WIDTH, y)
}

/** Everything [MapLayout] places on one slice. */
data class SliceLayout(
    val sliceIndex: Int,
    /** Level node centres, in play order. */
    val nodes: List<MapPoint>,
    /** Where the path enters this slice, on its left edge (x = 0). */
    val entry: MapPoint,
    /** Where the path leaves this slice, on its right edge (x = 1). */
    val exit: MapPoint,
    /** The gate badge centre. Drawn only on slices 1..3 (Worlds 2..4); slice 0 has no gate. */
    val gate: MapPoint,
    /** The Sweet Room node centre (B1 on slice 0 ... B4 on slice 3). */
    val sweetRoom: MapPoint,
)
