package app.krafted.candytriangle.ui.map

import app.krafted.candytriangle.level.CandyColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Every number the map UI decides, verified without a composable (`MapBackdrop.kt`'s pure half).
 *
 * The screen itself cannot run on this toolchain (D1/D2 note 17), so the things that would
 * otherwise only be eyeballed on a device live here: the backdrop is never decoded smaller than it
 * is drawn, the map opens with the current node centred and never scrolled past either end, every
 * box sits exactly where `MapLayout` reserves it, and a locked tap explains the right blocker.
 */
class MapBackdropTest {

    // -- decode sub-sampling -------------------------------------------------------------------

    @Test
    fun `sampleSize keeps full resolution when the slice is at least as big as the art`() {
        // A 1080 x 2400 phone lays a slice out 1350 x 2400 — larger than the 1080 x 1920 art.
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, 1350, 2400))
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, 1080, 1920))
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, 900, 1600))
    }

    @Test
    fun `sampleSize halves the art on a low-resolution screen`() {
        // 854 px tall: a half-size decode (540 x 960) still covers the 480 x 854 slice.
        assertEquals(2, MapBackdropMath.sampleSize(1080, 1920, 480, 854))
        assertEquals(4, MapBackdropMath.sampleSize(1080, 1920, 270, 480))
    }

    @Test
    fun `sampleSize never decodes below the laid-out size on either axis`() {
        var w = 40
        while (w <= 2000) {
            var h = 60
            while (h <= 3000) {
                val sample = MapBackdropMath.sampleSize(1080, 1920, w, h)
                assertTrue("not a power of two: $sample", sample > 0 && sample and (sample - 1) == 0)
                if (w <= 1080) assertTrue("width under-decoded at $w x $h", 1080 / sample >= w)
                if (h <= 1920) assertTrue("height under-decoded at $w x $h", 1920 / sample >= h)
                h += 173
            }
            w += 131
        }
    }

    @Test
    fun `sampleSize is limited by the tighter axis`() {
        // Wide and short: the height alone would allow 4, the width allows only 1.
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, 1000, 400))
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, 400, 1800))
    }

    @Test
    fun `sampleSize degrades to one before layout`() {
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, 0, 0))
        assertEquals(1, MapBackdropMath.sampleSize(1080, 1920, -5, 900))
        assertEquals(1, MapBackdropMath.sampleSize(0, 0, 540, 960))
    }

    @Test
    fun `sprite decode targets sub-sample the 400 px art`() {
        val crownPx = MapGeometry.crownSpritePx(2400)
        val sample = MapBackdropMath.sampleSize(400, 400, crownPx, crownPx)
        assertTrue("crown sprite decoded at full size", sample >= 2)
        assertTrue(400 / sample >= crownPx)

        val markerPx = MapGeometry.markerSpritePx(2400)
        val markerSample = MapBackdropMath.sampleSize(400, 374, markerPx, markerPx)
        assertTrue(400 / markerSample >= markerPx && 374 / markerSample >= markerPx)
    }

    @Test
    fun `sprite decode targets cover every place they are drawn, and never drop below the floor`() {
        for (height in intArrayOf(854, 1600, 2400, 3120)) {
            val crownPx = MapGeometry.crownSpritePx(height)
            assertTrue(crownPx >= MapGeometry.px(MapLayout.CROWN_ROW_HEIGHT, height))
            assertTrue(crownPx >= MapGeometry.px(MapGeometry.GATE_LABEL_HEIGHT, height) / 2)
            assertTrue(crownPx >= MapGeometry.MIN_SPRITE_PX)
            assertTrue(MapGeometry.markerSpritePx(height) >= MapGeometry.px(MapLayout.MARKER_SIZE, height))
        }
        assertEquals(MapGeometry.MIN_SPRITE_PX, MapGeometry.crownSpritePx(100))
        assertEquals(MapGeometry.MIN_SPRITE_PX, MapGeometry.crownSpritePx(0))
        assertEquals(MapGeometry.MIN_SPRITE_PX, MapGeometry.markerSpritePx(0))
    }

    // -- opening scroll --------------------------------------------------------------------------

    @Test
    fun `centreOn puts the node at the centre of the viewport`() {
        val target = MapScrollMath.centreOn(1, 0.5f, 4, 1350, 1080)
        val nodePx = 1350 + 675
        assertEquals(1, target.index)
        assertEquals(nodePx - 540, target.index * 1350 + target.offsetPx)
    }

    @Test
    fun `centreOn clamps at the left end of the panorama`() {
        assertEquals(MapScrollMath.START, MapScrollMath.centreOn(0, 0.1f, 4, 1350, 1080))
        assertEquals(MapScrollMath.START, MapScrollMath.centreOn(0, 0f, 4, 1350, 1080))
    }

    @Test
    fun `centreOn clamps at the right end of the panorama`() {
        val target = MapScrollMath.centreOn(3, 0.95f, 4, 1350, 1080)
        val scroll = target.index * 1350 + target.offsetPx
        // The viewport's right edge sits exactly on the panorama's right edge.
        assertEquals(4 * 1350, scroll + 1080)
        assertEquals(3, target.index)
        assertEquals(270, target.offsetPx)
    }

    @Test
    fun `centreOn always yields a valid scrollToItem target`() {
        for (viewport in intArrayOf(720, 1080, 1440, 1600)) {
            for (sliceWidth in intArrayOf(900, 1350, 1755)) {
                val maxScroll = (4 * sliceWidth - viewport).coerceAtLeast(0)
                for (slice in 0 until 4) {
                    var x = 0f
                    while (x <= 1f) {
                        val target = MapScrollMath.centreOn(slice, x, 4, sliceWidth, viewport)
                        val scroll = target.index * sliceWidth + target.offsetPx
                        val where = "slice $slice x $x in $viewport over $sliceWidth"
                        assertTrue(where, target.index in 0 until 4)
                        assertTrue(where, target.offsetPx >= 0)
                        assertTrue(where, scroll in 0..maxScroll)
                        val ideal = slice * sliceWidth + Math.round(x * sliceWidth) - viewport / 2
                        if (ideal in 0..maxScroll) assertEquals(where, ideal, scroll)
                        x += 0.05f
                    }
                }
            }
        }
    }

    @Test
    fun `centreOn stays at the start when the viewport is wider than the panorama`() {
        assertEquals(MapScrollMath.START, MapScrollMath.centreOn(3, 0.9f, 4, 200, 1000))
    }

    @Test
    fun `centreOn degrades rather than failing on unusable input`() {
        assertEquals(MapScrollMath.START, MapScrollMath.centreOn(2, 0.5f, 4, 0, 1080))
        assertEquals(MapScrollMath.START, MapScrollMath.centreOn(2, 0.5f, 4, 1350, 0))
        assertEquals(MapScrollMath.START, MapScrollMath.centreOn(0, 0.5f, 0, 1350, 1080))
        // A NaN x centres the slice; an out-of-range slice index is clamped onto the panorama.
        assertEquals(
            MapScrollMath.centreOn(1, 0.5f, 4, 1350, 1080),
            MapScrollMath.centreOn(1, Float.NaN, 4, 1350, 1080),
        )
        assertEquals(
            MapScrollMath.centreOn(3, 0.5f, 4, 1350, 1080),
            MapScrollMath.centreOn(9, 0.5f, 4, 1350, 1080),
        )
    }

    @Test
    fun `openingScroll centres the current level's own node`() {
        val state = map(currentLevelId = 14, currentSliceIndex = 1)
        val node = state.slices[1].levels.first { it.levelId == 14 }
        assertEquals(
            MapScrollMath.centreOn(1, node.position.x, 4, 1350, 1080),
            MapScrollMath.openingScroll(state, 1350, 1080),
        )
    }

    @Test
    fun `openingScroll falls back to the middle of the current slice`() {
        val state = map(currentLevelId = 99, currentSliceIndex = 2)
        assertEquals(
            MapScrollMath.centreOn(2, 0.5f, 4, 1350, 1080),
            MapScrollMath.openingScroll(state, 1350, 1080),
        )
    }

    // -- geometry --------------------------------------------------------------------------------

    @Test
    fun `a level node is a NODE_DIAMETER square centred on its point`() {
        val rect = MapGeometry.levelNode(MapPoint(0.25f, 0.5f), 1350, 2400)
        assertEquals(MapGeometry.px(MapLayout.NODE_DIAMETER, 2400), rect.width)
        assertEquals(rect.width, rect.height)
        assertEquals(0.25f * 1350, rect.centreX, 0.5f)
        assertEquals(0.5f * 2400, rect.centreY, 0.5f)
    }

    @Test
    fun `the crown row sits directly below its node and no wider than it`() {
        for (point in samplePoints) {
            val node = MapGeometry.levelNode(point, 1350, 2400)
            val row = MapGeometry.crownRow(node, 2400)
            assertEquals(node.bottom, row.top)
            assertEquals(MapGeometry.px(MapLayout.CROWN_ROW_HEIGHT, 2400), row.height)
            assertEquals(node.width, row.width)
            assertEquals(node.centreX, row.centreX, 0.5f)
        }
    }

    @Test
    fun `the marker square sits directly above the current node`() {
        for (point in samplePoints) {
            val node = MapGeometry.levelNode(point, 1350, 2400)
            val marker = MapGeometry.marker(node, 2400)
            assertEquals(node.top, marker.bottom)
            assertEquals(MapGeometry.px(MapLayout.MARKER_SIZE, 2400), marker.width)
            assertEquals(marker.width, marker.height)
            assertEquals(node.centreX, marker.centreX, 0.5f)
        }
    }

    @Test
    fun `the gate band is two gate diameters wide and sits directly below the badge`() {
        val gate = MapGeometry.gate(MapPoint(0.5f, 0.4f), 1350, 2400)
        val band = MapGeometry.gateLabel(gate, 2400)
        assertEquals(MapGeometry.px(MapLayout.GATE_DIAMETER, 2400), gate.width)
        assertEquals(gate.bottom, band.top)
        assertEquals(MapGeometry.px(2 * MapLayout.GATE_DIAMETER, 2400), band.width)
        assertEquals(MapGeometry.px(0.045f, 2400), band.height)
        assertEquals(gate.centreX, band.centreX, 0.5f)
    }

    @Test
    fun `the Sweet Room band sits directly below the room`() {
        val room = MapGeometry.sweetRoom(MapPoint(0.5f, 0.3f), 1350, 2400)
        val band = MapGeometry.sweetRoomBand(room, 2400)
        assertEquals(MapGeometry.px(MapLayout.SWEET_ROOM_DIAMETER, 2400), room.width)
        assertEquals(room.bottom, band.top)
        assertEquals(room.width, band.width)
        assertEquals(MapGeometry.px(MapLayout.CROWN_ROW_HEIGHT, 2400), band.height)
    }

    @Test
    fun `the world banner stays inside the band below SAFE_BOTTOM`() {
        for (height in intArrayOf(640, 854, 1600, 2400, 3120)) {
            val width = Math.round(height * MapLayout.SLICE_ASPECT)
            val banner = MapGeometry.banner(width, height)
            assertTrue("banner above SAFE_BOTTOM at $height", banner.top >= MapGeometry.px(MapLayout.SAFE_BOTTOM, height))
            assertTrue("banner off the slice at $height", banner.bottom <= height)
            assertTrue("empty banner at $height", banner.height > 0)
            assertEquals(0, banner.left)
            assertEquals(width, banner.width)
        }
    }

    @Test
    fun `sizes scale with slice height and positions with slice size`() {
        val point = MapPoint(0.73f, 0.41f)
        val small = MapGeometry.levelNode(point, 900, 1600)
        val large = MapGeometry.levelNode(point, 1800, 3200)
        assertEquals(2f * small.width, large.width.toFloat(), 1f)
        assertEquals(2f * small.centreX, large.centreX, 1f)
        assertEquals(2f * small.centreY, large.centreY, 1f)
    }

    @Test
    fun `centred tolerates a non-finite point`() {
        val rect = MapGeometry.centred(MapPoint(Float.NaN, Float.POSITIVE_INFINITY), 0.062f, 1350, 2400)
        assertEquals(MapGeometry.px(0.062f, 2400), rect.width)
        assertEquals(-rect.width / 2f, rect.left.toFloat(), 0.5f)
    }

    @Test
    fun `crown slots are three equal squares inside the row, in order, without overlap`() {
        for (height in intArrayOf(854, 1600, 2400, 3120)) {
            val node = MapGeometry.levelNode(MapPoint(0.5f, 0.5f), 1350, height)
            val row = MapGeometry.crownRow(node, height)
            val slots = MapGeometry.crownSlots(row)
            assertEquals(3, slots.size)
            for (slot in slots) {
                assertEquals(slots[0].width, slot.width)
                assertEquals(slot.width, slot.height)
                assertTrue("slot outside the row at $height", slot.left >= row.left && slot.right <= row.right)
                assertTrue("slot outside the row at $height", slot.top >= row.top && slot.bottom <= row.bottom)
            }
            assertTrue(slots[0].right <= slots[1].left && slots[1].right <= slots[2].left)
            assertEquals(row.centreX, (slots[0].left + slots[2].right) / 2f, 1f)
        }
    }

    @Test
    fun `crown slots shrink to fit a narrow row and vanish in an empty one`() {
        val narrow = MapGeometry.crownSlots(PxRect(10, 10, 30, 20))
        assertEquals(3, narrow.size)
        assertTrue(narrow.all { it.width < 20 && it.left >= 10 && it.right <= 40 })
        assertTrue(MapGeometry.crownSlots(PxRect(0, 0, 0, 20)).isEmpty())
        assertTrue(MapGeometry.crownSlots(PxRect(0, 0, 30, 0)).isEmpty())
        assertTrue(MapGeometry.crownSlots(PxRect(0, 0, 2, 20)).isEmpty())
    }

    // -- the dotted path -------------------------------------------------------------------------

    @Test
    fun `each path stretch is lit by the node it leads into`() {
        val levels = listOf(
            node(1, NodeStatus.CLEARED, x = 0.3f),
            node(2, NodeStatus.AVAILABLE, x = 0.5f),
            node(3, NodeStatus.LOCKED, x = 0.7f),
        )
        val slice = slice(world = 1, levels = levels)
        assertEquals(listOf(true, true, false, false), MapPathMath.litSegments(slice, exitLit = false))
        assertEquals(listOf(true, true, false, true), MapPathMath.litSegments(slice, exitLit = true))
    }

    @Test
    fun `a path that does not match its nodes is lit or dimmed with its world`() {
        val levels = listOf(node(11, NodeStatus.AVAILABLE), node(12, NodeStatus.LOCKED))
        val shortPath = listOf(MapPoint(0f, 0.5f), MapPoint(1f, 0.5f))
        assertEquals(listOf(true), MapPathMath.litSegments(slice(2, levels, unlocked = true, path = shortPath), false))
        assertEquals(listOf(false), MapPathMath.litSegments(slice(2, levels, unlocked = false, path = shortPath), true))
        assertTrue(MapPathMath.litSegments(slice(2, levels, path = listOf(MapPoint(0f, 0f))), true).isEmpty())
    }

    @Test
    fun `a slice's exit stretch matches the next slice's entry stretch`() {
        val open = map(currentLevelId = 11, currentSliceIndex = 1, frontier = 11)
        assertTrue(MapPathMath.exitLit(open, 0))
        assertEquals(MapPathMath.litSegments(open.slices[1], false).first(), MapPathMath.exitLit(open, 0))

        val shut = map(currentLevelId = 10, currentSliceIndex = 0, frontier = 10)
        assertFalse(MapPathMath.exitLit(shut, 0))
        assertEquals(MapPathMath.litSegments(shut.slices[1], false).first(), MapPathMath.exitLit(shut, 0))
    }

    @Test
    fun `the last slice's exit is lit only once its last level is cleared`() {
        assertFalse(MapPathMath.exitLit(map(currentLevelId = 40, currentSliceIndex = 3, frontier = 40), 3))
        assertTrue(MapPathMath.exitLit(map(currentLevelId = 40, currentSliceIndex = 3, frontier = 41), 3))
    }

    @Test
    fun `dots are evenly spaced along a straight path`() {
        val dots = MapPathMath.dots(listOf(MapPoint(0f, 0.5f), MapPoint(1f, 0.5f)), 1000f, 1000f, 100f, 0f)
        assertEquals(10, dots.size)
        dots.forEachIndexed { i, dot ->
            assertEquals(i * 100f, dot.x, 1e-3f)
            assertEquals(500f, dot.y, 1e-3f)
            assertEquals(0, dot.segment)
        }
    }

    @Test
    fun `dots carry their rhythm across a corner`() {
        val path = listOf(MapPoint(0f, 0f), MapPoint(0.5f, 0f), MapPoint(0.5f, 0.5f))
        val dots = MapPathMath.dots(path, 1000f, 1000f, 300f, 0f)
        // 0 and 300 on the first leg; the next falls 100 into the second leg, then 400.
        assertEquals(4, dots.size)
        assertEquals(listOf(0, 0, 1, 1), dots.map { it.segment })
        assertEquals(500f, dots[2].x, 1e-3f)
        assertEquals(100f, dots[2].y, 1e-3f)
        assertEquals(400f, dots[3].y, 1e-3f)
    }

    @Test
    fun `dots clear the node discs but not the slice edges`() {
        val path = listOf(MapPoint(0f, 0.5f), MapPoint(0.5f, 0.5f), MapPoint(1f, 0.5f))
        val dots = MapPathMath.dots(path, 1000f, 1000f, 20f, 60f)
        assertTrue(dots.none { hypot(it.x - 500f, it.y - 500f) < 60f })
        assertTrue("the entry on the slice edge was cleared", dots.any { abs(it.x) < 1e-3f })
        // The rhythm is kept: the dots either side of the gap are still on the 20 px grid.
        assertTrue(dots.all { abs(it.x / 20f - Math.round(it.x / 20f)) < 1e-3f })
    }

    @Test
    fun `dots degrade to nothing on a degenerate path`() {
        val line = listOf(MapPoint(0f, 0.5f), MapPoint(1f, 0.5f))
        assertTrue(MapPathMath.dots(listOf(MapPoint(0f, 0f)), 1000f, 1000f, 10f, 0f).isEmpty())
        assertTrue(MapPathMath.dots(line, 0f, 1000f, 10f, 0f).isEmpty())
        assertTrue(MapPathMath.dots(line, 1000f, 1000f, 0f, 0f).isEmpty())
        assertTrue(MapPathMath.dots(line, 1000f, 1000f, Float.NaN, 0f).isEmpty())
    }

    @Test
    fun `dots survive a zero-length or non-finite segment`() {
        val repeated = listOf(MapPoint(0f, 0.5f), MapPoint(0.5f, 0.5f), MapPoint(0.5f, 0.5f), MapPoint(1f, 0.5f))
        val dots = MapPathMath.dots(repeated, 1000f, 1000f, 100f, 0f)
        assertEquals(10, dots.size)
        assertTrue(dots.all { it.x.isFinite() && it.y.isFinite() })

        val broken = listOf(MapPoint(0f, 0.5f), MapPoint(Float.NaN, 0.5f), MapPoint(1f, 0.5f))
        assertTrue(MapPathMath.dots(broken, 1000f, 1000f, 100f, 0f).all { it.x.isFinite() && it.y.isFinite() })
    }

    // -- locked-tap hints ------------------------------------------------------------------------

    @Test
    fun `a playable node explains nothing`() {
        val slice = slice(1, listOf(node(1, NodeStatus.CLEARED), node(2, NodeStatus.AVAILABLE)))
        assertNull(MapHints.forLevel(slice, slice.levels[0]))
        assertNull(MapHints.forLevel(slice, slice.levels[1]))
    }

    @Test
    fun `a locked node in an open world asks for the previous level`() {
        val slice = slice(2, listOf(node(11, NodeStatus.AVAILABLE), node(12, NodeStatus.LOCKED)))
        assertEquals(MapHint.ClearPrevious(11), MapHints.forLevel(slice, slice.levels[1]))
    }

    @Test
    fun `a locked node in a shut world explains the gate`() {
        val gate = gate(levelCleared = false, crownsHave = 12)
        val slice = slice(2, listOf(node(11, NodeStatus.LOCKED), node(15, NodeStatus.LOCKED)), unlocked = false, gate = gate)
        assertEquals(MapHint.OpenGate(world = 2, levelToClear = 10, crownsShort = 3), MapHints.forLevel(slice, slice.levels[1]))
    }

    @Test
    fun `the gate hint leaves out the half that is already met`() {
        assertEquals(
            MapHint.OpenGate(world = 2, levelToClear = null, crownsShort = 3),
            MapHints.forGate(gate(levelCleared = true, crownsHave = 12)),
        )
        assertEquals(
            MapHint.OpenGate(world = 2, levelToClear = 10, crownsShort = 0),
            MapHints.forGate(gate(levelCleared = false, crownsHave = 21)),
        )
    }

    @Test
    fun `an open gate, or one whose halves are both met, explains nothing`() {
        assertNull(MapHints.forGate(gate(levelCleared = true, crownsHave = 20, open = true)))
        assertNull(MapHints.forGate(gate(levelCleared = true, crownsHave = 15, open = false)))
        // A shut world whose gate has nothing left to ask falls back to the previous level.
        val slice = slice(2, listOf(node(12, NodeStatus.LOCKED)), unlocked = false, gate = gate(levelCleared = true, crownsHave = 15))
        assertEquals(MapHint.ClearPrevious(11), MapHints.forLevel(slice, slice.levels[0]))
    }

    @Test
    fun `level one never asks for a level zero`() {
        val slice = slice(1, listOf(node(1, NodeStatus.LOCKED)))
        assertNull(MapHints.forLevel(slice, slice.levels[0]))
    }

    @Test
    fun `a locked Sweet Room reports how far its jar is from tier 3`() {
        assertEquals(
            MapHint.FillJar(code = "B1", jarColor = CandyColor.GREEN, candiesShort = 57),
            MapHints.forSweetRoom(room(NodeStatus.LOCKED, jarCount = 143, jarThreshold = 200)),
        )
    }

    @Test
    fun `a playable Sweet Room explains nothing, and a full jar never claims zero short`() {
        assertNull(MapHints.forSweetRoom(room(NodeStatus.AVAILABLE, jarCount = 212, jarThreshold = 200)))
        assertNull(MapHints.forSweetRoom(room(NodeStatus.CLEARED, jarCount = 212, jarThreshold = 200)))
        assertNull(MapHints.forSweetRoom(room(NodeStatus.LOCKED, jarCount = 200, jarThreshold = 200)))
    }

    // -- fixtures --------------------------------------------------------------------------------

    private val samplePoints = listOf(
        MapPoint(0.2f, 0.3f),
        MapPoint(0.5f, 0.5f),
        MapPoint(0.8f, 0.77f),
        MapPoint(0.37f, 0.61f),
    )

    private fun node(
        id: Int,
        status: NodeStatus,
        x: Float = 0.5f,
        y: Float = 0.5f,
        current: Boolean = false,
    ) = LevelNodeUiState(
        levelId = id,
        status = status,
        crowns = if (status == NodeStatus.CLEARED) 2 else 0,
        bestScore = 0,
        isCurrent = current,
        position = MapPoint(x, y),
    )

    private fun slice(
        world: Int,
        levels: List<LevelNodeUiState>,
        unlocked: Boolean = true,
        gate: GateUiState? = null,
        path: List<MapPoint> = listOf(MapPoint(0f, 0.5f)) + levels.map { it.position } + MapPoint(1f, 0.5f),
    ) = WorldSliceUiState(
        world = world,
        levelFrom = levels.first().levelId,
        levelTo = levels.last().levelId,
        pegTintArgb = 0xFFFF4FC8.toInt(),
        unlocked = unlocked,
        crownsEarned = 0,
        crownsAvailable = 3 * levels.size,
        gate = gate,
        levels = levels,
        sweetRoom = null,
        path = path,
    )

    private fun gate(
        levelCleared: Boolean,
        crownsHave: Int,
        open: Boolean = false,
    ) = GateUiState(
        world = 2,
        requiresLevelCleared = 10,
        levelCleared = levelCleared,
        crownsRequired = 15,
        crownsHave = crownsHave,
        open = open,
        position = MapPoint(0.5f, 0.5f),
    )

    private fun room(status: NodeStatus, jarCount: Int, jarThreshold: Int) = SweetRoomNodeUiState(
        levelId = 101,
        code = "B1",
        status = status,
        crowns = 0,
        bestScore = 0,
        jarColor = CandyColor.GREEN,
        jarCount = jarCount,
        jarThreshold = jarThreshold,
        position = MapPoint(0.5f, 0.3f),
    )

    /**
     * Four worlds of ten: levels below [frontier] cleared, [frontier] itself available, everything
     * after it locked. A world is open once the frontier has reached it, so `frontier = 10` leaves
     * World 2 shut and `frontier = 11` opens it.
     */
    private fun map(currentLevelId: Int, currentSliceIndex: Int, frontier: Int = 1): LevelMapUiState {
        val slices = (1..4).map { world ->
            val first = (world - 1) * 10 + 1
            val worldOpen = world == 1 || frontier >= first
            val levels = (first until first + 10).mapIndexed { i, id ->
                val status = when {
                    id < frontier -> NodeStatus.CLEARED
                    id == frontier && worldOpen -> NodeStatus.AVAILABLE
                    else -> NodeStatus.LOCKED
                }
                node(id, status, x = if (i % 2 == 0) 0.27f else 0.73f, y = 0.8f - i * 0.06f)
            }
            slice(world, levels, unlocked = worldOpen)
        }
        return LevelMapUiState(
            slices = slices,
            totalCrowns = 0,
            maxCrowns = 120,
            currentLevelId = currentLevelId,
            currentSliceIndex = currentSliceIndex,
        )
    }
}
