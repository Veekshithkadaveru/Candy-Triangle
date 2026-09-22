package app.krafted.candytriangle.engine

import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.WallLine
import app.krafted.candytriangle.level.WallsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The two §3.1 side walls and the §3.3 circle-segment overlap test.
 *
 * Pure geometry on the JVM: no world, no step, no emulator. Expected values are derived here, in
 * double, from the §3.1 numbers alone — a 1000 x 1250 triangle with its apex at (500, 0) — never
 * read back from [WallSegment], so a wrong normal or a wrong region split cannot agree with itself.
 */
class WallSegmentTest {

    private val board = GameConfig.DEFAULTS.board
    private val walls = WallSegment.boardWalls(board, WALL_RESTITUTION)
    private val left = walls[0]
    private val right = walls[1]

    // -- boardWalls: §3.1 geometry --------------------------------------------------------------

    @Test
    fun boardWallsAreLeftThenRightWithTheSection31Endpoints() {
        assertEquals("exactly the two slanted walls; the base is open", 2, walls.size)
        assertEndpoints("left wall", left, 500f, 0f, 0f, 1250f)
        assertEndpoints("right wall", right, 500f, 0f, 1000f, 1250f)
    }

    @Test
    fun inwardNormalsAreUnitPerpendicularAndFaceTheBoard() {
        // (1250, 500) / |W| and its mirror image, derived by hand from the §3.1 endpoints.
        assertD("left nx", 1250.0 / WALL_LENGTH, left.nx)
        assertD("left ny", 500.0 / WALL_LENGTH, left.ny)
        assertD("right nx", -1250.0 / WALL_LENGTH, right.nx)
        assertD("right ny", 500.0 / WALL_LENGTH, right.ny)
        assertEquals("left nx to 5 places", 0.92848, left.nx.toDouble(), 1e-5)
        assertEquals("left ny to 5 places", 0.37139, left.ny.toDouble(), 1e-5)

        for (wall in walls) {
            val length = hypot(wall.nx.toDouble(), wall.ny.toDouble())
            assertEquals("$wall: unit length", 1.0, length, DELTA)
            val wx = (wall.x2 - wall.x1).toDouble()
            val wy = (wall.y2 - wall.y1).toDouble()
            val cosine = (wx * wall.nx + wy * wall.ny) / WALL_LENGTH
            assertEquals("$wall: square to the segment", 0.0, cosine, DELTA)
            assertTrue(
                "$wall: must face the centroid (500, 833.3)",
                (CENTROID_X - wall.x1) * wall.nx + (CENTROID_Y - wall.y1) * wall.ny > 0.0,
            )
        }
    }

    @Test
    fun wallsSlant21Point8DegreesFromVertical() {
        for (wall in walls) {
            val fromVertical = Math.toDegrees(
                atan2(abs(wall.x2 - wall.x1).toDouble(), (wall.y2 - wall.y1).toDouble()),
            )
            assertEquals(
                "$wall: the configured §3.1 slant",
                board.walls.slantDegreesFromVertical.toDouble(),
                fromVertical,
                0.01,
            )
            assertEquals("$wall: atan(500 / 1250)", SLANT_DEGREES, fromVertical, 1e-9)
            // Square to the wall, the inward normal sits at the same angle to the horizontal.
            val normalFromHorizontal =
                Math.toDegrees(atan2(wall.ny.toDouble(), abs(wall.nx.toDouble())))
            assertEquals("$wall: normal angle", SLANT_DEGREES, normalFromHorizontal, 1e-4)
        }
    }

    @Test
    fun restitutionIsPassedThroughToBothWalls() {
        for (e in floatArrayOf(0.5f, 0f, 0.37f, 1f)) {
            val built = WallSegment.boardWalls(board, e)
            assertEquals("left restitution for e = $e", e, built[0].restitution, 0f)
            assertEquals("right restitution for e = $e", e, built[1].restitution, 0f)
        }
    }

