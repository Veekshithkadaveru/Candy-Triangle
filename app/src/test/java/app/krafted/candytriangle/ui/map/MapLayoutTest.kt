package app.krafted.candytriangle.ui.map

import app.krafted.candytriangle.ui.map.MapLayout.CROWN_ROW_HEIGHT
import app.krafted.candytriangle.ui.map.MapLayout.GATE_DIAMETER
import app.krafted.candytriangle.ui.map.MapLayout.GATE_LABEL_HEIGHT
import app.krafted.candytriangle.ui.map.MapLayout.GATE_LABEL_WIDTH
import app.krafted.candytriangle.ui.map.MapLayout.MARKER_SIZE
import app.krafted.candytriangle.ui.map.MapLayout.NODE_CAPACITY
import app.krafted.candytriangle.ui.map.MapLayout.NODE_DIAMETER
import app.krafted.candytriangle.ui.map.MapLayout.SAFE_BOTTOM
import app.krafted.candytriangle.ui.map.MapLayout.SAFE_TOP
import app.krafted.candytriangle.ui.map.MapLayout.SLICE_ASPECT
import app.krafted.candytriangle.ui.map.MapLayout.SLICE_COUNT
import app.krafted.candytriangle.ui.map.MapLayout.SWEET_ROOM_DIAMETER
import app.krafted.candytriangle.ui.map.MapLayout.sliceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proof behind `MapLayout`: nothing the map draws overlaps anything else, and nothing
 * interactive sits under the screen chrome.
 *
 * This has to be a unit test rather than a look at a device — §11's suite is JVM-only and no
 * composable can run in it — so every box `LevelMapScreen` paints is rebuilt here, in slice-height
 * units, from `MapLayout`'s own constants (see [SliceFootprints]).
 */
class MapLayoutTest {

    private val shipped = 10
    private val slices = 0 until SLICE_COUNT

    // ---------------------------------------------------------------- the shipped case

    @Test
    fun tenLevelSlicesReserveDisjointFootprints() {
        for (k in slices) {
            val clashes = SliceFootprints.clashes(sliceLayout(k, shipped))
            assertTrue("slice $k: $clashes", clashes.isEmpty())
        }
    }

    @Test
    fun everyFootprintStaysInsideTheSliceAndTheSafeBand() {
        for (k in slices) {
            val outside = SliceFootprints.outOfBounds(sliceLayout(k, shipped))
            assertTrue("slice $k: $outside", outside.isEmpty())
        }
    }

    @Test
    fun theRoadEntersOnTheLeftEdgeLeavesOnTheRightAndMeetsItselfAtEverySeam() {
        val layouts = slices.map { sliceLayout(it, shipped) }
        for (layout in layouts) {
            assertEquals(0f, layout.entry.x, 0f)
            assertEquals(1f, layout.exit.x, 0f)
        }
        for (k in 0 until SLICE_COUNT - 1) {
            // Exactly equal, not approximately: the road must not step at a seam.
            assertEquals("seam $k|${k + 1}", layouts[k].exit.y, layouts[k + 1].entry.y, 0f)
        }
    }

    /**
     * The right-hand U-turn of one slice faces the left-hand U-turn of the next at the same height,
     * so a per-slice bounds check alone would let their nodes touch across the seam.
     */
    @Test
    fun neighbouringSlicesDoNotCrowdEachOtherAcrossTheSeam() {
        for (count in listOf(1, 5, shipped, 12, NODE_CAPACITY)) {
            val layouts = slices.map { sliceLayout(it, count) }
            for (k in 0 until SLICE_COUNT - 1) {
                val clashes = SliceFootprints.seamClashes(layouts[k], layouts[k + 1])
                assertTrue("$count levels, seam $k|${k + 1}: $clashes", clashes.isEmpty())
            }
        }
    }

