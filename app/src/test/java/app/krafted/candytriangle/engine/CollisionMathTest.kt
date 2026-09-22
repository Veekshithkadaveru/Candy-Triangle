package app.krafted.candytriangle.engine

import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.GameConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The shared narrow phase ([CollisionMath.circleContact]) and the contact response
 * ([CollisionMath.resolve]): the §3.3 bounce, and the §3.3 moving-row hand-off.
 *
 * Everything under test is a pure function of its arguments, so these drive [CollisionMath]
 * directly with hand-built [Ball]s — no world, no step, no emulator. Expected velocities come
 * from a double-precision evaluation of the rule with the bounce in its *expanded* form (normal
 * part `-e vn`, tangential part `(e + (1 - e) damping) vT`), a different route to the answer
 * from the literal form the implementation evaluates, so the two cannot share a mistake. The
 * moving-peg cases use a peg sweeping right at 80 u/s and its left-moving mirror.
 */
class CollisionMathTest {

    // -- circleContact ----------------------------------------------------------------------------

    @Test
    fun separatedCirclesDoNotOverlap() {
        assertNoCircleContact("23 u reach, 30 u apart", 130f, 100f, 100f, 100f)
        // Inside the collider's bounding box but outside the circle: the diagonal is 24.04 u.
        assertNoCircleContact("bounding-box corner", 117f, 117f, 100f, 100f)
    }

    @Test
    fun exactlyTouchingIsNotAnOverlap() {
        assertNoCircleContact("touching along x", 123f, 100f, 100f, 100f)
        assertNoCircleContact("touching along -y", 100f, 77f, 100f, 100f)
        // A 15-20-25 triangle: a 16 u ball touching a 9 u collider exactly, on a diagonal.
        assertNoCircleContact("touching on a diagonal", 115f, 120f, 100f, 100f, colliderRadius = 9f)
        assertNoCircleContact("touching on a diagonal", 85f, 80f, 100f, 100f, colliderRadius = 9f)
    }

    @Test
    fun overlappingCirclesReportAUnitNormalFromColliderToBall() {
        val out = Contact()
        assertTrue(CollisionMath.circleContact(110f, 90f, BALL_R, 100f, 100f, PEG_R, out))
        val d = hypot(10.0, 10.0)
        assertD("nx points from the peg to the ball", 10.0 / d, out.nx)
        assertD("ny points from the peg to the ball", -10.0 / d, out.ny)
        assertEquals("penetration = r + R - d", 23.0 - d, out.penetration.toDouble(), 1e-5)

        assertTrue(CollisionMath.circleContact(100f, 80f, BALL_R, 100f, 100f, PEG_R, out))
        assertEquals("ball directly above: nx", 0f, out.nx, 0f)
        assertEquals("ball directly above: ny", -1f, out.ny, 0f)
        assertEquals("ball directly above: penetration", 3f, out.penetration, 0f)
    }

    @Test
    fun coincidentCentresPushStraightUpByTheFullReach() {
        val out = Contact()
        assertTrue(CollisionMath.circleContact(250f, 640f, BALL_R, 250f, 640f, GEM_R, out))
        assertEquals("nx", 0f, out.nx, 0f)
        assertEquals("ny: straight up, back toward the launcher", -1f, out.ny, 0f)
        assertEquals("penetration = r + R", 46f, out.penetration, 0f)
    }

    /**
     * Offsets on a grid spanning the collider's bounding box and beyond, so every early-out path
     * runs: each answer must match plain double geometry. Points within 1e-4 u of touching are
     * skipped, where float and double may legitimately disagree.
     */
    @Test
    fun circleContactMatchesADoublePrecisionReference() {
        val cx = 431.7f
        val cy = 902.3f
        var hits = 0
        var misses = 0
        for (colliderRadius in floatArrayOf(PEG_R, GEM_R)) {
            val reach = (BALL_R + colliderRadius).toDouble()
            var dy = -reach - 5.0
            while (dy <= reach + 5.0) {
                var dx = -reach - 5.0
                while (dx <= reach + 5.0) {
                    val bx = (cx + dx).toFloat()
                    val by = (cy + dy).toFloat()
                    val rx = bx.toDouble() - cx
                    val ry = by.toDouble() - cy
                    val d = hypot(rx, ry)
                    if (abs(d - reach) > 1e-4) {
                        val out = Contact()
                        val hit =
                            CollisionMath.circleContact(bx, by, BALL_R, cx, cy, colliderRadius, out)
                        val where = "ball ($bx, $by) vs r=$colliderRadius at ($cx, $cy)"
                        assertEquals("$where: overlap", d < reach, hit)
                        if (hit) {
                            hits++
                            assertEquals("$where: nx", rx / d, out.nx.toDouble(), 1e-5)
                            assertEquals("$where: ny", ry / d, out.ny.toDouble(), 1e-5)
                            assertEquals("$where: pen", reach - d, out.penetration.toDouble(), 1e-4)
                        } else {
                            misses++
                        }
                    }
                    dx += 0.73
                }
                dy += 0.79
            }
        }
        assertTrue("the sweep must exercise overlaps, got $hits", hits > 1000)
        assertTrue("the sweep must exercise misses, got $misses", misses > 1000)
    }