    // -- boardWalls: a bad config degrades, never throws ----------------------------------------

    @Test
    fun aZeroLengthConfiguredWallFallsBackToTheDefault() {
        val config = WallsConfig(
            left = WallLine(500f, 0f, 500f, 0f),
            right = WallLine(500f, 0f, 990f, 1250f),
        )
        val built = WallSegment.boardWalls(board.copy(walls = config), WALL_RESTITUTION)

        assertEndpoints("zero-length left becomes the §3.1 default", built[0], 500f, 0f, 0f, 1250f)
        assertEndpoints("a usable right wall is kept", built[1], 500f, 0f, 990f, 1250f)
        assertTrue("the kept wall still faces inward", built[1].nx < 0f && built[1].ny > 0f)
    }

    @Test
    fun nonFiniteConfiguredWallsFallBackToTheDefaults() {
        val broken = listOf(
            WallLine(Float.NaN, 0f, 0f, 1250f),
            WallLine(500f, 0f, Float.POSITIVE_INFINITY, 1250f),
            WallLine(500f, Float.NEGATIVE_INFINITY, 0f, 1250f),
            // Every coordinate is finite, but the length overflows a float.
            WallLine(-3e38f, 0f, 3e38f, 1250f),
        )
        for (bad in broken) {
            val config = board.copy(walls = WallsConfig(left = bad, right = bad))
            val built = WallSegment.boardWalls(config, WALL_RESTITUTION)

            assertEndpoints("left wall configured as $bad", built[0], 500f, 0f, 0f, 1250f)
            assertEndpoints("right wall configured as $bad", built[1], 500f, 0f, 1000f, 1250f)
            assertEquals("left normal for $bad", left.nx, built[0].nx, 0f)
            assertEquals("right normal for $bad", right.nx, built[1].nx, 0f)
        }
    }

    @Test
    fun collinearConfiguredWallsFallBackToTheDefaultsInsteadOfThrowing() {
        // Each is a fine segment on its own, but both lie on y = 0: no triangle, so no side is
        // "inside". The constructor would reject that; boardWalls must not let it get there.
        val flat = WallsConfig(
            left = WallLine(0f, 0f, 1000f, 0f),
            right = WallLine(0f, 0f, 500f, 0f),
        )
        val built = WallSegment.boardWalls(board.copy(walls = flat), WALL_RESTITUTION)

        assertEndpoints("left", built[0], 500f, 0f, 0f, 1250f)
        assertEndpoints("right", built[1], 500f, 0f, 1000f, 1250f)
    }

    // -- constructor ----------------------------------------------------------------------------

    @Test
    fun theConstructorRejectsASegmentWithNoDefinedInside() {
        val e = WALL_RESTITUTION
        val iae = IllegalArgumentException::class.java
        assertThrows("zero length", iae) { WallSegment(10f, 10f, 10f, 10f, e, 0f, 0f) }
        assertThrows("NaN endpoint", iae) { WallSegment(Float.NaN, 0f, 10f, 0f, e, 5f, 5f) }
        assertThrows("interior on the segment", iae) { WallSegment(0f, 0f, 10f, 0f, e, 5f, 0f) }
        assertThrows("interior on the line past the segment", iae) {
            WallSegment(0f, 0f, 10f, 0f, e, 50f, 0f)
        }
        assertThrows("NaN interior point", iae) { WallSegment(0f, 0f, 10f, 0f, e, Float.NaN, 5f) }
    }

