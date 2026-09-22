package app.krafted.candytriangle.engine

import app.krafted.candytriangle.engine.PhysicsWorldTest.Companion.runUntilNoActiveBalls
import app.krafted.candytriangle.engine.PhysicsWorldTest.EventLog
import app.krafted.candytriangle.level.BoardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pipeline step 2.5 as the world drives it: which colliders are tested, in what order, with which
 * parameters, and what reaches the listener — plus the listener's licence to spawn, remove and
 * deactivate from inside a contact callback.
 *
 * The bounce formula itself is [CollisionMath]'s and is tested there; these tests check the world
 * feeds it the right collider, restitution and surface velocity, and reports its result. Boards are
 * a peg or two in free space, walls only where a test needs them.
 */
class PhysicsWorldContactTest {

    // -- pegs and gems ---------------------------------------------------------------------------

    /**
     * Dropped dead-centre, the contact normal is exactly vertical, so the reported impact speed is
     * the ball's downward speed in that step (after gravity), and the rebound is `-e` times it with
     * `e` the collider's own restitution — 0.60 for a peg, 0.65 for a gem (§3.2).
     */
    @Test
    fun aBallDroppedOnAPegReportsItsApproachSpeedAndBouncesWithThePegsRestitution() {
        val params = PhysicsParams()
        val peg = Peg(id = 7, x = 500f, y = 600f, radius = params.pegRadius, restitution = 0.60f)
        val gem = Peg(
            id = 8,
            x = 500f,
            y = 600f,
            radius = params.gemRadius,
            restitution = 0.65f,
            kind = ColliderKind.GEM,
        )

        for (collider in listOf(peg, gem)) {
            val probe = ApproachProbe(params)
            val world = PhysicsWorld(params, walls = emptyList(), pegs = listOf(collider), listener = probe)
            world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)
            var guard = 0
            while (probe.hits.isEmpty()) {
                check(guard++ < 1_000) { "never reached the ${collider.kind}" }
                world.step()
            }

            val hit = probe.hits.first()
            val e = collider.restitution
            assertSame("the ${collider.kind} is reported", collider, hit.peg)
            assertTrue("a real impact, not a graze", hit.approachSpeed > 100f)
            assertEquals("${collider.kind}: impact = approach speed", hit.approachSpeed, hit.impactSpeed, 1e-3f)
            assertEquals("${collider.kind}: rebound = -e * impact", -e * hit.impactSpeed, hit.vyAfter, 1e-3f)
            assertEquals("${collider.kind}: dead-centre means no sideways kick", 0f, hit.vxAfter, 0f)
        }
    }

    @Test
    fun anInactivePegIsPassedStraightThrough() {
        val peg = Peg(id = 1, x = 500f, y = 600f, radius = 7f, restitution = 0.6f).apply { active = false }
        val log = EventLog()
        val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = listOf(peg), listener = log)
        val ball = world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)

        runUntilNoActiveBalls(world)

        assertTrue("an inactive peg is never reported", log.pegHits.isEmpty())
        assertEquals("nor deflects the ball", 500f, ball.x, 0f)
        assertEquals("which falls on out of the board", listOf(ball.id), log.exits.map { it.ballId })
    }

    /** B3's gem smash: the gem is deactivated from its own contact callback, mid-step. */
    @Test
    fun aGemDeactivatedInItsOwnContactCallbackIsNeverHitAgain() {
        val gem = Peg(id = 1, x = 500f, y = 600f, radius = 30f, restitution = 0.65f, kind = ColliderKind.GEM)
        val log = EventLog()
        val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = listOf(gem))
        world.listener = object : PhysicsListener by log {
            override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
                log.onPegContact(ball, peg, impactSpeed)
                peg.active = false
            }
        }
        val ball = world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)

        runUntilNoActiveBalls(world)

        assertEquals("smashed exactly once", 1, log.pegHits.size)
        assertTrue("the smash bounced it", log.pegHits.first().vyAfter < 0f)
        assertEquals("then it fell back through the gem's place", listOf(ball.id), log.exits.map { it.ballId })
    }

    /**
     * Two pegs mirror-symmetric about the ball's line of fall are overlapped in the same step, so
     * both are resolved in that step — in the order the world was given them.
     */
    @Test
    fun pegsOverlappedInTheSameStepResolveInTheOrderTheWorldWasGiven() {
        fun firstStepHits(leftFirst: Boolean): List<Int> {
            val left = Peg(id = 1, x = 484f, y = 600f, radius = 7f, restitution = 0.6f)
            val right = Peg(id = 2, x = 516f, y = 600f, radius = 7f, restitution = 0.6f)
            val log = EventLog()
            val pegs = if (leftFirst) listOf(left, right) else listOf(right, left)
            val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = pegs, listener = log)
            world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)
            var guard = 0
            while (log.pegHits.isEmpty()) {
                check(guard++ < 1_000) { "never reached the pegs" }
                world.step()
            }
            val step = log.pegHits.first().step
            return log.pegHits.filter { it.step == step }.map { it.peg.id }
        }

        assertEquals("given left, right", listOf(1, 2), firstStepHits(leftFirst = true).take(2))
        assertEquals("given right, left", listOf(2, 1), firstStepHits(leftFirst = false).take(2))
    }

    /**
     * The multi-pass backstop. In a V narrower than the ball, pushing it out of one peg drives it
     * into the other, so a single pass leaves it about 0.5 u deep (1.28 u at first contact here).
     * The step's [PhysicsWorld.MAX_CONTACT_PASSES] passes must leave no more than a sliver.
     *
     * A §3.4 board never has such a V — every gap there clears the ball — which is exactly why this
     * is the backstop's case rather than an everyday one.
     */
    @Test
    fun aBallDroppedIntoANarrowVIsSeparatedFromBothPegsWithinTheStep() {
        val left = Peg(id = 1, x = 482f, y = 600f, radius = 7f, restitution = 0.6f)
        val right = Peg(id = 2, x = 518f, y = 600f, radius = 7f, restitution = 0.6f)
        val log = EventLog()
        val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = listOf(left, right), listener = log)
        val ball = world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)

        repeat(600) { n ->
            world.step()
            for (peg in listOf(left, right)) {
                val overlap = ball.radius + peg.radius - hypot(ball.x - peg.x, ball.y - peg.y)
                assertTrue(
                    "after step ${n + 1} the ball is still $overlap u inside peg ${peg.id}",
                    overlap <= MAX_RESIDUAL_OVERLAP,
                )
            }
        }
        assertEquals(
            "the V was really hit: both pegs in the first contact step",
            setOf(left.id, right.id),
            log.pegHits.filter { it.step == log.pegHits.first().step }.map { it.peg.id }.toSet(),
        )
    }

    // -- walls ------------------------------------------------------------------------------------------

    @Test
    fun aWideLaunchBouncesOffTheWallOnItsSide() {
        val params = PhysicsParams()
        for ((aim, side) in listOf(1.3f to RIGHT, -1.3f to LEFT)) {
            val log = EventLog()
            val world = PhysicsWorld(
                params = params,
                walls = WallSegment.boardWalls(BoardConfig(), params.restitutionWall),
                pegs = emptyList(),
                listener = log,
            )
            world.launch(aim)
            var guard = 0
            while (log.wallHits.isEmpty()) {
                check(guard++ < 240) { "aim $aim never reached a wall" }
                world.step()
            }

            val hit = log.wallHits.first()
            assertSame("aim $aim meets the wall on its own side first", world.walls[side], hit.wall)
            assertTrue("aim $aim: a real impact", hit.impactSpeed > 100f)
            assertTrue(
                "aim $aim: the bounce turns it back toward the axis (vx ${hit.vxAfter})",
                if (side == RIGHT) hit.vxAfter < 0f else hit.vxAfter > 0f,
            )
            assertTrue("aim $aim: no peg was involved", log.pegHits.isEmpty())
        }
    }

    // -- listener mutations from contact callbacks ------------------------------------------------------

    /** B3's Split Gem: a clone spawned from the contact callback joins at the end of the step. */
    @Test
    fun aBallSpawnedFromAContactCallbackJoinsAtTheEndOfTheStep() {
        val gem = Peg(id = 1, x = 500f, y = 600f, radius = 30f, restitution = 0.65f, kind = ColliderKind.GEM)
        val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = listOf(gem))
        var clone: Ball? = null
        var cloneListedInItsOwnStep = true
        var spawnX = Float.NaN
        var spawnY = Float.NaN
        world.listener = object : PhysicsListener {
            override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
                if (clone != null) return
                spawnX = ball.x
                spawnY = ball.y
                val c = world.spawnBall(ball.x, ball.y, -ball.vx, ball.vy)
                clone = c
                cloneListedInItsOwnStep = c in world.balls
            }
        }
        val parent = world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)

        var guard = 0
        while (clone == null) {
            check(guard++ < 1_000) { "never reached the gem" }
            world.step()
        }
        val c = checkNotNull(clone)

        assertFalse("not in balls during the step that spawned it", cloneListedInItsOwnStep)
        assertEquals("appended after its parent at the end of the step", listOf(parent, c), world.balls)
        assertEquals("it has not moved yet: x", spawnX, c.x, 0f)
        assertEquals("it has not moved yet: y", spawnY, c.y, 0f)

        world.step()
        assertEquals("its first move starts from the spawn point", spawnY, c.prevY, 0f)
        assertTrue("and happens on the next step", c.y != spawnY)
    }

    /**
     * A ball removed from its own contact callback stops right there. The mirror-symmetric pair is
     * overlapped in one pass, so an implementation that kept going would report the second peg.
     */
    @Test
    fun aBallRemovedInAContactCallbackIsProcessedNoFurther() {
        val left = Peg(id = 1, x = 484f, y = 600f, radius = 7f, restitution = 0.6f)
        val right = Peg(id = 2, x = 516f, y = 600f, radius = 7f, restitution = 0.6f)
        val log = EventLog()
        val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = listOf(left, right))
        world.listener = object : PhysicsListener by log {
            override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
                log.onPegContact(ball, peg, impactSpeed)
                world.removeBall(ball)
            }
        }
        val ball = world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)

        repeat(400) { world.step() }

        assertEquals("only the first peg was reported", listOf(left.id), log.pegHits.map { it.peg.id })
        assertFalse("removed", ball.active)
        assertTrue("gone from balls", world.balls.isEmpty())
        assertTrue("and a removal is never an exit", log.exits.isEmpty())
    }

    // -- moving pegs --------------------------------------------------------------------------------

    /**
     * The world hands a moving peg's instantaneous [Peg.vx] and `movingPegTransfer` to the contact
     * response. Two runs that differ only in the transfer share cannot diverge before the first
     * contact, so their rebounds must differ by exactly `transfer * peg.vx` — §3.3's hand-off —
     * whatever the rest of the bounce is.
     */
    @Test
    fun aMovingPegHandsItsVelocityToTheContactResponse() {
        fun firstHit(transfer: Float): EventLog.PegHit {
            val motion = PegMotion(baseX = 500f, amplitude = 10f, periodSeconds = 3f)
            val peg = Peg(id = 1, x = 500f, y = 600f, radius = 7f, restitution = 0.6f, motion = motion)
            val log = EventLog()
            val world = PhysicsWorld(
                params = PhysicsParams(movingPegTransfer = transfer),
                walls = emptyList(),
                pegs = listOf(peg),
                listener = log,
            )
            world.spawnBall(x = 500f, y = 450f, vx = 0f, vy = 0f)
            var guard = 0
            while (log.pegHits.isEmpty()) {
                check(guard++ < 1_000) { "never reached the moving peg" }
                world.step()
            }
            return log.pegHits.first()
        }

        val plain = firstHit(transfer = 0f)
        val handedOff = firstHit(transfer = 0.5f)

        assertEquals("both runs meet the peg on the same step", plain.step, handedOff.step)
        assertEquals("with the peg at the same velocity", plain.pegVx, handedOff.pegVx, 0f)
        assertTrue("and that velocity is real (${handedOff.pegVx} u/s)", abs(handedOff.pegVx) > 1f)
        assertEquals(
            "the rebounds differ by exactly transfer * peg.vx",
            0.5f * handedOff.pegVx,
            handedOff.vxAfter - plain.vxAfter,
            1e-3f,
        )
        assertEquals("the transfer is horizontal only", plain.vyAfter, handedOff.vyAfter, 0f)
    }

    // -- helpers ---------------------------------------------------------------------------------------

    /**
     * Records each peg hit alongside the approach speed the ball had coming into that step's
     * contact: its velocity at the end of the previous step plus that step's gravity (the ball
     * falls straight, so no speed clamp is involved).
     */
    private class ApproachProbe(params: PhysicsParams) : PhysicsListener {

        data class Hit(
            val peg: Peg,
            val impactSpeed: Float,
            val approachSpeed: Float,
            val vxAfter: Float,
            val vyAfter: Float,
        )

        private val gainPerStep = params.gravity * params.dt
        private var vyAtStepStart = 0f
        val hits = ArrayList<Hit>()

        override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
            hits += Hit(peg, impactSpeed, vyAtStepStart + gainPerStep, ball.vx, ball.vy)
        }

        override fun afterStep(world: PhysicsWorld) {
            vyAtStepStart = world.balls.firstOrNull()?.vy ?: 0f
        }
    }

    private companion object {
        /** Indices into [PhysicsWorld.walls], which [WallSegment.boardWalls] orders left then right. */
        const val LEFT = 0
        const val RIGHT = 1

        /**
         * Overlap a step may leave behind in the narrow V, u. Three passes leave ~4e-4 u there and
         * one pass ~0.49 u, so this sits an order of magnitude clear of both.
         */
        const val MAX_RESIDUAL_OVERLAP = 0.05f
    }
}