    /** The suggested boustrophedon: even slices climb, odd slices descend. */
    @Test
    fun evenSlicesClimbAndOddSlicesDescend() {
        for (k in slices) {
            val layout = sliceLayout(k, shipped)
            val climbs = layout.nodes.first().y > layout.nodes.last().y
            assertEquals("slice $k climbs", k % 2 == 0, climbs)
            assertEquals("slice $k entry below exit", k % 2 == 0, layout.entry.y > layout.exit.y)
        }
    }

    /**
     * §6.2's gate reads as a barrier only if the road runs through it: it sits on the entry lane,
     * strictly between the entry point and the first node, so the polyline's first segment crosses
     * the badge's centre.
     */
    @Test
    fun theGateSitsOnTheRoadBetweenTheEntryAndTheFirstNode() {
        for (k in slices) {
            for (count in 1..NODE_CAPACITY) {
                val layout = sliceLayout(k, count)
                val first = layout.nodes.first()
                assertEquals("slice $k, $count levels", layout.entry.y, layout.gate.y, 0f)
                assertEquals("slice $k, $count levels", layout.entry.y, first.y, 0f)
                assertTrue(layout.entry.x < layout.gate.x && layout.gate.x < first.x)
            }
        }
    }

    /**
     * No stretch of the drawn polyline (entry, nodes, exit) passes over the Sweet Room.
     *
     * From three levels up, that is: with two or fewer the "road" is a single diagonal across the
     * slice with no meander left to follow, and the room does not chase it (see `MapLayout`).
     */
    @Test
    fun theSweetRoomSitsOffTheRoad() {
        for (k in slices) {
            for (count in 3..NODE_CAPACITY) {
                val layout = sliceLayout(k, count)
                val room = SliceFootprints.sweetRoom(layout.sweetRoom)
                val road = listOf(layout.entry) + layout.nodes + listOf(layout.exit)
                for ((a, b) in road.zipWithNext()) {
                    val ax = SliceFootprints.hx(a)
                    val bx = SliceFootprints.hx(b)
                    for (step in 0..ROAD_SAMPLES) {
                        val t = step.toFloat() / ROAD_SAMPLES
                        val x = ax + (bx - ax) * t
                        val y = a.y + (b.y - a.y) * t
                        assertTrue(
                            "slice $k, $count levels: the road crosses the Sweet Room at ($x, $y)",
                            !room.contains(x, y),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun layoutIsAFunctionOfItsArgumentsOnly() {
        for (k in -2..SLICE_COUNT + 1) {
            for (count in 0..NODE_CAPACITY + 2) {
                assertEquals(sliceLayout(k, count), sliceLayout(k, count))
            }
        }
    }

    // ---------------------------------------------------------------- other level counts

    /** A retuned `config.json` may give a world any number of levels; these three are the brief's
     *  own examples. */
    @Test
    fun oneFiveAndTwelveLevelsLayOutWithoutOverlap() {
        for (count in listOf(1, 5, 12)) {
            for (k in slices) {
                val layout = sliceLayout(k, count)
                assertEquals(count, layout.nodes.size)
                val problems = SliceFootprints.clashes(layout) + SliceFootprints.outOfBounds(layout)
                assertTrue("slice $k, $count levels: $problems", problems.isEmpty())
            }
        }
    }

    /** `NODE_CAPACITY` is a claim, so every count up to it is checked, not just a sample. */
    @Test
    fun everyCountUpToTheCapacityLaysOutWithoutOverlap() {
        for (count in 0..NODE_CAPACITY) {
            for (k in slices) {
                val layout = sliceLayout(k, count)
                val problems = SliceFootprints.clashes(layout) + SliceFootprints.outOfBounds(layout)
                assertTrue("slice $k, $count levels: $problems", problems.isEmpty())
            }
        }
    }

    @Test
    fun nonPositiveCountsPlaceNoNodesButKeepTheRestOfTheSlice() {
        for (count in listOf(0, -1, Int.MIN_VALUE)) {
            for (k in slices) {
                val layout = sliceLayout(k, count)
                assertTrue(layout.nodes.isEmpty())
                assertEquals(sliceLayout(k, shipped).copy(nodes = emptyList()), layout)
                val problems = SliceFootprints.clashes(layout) + SliceFootprints.outOfBounds(layout)
                assertTrue("slice $k, $count levels: $problems", problems.isEmpty())
            }
        }
    }

    /**
     * Past the capacity nodes may crowd, but the layout must not throw, must still place one node
     * per level inside the slice, and must keep the road's two ends where they always are.
     */
    @Test
    fun countsBeyondTheCapacityStillLayOutInsideTheSlice() {
        for (count in listOf(NODE_CAPACITY + 1, 40, 200)) {
            for (k in slices) {
                val layout = sliceLayout(k, count)
                val reference = sliceLayout(k, shipped)
                assertEquals(count, layout.nodes.size)
                assertEquals(reference.nodes.first(), layout.nodes.first())
                assertEquals(reference.nodes.last().x, layout.nodes.last().x, 1e-5f)
                assertEquals(reference.nodes.last().y, layout.nodes.last().y, 1e-5f)
                val outside = SliceFootprints.outOfBounds(layout)
                assertTrue("slice $k, $count levels: $outside", outside.isEmpty())
            }
        }
    }

    // ---------------------------------------------------------------- slice index

    /**
     * An index outside `0 until SLICE_COUNT` lays out by its parity — which is also how a fifth
     * world would carry the road on from the fourth.
     */
    @Test
    fun outOfRangeSliceIndicesLayOutByTheirParity() {
        val climbing = sliceLayout(0, shipped)
        val descending = sliceLayout(1, shipped)
        for (index in listOf(4, 6, -2, Int.MIN_VALUE)) {
            assertEquals(climbing.copy(sliceIndex = index), sliceLayout(index, shipped))
        }
        for (index in listOf(5, -1, -3, Int.MAX_VALUE)) {
            assertEquals(descending.copy(sliceIndex = index), sliceLayout(index, shipped))
        }
        assertEquals(sliceLayout(3, shipped).exit.y, sliceLayout(4, shipped).entry.y, 0f)
    }

    // ---------------------------------------------------------------- sizes

    /** ~50 dp on a 915 dp tall phone: below this a node stops being a comfortable touch target. */
    @Test
    fun aNodeStaysATouchTarget() {
        assertTrue(NODE_DIAMETER >= 0.055f)
    }

    private companion object {
        /** Points checked per road segment — well under a pixel apart at 1920 px. */
        const val ROAD_SAMPLES = 400
    }
}

/**
 * Every box the map reserves, rebuilt in slice-**height** units from `MapLayout`'s constants: x is
 * a `MapPoint`'s width fraction times `SLICE_ASPECT`, so both axes share one scale. Shared with
 * `MapStateMapperTest`, which runs the same proof over the real `config.json`'s world sizes.
 *
 * The reserved regions, as the D3 brief defines them and as `MapGeometry` draws them:
 * - **body** — the node square, extended down by the crown row drawn under every node;
 * - **marker** — the `triangle.png` square directly above the body (any node can be current);
 * - **gate** — the badge square, plus its requirement band directly below it;
 * - **Sweet Room** — its square, extended down by its crown / jar-progress row.
 */
internal object SliceFootprints {

    /** Two footprints closer than this count as touching: a few pixels of rounding at 1920 px. */
    const val MIN_GAP: Float = 0.003f

    /** Every footprint keeps this far inside the slice's left and right edges. */
    const val SIDE_MARGIN: Float = 0.02f

    data class Box(
        val what: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {

        fun clashesWith(other: Box): Boolean =
            left < other.right + MIN_GAP && other.left < right + MIN_GAP &&
                top < other.bottom + MIN_GAP && other.top < bottom + MIN_GAP

        fun contains(x: Float, y: Float): Boolean =
            x > left - MIN_GAP && x < right + MIN_GAP && y > top - MIN_GAP && y < bottom + MIN_GAP

        fun shiftedRight(dx: Float): Box = copy(left = left + dx, right = right + dx)

        override fun toString(): String =
            "$what[%.4f, %.4f, %.4f, %.4f]".format(left, top, right, bottom)
    }

    fun hx(point: MapPoint): Float = point.x * SLICE_ASPECT

    fun body(point: MapPoint, what: String = "body"): Box {
        val r = NODE_DIAMETER / 2f
        return Box(what, hx(point) - r, point.y - r, hx(point) + r, point.y + r + CROWN_ROW_HEIGHT)
    }

    fun marker(point: MapPoint, what: String = "marker"): Box {
        val half = MARKER_SIZE / 2f
        val top = point.y - NODE_DIAMETER / 2f - MARKER_SIZE
        return Box(what, hx(point) - half, top, hx(point) + half, top + MARKER_SIZE)
    }

    fun gate(point: MapPoint): List<Box> {
        val g = GATE_DIAMETER / 2f
        val bandTop = point.y + g
        return listOf(
            Box("gate", hx(point) - g, point.y - g, hx(point) + g, point.y + g),
            Box(
                "gate band",
                hx(point) - GATE_LABEL_WIDTH / 2f,
                bandTop,
                hx(point) + GATE_LABEL_WIDTH / 2f,
                bandTop + GATE_LABEL_HEIGHT,
            ),
        )
    }

    fun sweetRoom(point: MapPoint): Box {
        val s = SWEET_ROOM_DIAMETER / 2f
        val bottom = point.y + s + CROWN_ROW_HEIGHT
        return Box("sweet room", hx(point) - s, point.y - s, hx(point) + s, bottom)
    }

    /** Every footprint of [layout], nodes first. */
    fun all(layout: SliceLayout): List<Box> =
        layout.nodes.flatMapIndexed { i, p -> listOf(body(p, "body $i"), marker(p, "marker $i")) } +
            gate(layout.gate) + sweetRoom(layout.sweetRoom)

    /**
     * The brief's disjointness rules for one slice: bodies pairwise; every marker against every
     * other node's body; the gate and the Sweet Room against every body, every marker and each
     * other. (Two markers may overlap — only one is ever drawn.)
     */
    fun clashes(layout: SliceLayout): List<String> {
        val bodies = layout.nodes.mapIndexed { i, p -> body(p, "body $i") }
        val markers = layout.nodes.mapIndexed { i, p -> marker(p, "marker $i") }
        val fixtures = gate(layout.gate) + sweetRoom(layout.sweetRoom)
        val found = ArrayList<String>()
        for (i in bodies.indices) {
            for (j in bodies.indices) {
                if (i < j && bodies[i].clashesWith(bodies[j])) {
                    found += "${bodies[i]} x ${bodies[j]}"
                }
                if (i != j && markers[i].clashesWith(bodies[j])) {
                    found += "${markers[i]} x ${bodies[j]}"
                }
            }
        }
        for (fixture in fixtures) {
            for (box in bodies + markers) {
                if (fixture.clashesWith(box)) found += "$fixture x $box"
            }
        }
        val (badge, band) = gate(layout.gate)
        val room = sweetRoom(layout.sweetRoom)
        if (badge.clashesWith(room) || band.clashesWith(room)) found += "gate x $room"
        return found
    }

    /** Footprints outside the slice (less [SIDE_MARGIN]) or outside `SAFE_TOP..SAFE_BOTTOM`. */
    fun outOfBounds(layout: SliceLayout): List<String> = all(layout).filter { box ->
        box.left < SIDE_MARGIN || box.right > SLICE_ASPECT - SIDE_MARGIN ||
            box.top < SAFE_TOP || box.bottom > SAFE_BOTTOM
    }.map { it.toString() }

    /** Footprints of [left] against those of [right], placed one slice width further right. */
    fun seamClashes(left: SliceLayout, right: SliceLayout): List<String> {
        val shifted = all(right).map { it.shiftedRight(SLICE_ASPECT) }
        return all(left).flatMap { a ->
            shifted.filter { b -> a.clashesWith(b) }.map { b -> "$a x $b" }
        }
    }
}