    @Test
    fun theNormalFacesWhicheverSideTheInteriorPointIsOn() {
        val below = WallSegment(0f, 0f, 10f, 0f, WALL_RESTITUTION, 5f, 3f)
        assertD("interior below (+y): nx", 0.0, below.nx)
        assertD("interior below (+y): ny", 1.0, below.ny)

        val above = WallSegment(0f, 0f, 10f, 0f, WALL_RESTITUTION, 5f, -3f)
        assertD("interior above (-y): nx", 0.0, above.nx)
        assertD("interior above (-y): ny", -1.0, above.ny)

        // Which end is p1 does not matter; only the interior point does.
        val reversed = WallSegment(10f, 0f, 0f, 0f, WALL_RESTITUTION, 5f, 3f)
        assertD("reversed endpoints: ny", 1.0, reversed.ny)

        // A point far beyond the segment's ends still only picks a side of the line.
        val farAlong = WallSegment(0f, 0f, 10f, 0f, WALL_RESTITUTION, 500f, 1f)
        assertD("interior far along the line: ny", 1.0, farAlong.ny)
    }

    // -- contact: alongside a wall --------------------------------------------------------------

    @Test
    fun aBallMidBoardTouchesNeitherWall() {
        val clear = listOf(500f to 600f, 500f to 200f, 300f to 900f, 700f to 900f, 500f to 1200f)
        for ((x, y) in clear) {
            assertNoContact("left wall, ball at ($x, $y)", left, x, y)
            assertNoContact("right wall, ball at ($x, $y)", right, x, y)
        }
    }

    @Test
    fun onTheAxisABallClearsBothWallsOnlyBelowAbout43Units() {
        // 16 / sin(21.8 deg): how deep a 16 u ball must be before it fits between the walls.
        val clearY = BALL_R / (500.0 / WALL_LENGTH)
        assertEquals("16 / sin(21.8 deg)", 43.08, clearY, 0.01)

        val justClear = (clearY + 0.01).toFloat()
        assertNoContact("left, just below the clearance depth", left, 500f, justClear)
        assertNoContact("right, just below the clearance depth", right, 500f, justClear)

        val justShort = (clearY - 0.01).toFloat()
        for (wall in walls) {
            val hit = contactOf(wall, 500f, justShort)
            assertEquals(
                "$wall: 0.01 u short of clear overlaps by 0.01 sin(21.8 deg)",
                0.01 * 500.0 / WALL_LENGTH,
                hit.penetration.toDouble(),
                1e-5,
            )
        }

        // PhysicsParams' spawn point relies on exactly this: it must be clear of both walls.
        val spawn = PhysicsParams()
        assertNoContact("left, launcher spawn point", left, spawn.spawnX, spawn.spawnY)
        assertNoContact("right, launcher spawn point", right, spawn.spawnX, spawn.spawnY)
    }

    @Test
    fun overlappingAlongsideAWallPushesAlongItsInwardNormal() {
        for (wall in walls) {
            for (t in doubleArrayOf(0.1, 0.5, 0.9)) {
                val (x, y) = pointAt(wall, t, s = 10.0)
                val hit = contactOf(wall, x, y)
                assertEquals("$wall t=$t: normal is the inward normal", wall.nx, hit.nx, 0f)
                assertEquals("$wall t=$t: normal is the inward normal", wall.ny, hit.ny, 0f)
                val penetration = hit.penetration.toDouble()
                assertEquals("$wall t=$t: penetration = r - s", 6.0, penetration, 1e-4)

                val (clearX, clearY) = pointAt(wall, t, s = 16.01)
                assertNoContact("$wall t=$t, 16.01 u inside", wall, clearX, clearY)
            }
        }
    }

    @Test
    fun aCentreThatCrossedTheLineIsPushedBackInside() {
        for (wall in walls) {
            for (s in doubleArrayOf(0.0, -3.0, -6.67, -15.0)) {
                val (x, y) = pointAt(wall, t = 0.6, s = s)
                val hit = contactOf(wall, x, y)
                assertEquals("$wall s=$s: pushed along the inward normal", wall.nx, hit.nx, 0f)
                assertEquals("$wall s=$s: pushed along the inward normal", wall.ny, hit.ny, 0f)
                val penetration = hit.penetration.toDouble()
                assertEquals("$wall s=$s: penetration = r - s", 16.0 - s, penetration, 1e-4)

                // Undoing the penetration lands the centre exactly one radius inside the line.
                val backX = x + hit.nx * penetration
                val backY = y + hit.ny * penetration
                val back = signedDistance(wall, backX, backY)
                assertEquals("$wall s=$s: back inside", 16.0, back, 1e-4)
            }
        }
    }