    @Test
    fun aNonFinitePositionNeverOverlaps() {
        for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNoCircleContact("ball x = $bad", bad, 100f, 100f, 100f)
            assertNoCircleContact("ball y = $bad", 100f, bad, 100f, 100f)
            assertNoCircleContact("collider x = $bad", 100f, 100f, bad, 100f)
            assertNoCircleContact("collider y = $bad", 100f, 100f, 100f, bad)
        }
    }

    // -- resolve, static collider -----------------------------------------------------------------

    @Test
    fun aHeadOnBounceReversesTheNormalVelocityScaledByRestitution() {
        // Falling straight down at 500 u/s onto a peg 20 u below: 3 u deep, normal straight up.
        val ball = ballAt(100f, 80f, vy = 500f)
        val hit = hitOnCircle(ball, 100f, 100f)

        val impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, 0f, TRANSFER)

        assertEquals("the return value is the approach speed", 500f, impact, 1e-3f)
        assertEquals("v' = -e v along the normal", -300f, ball.vy, 1e-3f)
        assertEquals("no sideways velocity appears", 0f, ball.vx, 0f)
        assertEquals("pushed straight up, out of the peg", 100f - 23f - SLOP, ball.y, 1e-4f)
        assertEquals("not pushed sideways", 100f, ball.x, 0f)
    }

    /**
     * Static and moving colliders, every normal and incidence, against [expected]: the rule
     * evaluated independently in double. The slow balls and the 150 u/s peg are there so that all
     * three moving-row branches run — no reflection for a ball already leaving, a kick capped at
     * the peg's speed, and no kick for a ball already faster than the peg — and the sweep counts
     * them to prove it.
     */
    @Test
    fun obliqueContactsMatchAnIndependentDoubleEvaluationOfSection33() {
        var approaches = 0
        var unreflected = 0
        var capped = 0
        var alreadyFaster = 0
        for (e in floatArrayOf(0.5f, E_PEG, E_GEM)) {
            for (surfaceVx in floatArrayOf(0f, PEG_U, -60f, 150f)) {
                for (normalDegrees in 0 until 360 step 25) {
                    val contact = unitContact(normalDegrees.toDouble(), penetration = 0.25f)
                    for (velocityDegrees in 0 until 360 step 20) {
                        for (speed in floatArrayOf(20f, 60f, 100f, 250f, 900f, 1600f)) {
                            val ball = movingBall(speed, velocityDegrees)
                            val want = expected(ball, contact, e, DAMPING, surfaceVx, TRANSFER)
                            val tolerance = 1e-5 * (speed + abs(surfaceVx)) + 1e-6
                            // Along the contact tangent, (v - u) . n or v . n is zero up to
                            // rounding, and float and double may land on opposite sides of it.
                            // Both answers are right there, so only clear-cut cases compare.
                            if (abs(want.closing) < tolerance) continue
                            if (abs(want.normalSpeed) < tolerance) continue

                            val impact = CollisionMath.resolve(
                                ball, contact, e, DAMPING, surfaceVx, TRANSFER,
                            ).toDouble()

                            val where =
                                "e=$e u=$surfaceVx n=$normalDegrees v=$speed@$velocityDegrees"
                            assertEquals("$where: vx'", want.vx, ball.vx.toDouble(), tolerance)
                            assertEquals("$where: vy'", want.vy, ball.vy.toDouble(), tolerance)
                            assertEquals("$where: impact", want.impact, impact, tolerance)
                            if (impact > 0.0) approaches++
                            if (impact > 0.0 && !want.reflected) unreflected++
                            if (want.capped) capped++
                            if (want.alreadyFaster) alreadyFaster++
                        }
                    }
                }
            }
        }
        val counts = "approaches $approaches, unreflected $unreflected, capped $capped, " +
            "already faster $alreadyFaster"
        assertTrue("the sweep must include plenty of approaches: $counts", approaches > 1000)
        assertTrue("the sweep must exercise the vn >= 0 guard: $counts", unreflected > 20)
        assertTrue("the sweep must exercise the cap: $counts", capped > 20)
        assertTrue("the sweep must exercise the no-kick case: $counts", alreadyFaster > 20)
    }

    @Test
    fun aSeparatingBallIsPushedOutButNotBounced() {
        // Overlapping a peg below it, but already moving up and away.
        val leaving = ballAt(100f, 80f, vx = 37f, vy = -200f)
        val vxBits = leaving.vx.toRawBits()
        val vyBits = leaving.vy.toRawBits()

        val impact = resolveStatic(leaving, hitOnCircle(leaving, 100f, 100f), E_PEG)

        assertEquals("no bounce, so no impact", 0f, impact, 0f)
        assertEquals("vx untouched", vxBits, leaving.vx.toRawBits())
        assertEquals("vy untouched", vyBits, leaving.vy.toRawBits())
        assertEquals("still separated", 100f - 23f - SLOP, leaving.y, 1e-4f)
        assertFalse(
            "and clear of the peg",
            CollisionMath.circleContact(leaving.x, leaving.y, BALL_R, 100f, 100f, PEG_R, Contact()),
        )

        // Sliding exactly along the contact tangent (v . n == 0) is not approaching either.
        val grazing = ballAt(100f, 80f, vx = 250f)
        val grazingImpact = resolveStatic(grazing, hitOnCircle(grazing, 100f, 100f), E_PEG)
        assertEquals("tangential motion is not an approach", 0f, grazingImpact, 0f)
        assertEquals("vx untouched", 250f, grazing.vx, 0f)
        assertEquals("vy untouched", 0f, grazing.vy, 0f)
    }

    @Test
    fun restitutionZeroStopsTheNormalMotionAndKeepsTheDampedTangent() {
        val ball = ballAt(100f, 80f, vx = 300f, vy = 400f)
        val impact = resolveStatic(ball, hitOnCircle(ball, 100f, 100f), restitution = 0f)

        assertEquals("impact", 400f, impact, 1e-3f)
        assertEquals("tangent kept, times tangentialDamping", 300f * DAMPING, ball.vx, 1e-3f)
        assertEquals("normal motion stopped dead", 0f, ball.vy, 1e-4f)
    }

    @Test
    fun restitutionOneIsAPerfectMirrorWhateverTheDamping() {
        // With e = 1 the (1 - e) damping term vanishes, so damping cannot matter.
        for (damping in floatArrayOf(0f, 0.5f, DAMPING, 1f)) {
            val ball = ballAt(100f, 80f, vx = 300f, vy = 400f)
            val hit = hitOnCircle(ball, 100f, 100f)
            val impact = CollisionMath.resolve(ball, hit, 1f, damping, 0f, TRANSFER)

            assertEquals("damping $damping: impact", 400f, impact, 0f)
            assertEquals("damping $damping: tangent kept", 300f, ball.vx, 0f)
            assertEquals("damping $damping: normal mirrored", -400f, ball.vy, 0f)
        }
    }

    @Test
    fun speedNeverIncreasesOffAStaticCollider() {
        var bounces = 0
        for (e in floatArrayOf(0f, 0.5f, E_PEG, E_GEM, 1f)) {
            for (damping in floatArrayOf(DAMPING, 1f)) {
                for (normalDegrees in 0 until 360 step 15) {
                    val contact = unitContact(normalDegrees.toDouble(), penetration = 0.5f)
                    for (velocityDegrees in 0 until 360 step 5) {
                        for (speed in floatArrayOf(1f, 30f, 900f, 1600f)) {
                            val ball = movingBall(speed, velocityDegrees)
                            val before = hypot(ball.vx.toDouble(), ball.vy.toDouble())

                            val impact =
                                CollisionMath.resolve(ball, contact, e, damping, 0f, TRANSFER)
                            if (impact > 0f) bounces++

                            // Float rounding may add an ulp or two; anything more is a real gain.
                            val after = hypot(ball.vx.toDouble(), ball.vy.toDouble())
                            assertTrue(
                                "e=$e damping=$damping n=$normalDegrees deg " +
                                    "v=$speed@$velocityDegrees deg: speed rose $before -> $after",
                                after <= before * (1.0 + 2e-6),
                            )
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must include plenty of bounces, got $bounces", bounces > 10000)
    }

    /**
     * The static path must be the plain §3.3 formula bit for bit: evaluated here directly, in
     * float, term by term as the PRD writes it, and compared by raw bits.
     */
    @Test
    fun aStaticColliderIsBitForBitThePlainSection33Formula() {
        var bounces = 0
        for (e in floatArrayOf(0f, 0.5f, E_PEG, E_GEM, 1f)) {
            for (normalDegrees in 0 until 360 step 15) {
                val contact = unitContact(normalDegrees + 0.3, penetration = 0.5f)
                val nx = contact.nx
                val ny = contact.ny
                for (velocityDegrees in 0 until 360 step 10) {
                    for (speed in floatArrayOf(1f, 30f, 900f, 1600f)) {
                        val ball = movingBall(speed, velocityDegrees)
                        val vx = ball.vx
                        val vy = ball.vy
                        // V' = e (V - 2 (V . n) n) + (1 - e) 0.98 V_tangent, if V . n < 0.
                        val vn = vx * nx + vy * ny
                        val share = (1f - e) * DAMPING
                        var wantVx = vx
                        var wantVy = vy
                        var wantImpact = 0f
                        if (vn < 0f) {
                            wantVx = e * (vx - 2f * vn * nx) + share * (vx - vn * nx)
                            wantVy = e * (vy - 2f * vn * ny) + share * (vy - vn * ny)
                            wantImpact = -vn
                        }

                        val impact = resolveStatic(ball, contact, e)

                        val where = "e=$e n=$normalDegrees v=$speed@$velocityDegrees"
                        assertEquals("$where: vx' bits", wantVx.toRawBits(), ball.vx.toRawBits())
                        assertEquals("$where: vy' bits", wantVy.toRawBits(), ball.vy.toRawBits())
                        assertEquals("$where: impact", wantImpact.toRawBits(), impact.toRawBits())
                        if (impact > 0f) bounces++
                    }
                }
            }
        }
        assertTrue("the sweep must include plenty of bounces, got $bounces", bounces > 1000)
    }

    // -- resolve, moving collider (§3.3 moving rows) ----------------------------------------------

    @Test
    fun aTopHitOnAMovingPegKicksTheBallHalfThePegSpeedItsWay() {
        for (u in floatArrayOf(PEG_U, -PEG_U)) {
            // Falling straight down at 500 u/s onto the top of a peg sweeping sideways at u.
            val ball = ballAt(100f, 80f, vy = 500f)
            val hit = hitOnCircle(ball, 100f, 100f)
            val want = expectedImpact(ball, hit, u)

            val impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, u, TRANSFER)

            assertEquals("u=$u: impact = -(v - u) . n", want, impact, 0f)
            assertEquals("u=$u: the fall hits at 500 u/s", 500f, impact, 0f)
            assertEquals("u=$u: kicked exactly transfer * u.x", TRANSFER * u, ball.vx, 0f)
            assertEquals("u=$u: bounced as off a static peg", -E_PEG * 500f, ball.vy, 0f)
        }
    }

    @Test
    fun aBallRestingOnAMovingPegRidesUpToItsSpeedAndNeverPast() {
        for (u in floatArrayOf(PEG_U, -PEG_U)) {
            // Settled on top of the peg: each step, gravity presses it in and it micro-bounces.
            val ball = ballAt(100f, 77f)
            val top = Contact().apply { set(0f, -1f, 0.01f) }
            var bounces = 0
            repeat(20) { step ->
                ball.vy += GRAVITY_DT
                val before = ball.vx
                val want = expectedImpact(ball, top, u)

                val impact = CollisionMath.resolve(ball, top, E_PEG, DAMPING, u, TRANSFER)

                val where = "u=$u, micro-contact $step"
                assertEquals("$where: impact = -(v - u) . n", want, impact, 0f)
                if (impact > 0f) bounces++
                // Toward the peg's speed and never past it: no reversal, no runaway.
                if (u > 0f) {
                    assertTrue("$where: vx ${ball.vx} fell from $before", ball.vx >= before)
                    assertTrue("$where: vx ${ball.vx} passed the peg", ball.vx <= u)
                } else {
                    assertTrue("$where: vx ${ball.vx} rose from $before", ball.vx <= before)
                    assertTrue("$where: vx ${ball.vx} passed the peg", ball.vx >= u)
                }
            }
            assertTrue("u=$u: it micro-bounced on nearly every step, $bounces", bounces >= 15)
            assertEquals("u=$u: it ends up riding at the peg's speed", u, ball.vx, 0f)
        }
    }

    @Test
    fun aPegSweepingIntoABallAtRestPushesItUpToThePegSpeedAndNoFurther() {
        for (u in floatArrayOf(PEG_U, -PEG_U)) {
            // The ball rests 20 u from the peg's centre, on the side the peg is sweeping toward.
            val side = if (u > 0f) 1f else -1f
            val ball = ballAt(100f + side * 20f, 100f)
            val hit = hitOnCircle(ball, 100f, 100f)
            assertEquals("u=$u: the normal points along the sweep", side, hit.nx, 0f)

            // 1st contact: an approach only in the peg's frame. At rest, v . n = 0, so there is
            // nothing to reflect: just the kick.
            var want = expectedImpact(ball, hit, u)
            var impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, u, TRANSFER)
            assertEquals("u=$u, 1st: impact = -(v - u) . n", want, impact, 0f)
            assertEquals("u=$u, 1st: the peg closes at its own speed", abs(u), impact, 0f)
            assertEquals("u=$u, 1st: kicked transfer * u.x", TRANSFER * u, ball.vx, 0f)
            assertEquals("u=$u, 1st: not reflected", 0f, ball.vy, 0f)

            // 2nd: the peg catches up again (same geometry) and takes it to the peg's own speed.
            want = expectedImpact(ball, hit, u)
            impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, u, TRANSFER)
            assertEquals("u=$u, 2nd: impact = -(v - u) . n", want, impact, 0f)
            assertEquals("u=$u, 2nd: closing at the half it lacks", abs(u) / 2f, impact, 0f)
            assertEquals("u=$u, 2nd: capped at the peg's speed", u, ball.vx, 0f)

            // 3rd: riding along at the peg's speed, nothing closes any more: separation only.
            impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, u, TRANSFER)
            assertEquals("u=$u, 3rd: no longer approaching", 0f, impact, 0f)
            assertEquals("u=$u, 3rd: ridden, not launched past the peg", u, ball.vx, 0f)
        }
    }

    @Test
    fun aHeadOnHitIsReflectedWithNoKickWhenAlreadyFasterThanThePeg() {
        for (u in floatArrayOf(PEG_U, -PEG_U)) {
            // On the side the peg sweeps toward, the ball comes back at it at 200 u/s.
            val side = if (u > 0f) 1f else -1f
            val ball = ballAt(100f + side * 20f, 100f, vx = -side * 200f)
            val hit = hitOnCircle(ball, 100f, 100f)
            val want = expectedImpact(ball, hit, u)

            val impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, u, TRANSFER)

            assertEquals("u=$u: impact = -(v - u) . n", want, impact, 0f)
            assertEquals("u=$u: closing at 200 + 80 in the peg's frame", 280f, impact, 0f)
            // Reflected to e * 200 = 120 the peg's way: already faster than the peg, so no kick.
            assertEquals("u=$u: reflected, and not kicked", side * E_PEG * 200f, ball.vx, 0f)
            assertEquals("u=$u: no vertical velocity appears", 0f, ball.vy, 0f)
        }
    }

    @Test
    fun aBallRecedingFasterThanThePegIsOnlySeparated() {
        for (u in floatArrayOf(PEG_U, -PEG_U)) {
            // Leaving the peg at 150 u/s along its sweep; the peg follows at only 80.
            val side = if (u > 0f) 1f else -1f
            val ball = ballAt(100f + side * 20f, 100f, vx = side * 150f, vy = 20f)
            val hit = hitOnCircle(ball, 100f, 100f)
            val vxBits = ball.vx.toRawBits()
            val vyBits = ball.vy.toRawBits()

            val impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, u, TRANSFER)

            assertEquals("u=$u: separating in the peg's frame, so no bounce", 0f, impact, 0f)
            assertEquals("u=$u: and no kick: vx untouched", vxBits, ball.vx.toRawBits())
            assertEquals("u=$u: vy untouched", vyBits, ball.vy.toRawBits())
            assertEquals("u=$u: but still pushed out", 100f + side * (23f + SLOP), ball.x, 1e-4f)
        }
    }

    @Test
    fun aBallChasingAPegThatPullsAwayFasterIsNotBounced() {
        // Ball left of the peg moving right at 50 u/s; the peg retreats right at 80 u/s.
        val chasing = ballAt(80f, 100f, vx = 50f)
        val hit = hitOnCircle(chasing, 100f, 100f)
        val impact = CollisionMath.resolve(chasing, hit, E_PEG, DAMPING, 80f, TRANSFER)
        assertEquals("the peg is pulling away faster: no bounce", 0f, impact, 0f)
        assertEquals("vx untouched", 50f, chasing.vx, 0f)

        // Against a static peg, the very same ball is a 50 u/s impact.
        val control = ballAt(80f, 100f, vx = 50f)
        val controlImpact = resolveStatic(control, hitOnCircle(control, 100f, 100f), E_PEG)
        assertEquals("static control: the approach speed", 50f, controlImpact, 1e-4f)
    }

    @Test
    fun transferIsIrrelevantForAStaticCollider() {
        fun resolveWith(transfer: Float): IntArray {
            val ball = ballAt(100f, 80f, vx = 123.4f, vy = 567.8f)
            val hit = hitOnCircle(ball, 100f, 100f)
            val impact = CollisionMath.resolve(ball, hit, E_PEG, DAMPING, 0f, transfer)
            return intArrayOf(
                ball.x.toRawBits(),
                ball.y.toRawBits(),
                ball.vx.toRawBits(),
                ball.vy.toRawBits(),
                impact.toRawBits(),
            )
        }

        val baseline = resolveWith(0f)
        for (transfer in floatArrayOf(0.5f, 1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertArrayEquals("surfaceVx 0, transfer $transfer", baseline, resolveWith(transfer))
        }
    }

    // -- resolve against a wall -------------------------------------------------------------------

    @Test
    fun aBallThatCrossedAWallIsPutBackInsideAndBounced() {
        val left = WallSegment.boardWalls(GameConfig.DEFAULTS.board, 0.5f)[0]
        // Centre 5 u outside the left wall's midpoint (250, 625), still heading out at 1600 u/s.
        val ball = ballAt(
            250f - 5f * left.nx,
            625f - 5f * left.ny,
            vx = -1600f * left.nx,
            vy = -1600f * left.ny,
        )
        val hit = Contact()
        assertTrue("a crossed centre is a contact", left.contact(ball.x, ball.y, BALL_R, hit))
        assertEquals("penetration = r - s, with s = -5", 21f, hit.penetration, 1e-3f)

        val impact = CollisionMath.resolve(ball, hit, left.restitution, DAMPING, 0f, TRANSFER)

        assertEquals("impact", 1600f, impact, 0.05f)
        val s = (ball.x - left.x1) * left.nx + (ball.y - left.y1) * left.ny
        assertEquals("back inside, a radius plus the slop from the line", 16f + SLOP, s, 2e-4f)
        val vn = ball.vx * left.nx + ball.vy * left.ny
        assertEquals("heading back in at e times the approach speed", 800f, vn, 0.05f)
        assertFalse("and clear of the wall", left.contact(ball.x, ball.y, BALL_R, Contact()))
    }

    // -- separation leaves no sliver --------------------------------------------------------------

    /**
     * The reason [CollisionMath.SEPARATION_SLOP] exists: without it, about half of these would
     * still overlap by a float rounding error after [CollisionMath.resolve], and the step's next
     * contact pass would re-detect them. Also checks that the slop is all the extra there is.
     */
    @Test
    fun aResolvedBallNoLongerOverlapsTheCircleItHit() {
        val probe = Contact()
        var checked = 0
        for (colliderRadius in floatArrayOf(PEG_R, GEM_R)) {
            val reach = BALL_R + colliderRadius
            for (px in 0..1000 step 53) {
                for (py in 0..1346 step 61) {
                    val cx = px + 0.37f
                    val cy = py + 0.61f
                    for (k in 0 until 24) {
                        val a = 2.0 * PI * k / 24 + 0.1
                        for (depth in floatArrayOf(0.01f, 0.5f, 3f, 6.67f, 12f)) {
                            val dist = reach - depth
                            val ball = ballAt(
                                (cx + dist * cos(a)).toFloat(),
                                (cy + dist * sin(a)).toFloat(),
                                vx = (-300.0 * cos(a)).toFloat(),
                                vy = (-300.0 * sin(a)).toFloat(),
                            )
                            val hit = hitOnCircle(ball, cx, cy, colliderRadius)

                            resolveStatic(ball, hit, E_PEG)

                            val where = "r=$colliderRadius at ($cx, $cy), angle $k/24, depth $depth"
                            assertFalse(
                                "$where: still overlapping after resolve, $ball",
                                CollisionMath.circleContact(
                                    ball.x, ball.y, BALL_R, cx, cy, colliderRadius, probe,
                                ),
                            )
                            val gap = hypot(ball.x.toDouble() - cx, ball.y.toDouble() - cy) - reach
                            assertTrue(
                                "$where: separated by $gap, expected about the slop",
                                gap > 0.5 * SLOP && gap < 2.0 * SLOP,
                            )
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must resolve plenty of contacts, got $checked", checked > 10000)

        // Coincident centres: pushed a full reach (plus the slop) straight up, and clear.
        val coincident = ballAt(640f, 1200f, vy = 300f)
        resolveStatic(coincident, hitOnCircle(coincident, 640f, 1200f), E_PEG)
        assertFalse(
            "coincident centres: still overlapping after resolve",
            CollisionMath.circleContact(
                coincident.x, coincident.y, BALL_R, 640f, 1200f, PEG_R, probe,
            ),
        )
    }

    @Test
    fun aResolvedBallNoLongerOverlapsTheWallItHit() {
        val walls = WallSegment.boardWalls(GameConfig.DEFAULTS.board, 0.5f)
        val probe = Contact()
        var checked = 0

        fun resolveAndRetest(wall: WallSegment, x: Float, y: Float) {
            val ball = ballAt(x, y, vx = -500f * wall.nx, vy = -500f * wall.ny)
            val hit = Contact()
            if (!wall.contact(ball.x, ball.y, BALL_R, hit)) return // past an end and out of reach
            resolveStatic(ball, hit, wall.restitution)
            assertFalse(
                "$wall, ball from ($x, $y): still overlapping after resolve, $ball",
                wall.contact(ball.x, ball.y, BALL_R, probe),
            )
            checked++
        }

        for (wall in walls) {
            // Alongside and just past both ends; inside the line, on it, and crossed.
            for (i in -3..103) {
                val t = i / 100.0
                for (s in doubleArrayOf(-20.0, -6.67, -1.0, 0.0, 2.5, 8.0, 15.9)) {
                    val x = wall.x1 + t * (wall.x2 - wall.x1) + s * wall.nx
                    val y = wall.y1 + t * (wall.y2 - wall.y1) + s * wall.ny
                    resolveAndRetest(wall, x.toFloat(), y.toFloat())
                }
            }
            // A fine grid around the base corner, where the endpoint test takes over.
            var y = 1230.0
            while (y <= 1270.0) {
                var x = wall.x2 - 20.0
                while (x <= wall.x2 + 20.0) {
                    resolveAndRetest(wall, x.toFloat(), y.toFloat())
                    x += 1.3
                }
                y += 1.1
            }
        }
        assertTrue("the sweep must resolve plenty of contacts, got $checked", checked > 2000)
    }

    // -- determinism ------------------------------------------------------------------------------

    /** §11 replays these calls bit for bit: identical inputs must give identical raw bits. */
    @Test
    fun identicalInputsGiveBitIdenticalOutputs() {
        fun replay(): IntArray {
            val walls = WallSegment.boardWalls(GameConfig.DEFAULTS.board, 0.5f)
            val contact = Contact()
            val trace = ArrayList<Int>()
            for (i in 0 until 500) {
                val x = 20f + (i * 37 % 960) + i * 0.013f
                val y = 30f + (i * 53 % 1300) + i * 0.029f
                val vx = (i * 71 % 3200 - 1600).toFloat()
                val vy = (i * 43 % 3200 - 1600).toFloat()
                val ball = ballAt(x, y, vx, vy)
                for (wall in walls) {
                    if (wall.contact(ball.x, ball.y, BALL_R, contact)) {
                        trace += contact.penetration.toRawBits()
                        trace += resolveStatic(ball, contact, wall.restitution).toRawBits()
                    }
                }
                val pegVx = (i % 7 - 3) * 20f
                val touching = CollisionMath.circleContact(
                    ball.x, ball.y, BALL_R, x + 5f, y + 9f, PEG_R, contact,
                )
                if (touching) {
                    trace += contact.nx.toRawBits()
                    trace += contact.ny.toRawBits()
                    trace += contact.penetration.toRawBits()
                    val impact =
                        CollisionMath.resolve(ball, contact, E_PEG, DAMPING, pegVx, TRANSFER)
                    trace += impact.toRawBits()
                }
                trace += ball.x.toRawBits()
                trace += ball.y.toRawBits()
                trace += ball.vx.toRawBits()
                trace += ball.vy.toRawBits()
            }
            return trace.toIntArray()
        }

        val first = replay()
        val second = replay()
        assertTrue("the replay must record every ball, got ${first.size}", first.size >= 2000)
        assertArrayEquals("two runs of the same inputs", first, second)
    }

    // -- helpers ----------------------------------------------------------------------------------

    private fun ballAt(x: Float, y: Float, vx: Float = 0f, vy: Float = 0f) =
        Ball(id = 0, x = x, y = y, vx = vx, vy = vy, radius = BALL_R, skin = BallSkin.DEFAULT)

    /** A ball at (500, 600) moving at [speed] u/s, [degrees] from +x (y down). */
    private fun movingBall(speed: Float, degrees: Int): Ball {
        val a = Math.toRadians(degrees.toDouble())
        return ballAt(500f, 600f, (speed * cos(a)).toFloat(), (speed * sin(a)).toFloat())
    }

    /** [ball]'s contact with a collider at ([cx], [cy]); fails the test if they do not overlap. */
    private fun hitOnCircle(
        ball: Ball,
        cx: Float,
        cy: Float,
        colliderRadius: Float = PEG_R,
    ): Contact {
        val out = Contact()
        assertTrue(
            "expected $ball to overlap the r=$colliderRadius collider at ($cx, $cy)",
            CollisionMath.circleContact(ball.x, ball.y, ball.radius, cx, cy, colliderRadius, out),
        )
        return out
    }

    /** [CollisionMath.resolve] against a static collider with the §3.2 damping. */
    private fun resolveStatic(ball: Ball, contact: Contact, restitution: Float): Float =
        CollisionMath.resolve(ball, contact, restitution, DAMPING, 0f, TRANSFER)

    /**
     * The impact [CollisionMath.resolve] must return for [ball]'s current velocity against a
     * collider moving at ([surfaceVx], 0): `-((v - u) . n)` when that is positive, else 0 —
     * evaluated in the same float arithmetic, so it can be compared exactly.
     */
    private fun expectedImpact(ball: Ball, contact: Contact, surfaceVx: Float): Float {
        val closing = (ball.vx - surfaceVx) * contact.nx + ball.vy * contact.ny
        return if (closing < 0f) -closing else 0f
    }

    /** Asserts no overlap, and that the miss left a pre-filled [Contact] exactly as it was. */
    private fun assertNoCircleContact(
        message: String,
        ballX: Float,
        ballY: Float,
        cx: Float,
        cy: Float,
        colliderRadius: Float = PEG_R,
    ) {
        val out = Contact().apply { set(SENTINEL_NX, SENTINEL_NY, SENTINEL_PENETRATION) }
        assertFalse(
            "$message: expected no overlap",
            CollisionMath.circleContact(ballX, ballY, BALL_R, cx, cy, colliderRadius, out),
        )
        assertEquals("$message: a miss must leave nx untouched", SENTINEL_NX, out.nx, 0f)
        assertEquals("$message: a miss must leave ny untouched", SENTINEL_NY, out.ny, 0f)
        assertEquals(
            "$message: a miss must leave penetration untouched",
            SENTINEL_PENETRATION,
            out.penetration,
            0f,
        )
    }

    private companion object {

        const val BALL_R = 16f
        const val PEG_R = 7f
        const val GEM_R = 30f
        const val E_PEG = 0.60f
        const val E_GEM = 0.65f
        const val DAMPING = 0.98f
        const val TRANSFER = 0.50f
        const val SLOP = CollisionMath.SEPARATION_SLOP

        /** A moving-row peg's speed, u/s: the §3.2 rows (40 u, 3 s) peak at 83.8. */
        const val PEG_U = 80f

        /** One 240 Hz step of §3.2 gravity, u/s. */
        const val GRAVITY_DT = 1400f / 240f

        /** Values no real contact produces, to prove a miss wrote nothing. */
        const val SENTINEL_NX = 7f
        const val SENTINEL_NY = -8f
        const val SENTINEL_PENETRATION = 9f

        fun assertD(message: String, expected: Double, actual: Float) =
            assertEquals(message, expected, actual.toDouble(), 1e-6)

        /** A contact whose normal points [angleDegrees] from +x (y down), rounded to float. */
        fun unitContact(angleDegrees: Double, penetration: Float): Contact {
            val a = Math.toRadians(angleDegrees)
            return Contact().apply { set(cos(a).toFloat(), sin(a).toFloat(), penetration) }
        }

        /** What [expected] predicts for one contact, and which branches of the rule it took. */
        class Expected(
            val vx: Double,
            val vy: Double,
            val impact: Double,
            /** `(v - u) . n` and `v . n`: the two quantities whose sign picks a branch. */
            val closing: Double,
            val normalSpeed: Double,
            /** The §3.3 bounce reflected the ball (it was itself moving into the surface). */
            val reflected: Boolean,
            /** The moving-row kick was cut short at the peg's own speed. */
            val capped: Boolean,
            /** A moving peg closed on a ball already at least as fast as the peg: no kick. */
            val alreadyFaster: Boolean,
        )

        /**
         * The contact rule evaluated in double, the bounce in *expanded* form: approaching iff
         * `(v - u) . n < 0`; if `vn = v . n < 0`, the ball's own velocity becomes
         * `-e vn n + (e + (1 - e) damping) (v - vn n)` (§3.3), otherwise it is kept; then, against
         * a moving collider, `vx` is kicked `transfer u.x` toward `u.x` and capped there. Uses the
         * contact's own float normal, so both sides see the same vector.
         */
        fun expected(
            ball: Ball,
            contact: Contact,
            e: Float,
            damping: Float,
            surfaceVx: Float,
            transfer: Float,
        ): Expected {
            val nx = contact.nx.toDouble()
            val ny = contact.ny.toDouble()
            val vx = ball.vx.toDouble()
            val vy = ball.vy.toDouble()
            val u = surfaceVx.toDouble()
            val closing = (vx - u) * nx + vy * ny
            val vn = vx * nx + vy * ny
            if (closing >= 0.0) return Expected(vx, vy, 0.0, closing, vn, false, false, false)

            var outX = vx
            var outY = vy
            if (vn < 0.0) {
                val tangentKeep = e + (1.0 - e) * damping
                outX = -e * vn * nx + tangentKeep * (vx - vn * nx)
                outY = -e * vn * ny + tangentKeep * (vy - vn * ny)
            }
            val kicked = outX + transfer * u
            val capped: Boolean
            val alreadyFaster: Boolean
            if (u > 0.0) {
                capped = outX < u && kicked > u
                alreadyFaster = outX >= u
                outX = max(outX, min(kicked, u))
            } else if (u < 0.0) {
                capped = outX > u && kicked < u
                alreadyFaster = outX <= u
                outX = min(outX, max(kicked, u))
            } else {
                capped = false
                alreadyFaster = false
            }
            return Expected(outX, outY, -closing, closing, vn, vn < 0.0, capped, alreadyFaster)
        }
    }
}