    // -- contact: the ends of a wall ------------------------------------------------------------

    @Test
    fun pastABaseCornerTheCornerIsASolidPoint() {
        // (5, 1262) projects past the left wall's lower end, 13 u from the (0, 1250) corner — a
        // 5-12-13 triangle. The side test alone would have said 6.9 u deep along the wall normal.
        assertTrue("(5, 1262) must project past the end", projectionT(left, 5.0, 1262.0) > 1.0)
        val leftCorner = contactOf(left, 5f, 1262f)
        assertD("left corner: radial nx", 5.0 / 13.0, leftCorner.nx)
        assertD("left corner: radial ny", 12.0 / 13.0, leftCorner.ny)
        assertEquals("left corner: r - d", 3.0, leftCorner.penetration.toDouble(), 1e-5)

        // The mirror image at the right corner.
        val rightCorner = contactOf(right, 995f, 1262f)
        assertD("right corner: radial nx", -5.0 / 13.0, rightCorner.nx)
        assertD("right corner: radial ny", 12.0 / 13.0, rightCorner.ny)
        assertEquals("right corner: r - d", 3.0, rightCorner.penetration.toDouble(), 1e-5)
    }

    @Test
    fun belowTheOpenBaseAwayFromTheCornersNothingIsSolid() {
        val open = listOf(
            500f to 1250f,
            500f to 1300f,
            500f to 1346f,
            250f to 1270f,
            750f to 1290f,
            40f to 1275f,
            960f to 1275f,
        )
        for ((x, y) in open) {
            assertNoContact("left wall, ball at ($x, $y)", left, x, y)
            assertNoContact("right wall, ball at ($x, $y)", right, x, y)
        }
    }

    @Test
    fun nearTheApexABallOverlapsBothWalls() {
        val expected = 16.0 - 30.0 * 500.0 / WALL_LENGTH // r - s, with s = y sin(21.8 deg)
        val onLeft = contactOf(left, 500f, 30f)
        val onRight = contactOf(right, 500f, 30f)

        assertEquals("left penetration", expected, onLeft.penetration.toDouble(), 1e-5)
        assertEquals("right penetration", expected, onRight.penetration.toDouble(), 1e-5)
        assertEquals("left pushes along its inward normal", left.nx, onLeft.nx, 0f)
        assertEquals("right pushes along its inward normal", right.nx, onRight.nx, 0f)
        // Mirror images: the sideways pushes cancel and both push down, away from the apex.
        assertEquals("sideways pushes cancel", -onLeft.nx, onRight.nx, 0f)
        assertEquals("both push down equally", onLeft.ny, onRight.ny, 0f)
    }

    @Test
    fun theTwoRegionsAgreeWhereTheyMeet() {
        // A centre 10 u inside the line, 0.01 u either side of each end: the side test and the
        // endpoint test must give the same answer there, or a ball sliding off a corner would jolt.
        for (wall in walls) {
            for (end in doubleArrayOf(0.0, 1.0)) {
                for (along in doubleArrayOf(-0.01, 0.01)) {
                    val (x, y) = pointAt(wall, end + along / WALL_LENGTH, s = 10.0)
                    val hit = contactOf(wall, x, y)
                    val where = "$wall, end t=$end, ${along}u along"
                    assertEquals("$where: penetration", 6.0, hit.penetration.toDouble(), 1e-4)
                    assertEquals("$where: nx", wall.nx, hit.nx, 2e-3f)
                    assertEquals("$where: ny", wall.ny, hit.ny, 2e-3f)
                }
            }
        }
    }

    /**
     * The documented contract, evaluated in double on a grid over and around the whole board, for
     * both walls: hit or miss, normal and penetration must all match. Grid points within 1e-3 u of
     * the overlap boundary, or on the seam between the two regions, are skipped — float and double
     * may legitimately disagree there, and [theTwoRegionsAgreeWhereTheyMeet] covers the seam.
     */
    @Test
    fun contactMatchesADoublePrecisionReferenceAcrossTheBoard() {
        var hits = 0
        var misses = 0
        for (wall in walls) {
            val (refNx, refNy) = referenceNormal(wall)
            val x1 = wall.x1.toDouble()
            val y1 = wall.y1.toDouble()
            var gy = -40.0
            while (gy <= 1300.0) {
                var gx = -40.0
                while (gx <= 1040.0) {
                    val fx = gx.toFloat()
                    val fy = gy.toFloat()
                    val cx = fx.toDouble()
                    val cy = fy.toDouble()
                    val t = projectionT(wall, cx, cy)

                    val expectedNx: Double
                    val expectedNy: Double
                    val margin: Double // r - s alongside, r - d past an end; > 0 means overlap
                    if (t in 0.0..1.0) {
                        expectedNx = refNx
                        expectedNy = refNy
                        margin = BALL_R - ((cx - x1) * refNx + (cy - y1) * refNy)
                    } else {
                        val ex = if (t < 0.0) cx - x1 else cx - wall.x2
                        val ey = if (t < 0.0) cy - y1 else cy - wall.y2
                        val d = hypot(ex, ey)
                        expectedNx = ex / d
                        expectedNy = ey / d
                        margin = BALL_R - d
                    }

                    val onBoundary = abs(margin) < 1e-3 || abs(t) < 1e-5 || abs(t - 1.0) < 1e-5
                    if (!onBoundary) {
                        val out = Contact()
                        val hit = wall.contact(fx, fy, BALL_R, out)
                        val where = "$wall, ball at ($fx, $fy)"
                        assertEquals("$where: overlap", margin > 0.0, hit)
                        if (hit) {
                            hits++
                            assertEquals("$where: nx", expectedNx, out.nx.toDouble(), 1e-5)
                            assertEquals("$where: ny", expectedNy, out.ny.toDouble(), 1e-5)
                            val penetration = out.penetration.toDouble()
                            assertEquals("$where: penetration", margin, penetration, 5e-4)
                        } else {
                            misses++
                        }
                    }
                    gx += 3.7
                }
                gy += 4.3
            }
        }
        assertTrue("the sweep must exercise overlaps, got $hits", hits > 1000)
        assertTrue("the sweep must exercise misses, got $misses", misses > 1000)
    }

    @Test
    fun aNonFiniteCentreNeverReportsAContact() {
        val bad = listOf(
            Float.NaN to 600f,
            500f to Float.NaN,
            Float.POSITIVE_INFINITY to 600f,
            Float.NEGATIVE_INFINITY to 600f,
            500f to Float.POSITIVE_INFINITY,
            500f to Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY to Float.NEGATIVE_INFINITY,
        )
        for ((x, y) in bad) {
            assertNoContact("left wall, ball at ($x, $y)", left, x, y)
            assertNoContact("right wall, ball at ($x, $y)", right, x, y)
        }
    }

    // -- helpers --------------------------------------------------------------------------------

    /** The contact for a ball at ([x], [y]); fails the test if [wall] reports none. */
    private fun contactOf(wall: WallSegment, x: Float, y: Float): Contact {
        val out = Contact()
        assertTrue("expected $wall to touch a ball at ($x, $y)", wall.contact(x, y, BALL_R, out))
        return out
    }

    /** Asserts no contact, and that the miss left a pre-filled [Contact] exactly as it was. */
    private fun assertNoContact(message: String, wall: WallSegment, x: Float, y: Float) {
        val out = Contact().apply { set(SENTINEL_NX, SENTINEL_NY, SENTINEL_PENETRATION) }
        assertFalse("$message: expected no contact", wall.contact(x, y, BALL_R, out))
        assertEquals("$message: a miss must leave nx untouched", SENTINEL_NX, out.nx, 0f)
        assertEquals("$message: a miss must leave ny untouched", SENTINEL_NY, out.ny, 0f)
        assertEquals(
            "$message: a miss must leave penetration untouched",
            SENTINEL_PENETRATION,
            out.penetration,
            0f,
        )
    }

    private fun assertEndpoints(
        message: String,
        wall: WallSegment,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        assertEquals("$message: x1", x1, wall.x1, 0f)
        assertEquals("$message: y1", y1, wall.y1, 0f)
        assertEquals("$message: x2", x2, wall.x2, 0f)
        assertEquals("$message: y2", y2, wall.y2, 0f)
    }

    private companion object {

        const val BALL_R = 16f
        const val WALL_RESTITUTION = 0.5f

        /** Tight enough for a unit vector computed once in double and rounded to float. */
        const val DELTA = 1e-6

        /** Centroid of the §3.1 triangle (500, 0), (0, 1250), (1000, 1250). */
        const val CENTROID_X = 500.0
        const val CENTROID_Y = 2500.0 / 3.0

        /** |W| for either §3.1 wall: sqrt(500^2 + 1250^2). */
        val WALL_LENGTH = sqrt(500.0 * 500.0 + 1250.0 * 1250.0)

        /** atan(500 / 1250) = 21.80 degrees: §3.1's slant from vertical. */
        val SLANT_DEGREES = Math.toDegrees(atan(500.0 / 1250.0))

        /** Values no real contact produces, to prove a miss wrote nothing. */
        const val SENTINEL_NX = 7f
        const val SENTINEL_NY = -8f
        const val SENTINEL_PENETRATION = 9f

        fun assertD(message: String, expected: Double, actual: Float) =
            assertEquals(message, expected, actual.toDouble(), DELTA)

        /**
         * The inward unit normal of [wall], in double, built from its endpoints and the §3.1
         * centroid — independently of [WallSegment.nx] / [WallSegment.ny].
         */
        fun referenceNormal(wall: WallSegment): DoubleArray {
            val wx = (wall.x2 - wall.x1).toDouble()
            val wy = (wall.y2 - wall.y1).toDouble()
            val length = hypot(wx, wy)
            val nx = -wy / length
            val ny = wx / length
            val inward = (CENTROID_X - wall.x1) * nx + (CENTROID_Y - wall.y1) * ny > 0.0
            return if (inward) doubleArrayOf(nx, ny) else doubleArrayOf(-nx, -ny)
        }

        /** `t` of §3.3: the projection of (x, y) onto [wall], 0 at (x1, y1) and 1 at (x2, y2). */
        fun projectionT(wall: WallSegment, x: Double, y: Double): Double {
            val wx = (wall.x2 - wall.x1).toDouble()
            val wy = (wall.y2 - wall.y1).toDouble()
            return ((x - wall.x1) * wx + (y - wall.y1) * wy) / (wx * wx + wy * wy)
        }

        /** Signed distance of (x, y) from [wall]'s line, positive on the board side. */
        fun signedDistance(wall: WallSegment, x: Double, y: Double): Double {
            val (nx, ny) = referenceNormal(wall)
            return (x - wall.x1) * nx + (y - wall.y1) * ny
        }

        /** The float point at projection [t] along [wall] and signed distance [s] from its line. */
        fun pointAt(wall: WallSegment, t: Double, s: Double): FloatArray {
            val (nx, ny) = referenceNormal(wall)
            val x = wall.x1 + t * (wall.x2 - wall.x1) + s * nx
            val y = wall.y1 + t * (wall.y2 - wall.y1) + s * ny
            return floatArrayOf(x.toFloat(), y.toFloat())
        }
    }
}
