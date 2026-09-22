package app.krafted.candytriangle.engine

import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.BoardConfig
import app.krafted.candytriangle.level.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [PhysicsWorld]'s step pipeline and ball lifecycle: gravity and integration, the terminal-speed
 * clamp, launch, exit, spawn and removal (between steps and from inside a listener), re-entrancy,
 * time, [PhysicsWorld.advance], and a determinism smoke test.
 *
 * Almost every board here is empty — a ball or two in free space — so each number asserted follows
 * from the pipeline alone. Contacts and the stuck rescue have their own suites
 * ([PhysicsWorldContactTest], [PhysicsWorldStuckTest]); the heavy determinism, tunnelling and
 * allocation suites belong to the engine verification tests.
 */
class PhysicsWorldTest {

    // -- integration ----------------------------------------------------------------------------

    /**
     * Semi-implicit Euler from rest: `vy_n = n g dt` and `y_n = y0 + g dt^2 n(n+1)/2`. The
     * `n(n+1)` (not explicit Euler's `n(n-1)`) is the signature of velocity-then-position.
     */
    @Test
    fun freeFallMatchesTheSemiImplicitEulerClosedForm() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        val y0 = 100f
        val ball = world.spawnBall(x = 300f, y = y0, vx = 0f, vy = 0f)
        val g = params.gravity.toDouble()
        val dt = 1.0 / params.stepsPerSecond

        // 120 steps reach 700 u/s: well under the terminal speed and well inside the board.
        for (n in 1..120) {
            val yBefore = ball.y
            world.step()
            assertEquals("prevY is the centre at the start of step $n", yBefore, ball.prevY, 0f)
            assertEquals("vy after $n steps", n * g * dt, ball.vy.toDouble(), 1e-2)
            assertEquals(
                "y after $n steps",
                y0 + g * dt * dt * n * (n + 1) / 2,
                ball.y.toDouble(),
                1e-2,
            )
            assertEquals("no horizontal drift", 300f, ball.x, 0f)
        }
    }

    @Test
    fun theFirstStepFromRestAlreadyMovesTheBall() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        val ball = world.spawnBall(x = 300f, y = 100f, vx = 0f, vy = 0f)

        world.step()

        val gainPerStep = params.gravity * params.dt
        assertEquals("velocity first: vy = g dt", gainPerStep, ball.vy, 0f)
        assertEquals("then position, with the new velocity", 100f + gainPerStep * params.dt, ball.y, 0f)
    }

    @Test
    fun ballsDoNotCollideWithEachOther() {
        val world = emptyWorld()
        val a = world.spawnBall(x = 500f, y = 300f, vx = 40f, vy = 0f)
        val b = world.spawnBall(x = 500f, y = 300f, vx = 40f, vy = 0f)

        repeat(100) { world.step() }

        assertEquals("coincident balls pass through each other: x", a.x, b.x, 0f)
        assertEquals("coincident balls pass through each other: y", a.y, b.y, 0f)
        assertEquals("and keep identical velocities", a.vy, b.vy, 0f)
    }

    // -- terminal speed -------------------------------------------------------------------------

    @Test
    fun aBallSpawnedAt5000UnitsPerSecondMovesAtMostMaxStepDisplacementInItsFirstStep() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        // A 3-4-5 velocity: 5000 u/s, over three times the terminal speed.
        val ball = world.spawnBall(x = 500f, y = 400f, vx = 3000f, vy = 4000f)

        world.step()

        val moved = hypot(ball.x - ball.prevX, ball.y - ball.prevY)
        assertTrue(
            "moved $moved u, over maxStepDisplacement ${params.maxStepDisplacement}",
            moved <= params.maxStepDisplacement + EPSILON,
        )
        assertTrue("§3.3: one step stays under the smallest collider radius", moved < params.pegRadius)
        assertEquals("clamped to exactly the terminal speed", params.maxBallSpeed, ball.speed, 0.01f)
        assertTrue("the clamp keeps the direction", ball.vx > 0f && ball.vy > ball.vx)
    }

    @Test
    fun gravityCannotPushABallPastTheTerminalSpeed() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        val ball = world.spawnBall(x = 500f, y = 200f, vx = 0f, vy = params.maxBallSpeed)

        repeat(20) { n ->
            world.step()
            assertEquals("vy after step ${n + 1}", params.maxBallSpeed, ball.vy, 0.01f)
            assertTrue(
                "step ${n + 1} moved further than maxStepDisplacement",
                ball.y - ball.prevY <= params.maxStepDisplacement + EPSILON,
            )
        }
    }

    // -- launch --------------------------------------------------------------------------------------

    @Test
    fun launchStraightDownFiresFromTheSpawnPointAtExactlyTheLaunchSpeed() {
        val params = PhysicsParams()
        val world = emptyWorld(params)

        val ball = world.launch(0f)

        assertEquals("spawn x", params.spawnX, ball.x, 0f)
        assertEquals("spawn y", params.spawnY, ball.y, 0f)
        assertEquals("aim 0 has exactly no sideways component", 0f, ball.vx, 0f)
        assertEquals("aim 0 is exactly the launch speed, downward", params.launchSpeed, ball.vy, 0f)
        assertEquals("radius from params", params.ballRadius, ball.radius, 0f)
        assertEquals("default skin", BallSkin.DEFAULT, ball.skin)
        assertEquals("interpolation starts at the spawn point: prevX", ball.x, ball.prevX, 0f)
        assertEquals("interpolation starts at the spawn point: prevY", ball.y, ball.prevY, 0f)
        assertTrue("active", ball.active)
        assertEquals("joins balls immediately between steps", listOf(ball), world.balls)
        assertTrue("a drop is in progress", world.hasActiveBalls)
    }

    @Test
    fun launchAimIsMeasuredFromStraightDownPositiveTowardPlusX() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        val thirty = Math.toRadians(30.0).toFloat()

        val right = world.launch(thirty)
        val left = world.launch(-thirty)

        assertEquals("vx at +30 deg", params.launchSpeed * sin(PI / 6), right.vx.toDouble(), 1e-3)
        assertEquals("vy at +30 deg", params.launchSpeed * cos(PI / 6), right.vy.toDouble(), 1e-3)
        assertEquals("mirrored aims launch exactly mirrored balls", -right.vx, left.vx, 0f)
        assertEquals("with the same downward speed", right.vy, left.vy, 0f)
    }

    @Test
    fun launchBeyondSeventyDegreesIsClampedAndANonFiniteAimFiresStraightDown() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        val seventy = Math.toRadians(70.0)

        val atClamp = world.launch(params.aimClampRadians)
        assertEquals("vx at 70 deg", params.launchSpeed * sin(seventy), atClamp.vx.toDouble(), 1e-2)
        assertEquals("vy at 70 deg", params.launchSpeed * cos(seventy), atClamp.vy.toDouble(), 1e-2)

        for (aim in floatArrayOf(1.5f, 3.1f, 10f)) {
            val wide = world.launch(aim)
            assertEquals("aim $aim clamps to 70 deg: vx", atClamp.vx, wide.vx, 0f)
            assertEquals("aim $aim clamps to 70 deg: vy", atClamp.vy, wide.vy, 0f)
        }
        val wideLeft = world.launch(-1.5f)
        assertEquals("-1.5 rad clamps to -70 deg: vx", -atClamp.vx, wideLeft.vx, 0f)
        assertEquals("-1.5 rad clamps to -70 deg: vy", atClamp.vy, wideLeft.vy, 0f)

        for (aim in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val straight = world.launch(aim)
            assertEquals("aim $aim degrades to straight down: vx", 0f, straight.vx, 0f)
            assertEquals("aim $aim degrades to straight down: vy", params.launchSpeed, straight.vy, 0f)
        }
    }

    @Test
    fun ballIdsFollowSpawnOrderAndAreNeverReused() {
        val world = emptyWorld()

        val first = world.launch(0f)
        val second = world.spawnBall(x = 400f, y = 300f, vx = 0f, vy = 0f)
        val third = world.launch(0.2f, BallSkin.GOLD)

        assertEquals("ids 0, 1, 2 in spawn order", listOf(0, 1, 2), listOf(first.id, second.id, third.id))
        assertEquals("balls in spawn order", listOf(first, second, third), world.balls)
        assertEquals("the skin is carried", BallSkin.GOLD, third.skin)

        world.removeBall(second)
        val fourth = world.spawnBall(x = 600f, y = 300f, vx = 0f, vy = 0f)
        assertEquals("a removed ball's id is not reused", 3, fourth.id)
        assertEquals("order preserved across a removal", listOf(0, 2, 3), world.balls.map { it.id })
    }

    // -- exit ----------------------------------------------------------------------------------------

    @Test
    fun aBallFallingThroughTheOpenBaseExitsExactlyOnce() {
        val params = PhysicsParams()
        val log = EventLog()
        val world = emptyWorld(params, log)
        val ball = world.spawnBall(x = 500f, y = 1250f, vx = 0f, vy = 300f)

        runUntilNoActiveBalls(world)

        assertEquals("exactly one exit, for this ball", listOf(ball.id), log.exits.map { it.ballId })
        assertFalse("deactivated", ball.active)
        assertTrue("removed from balls", world.balls.isEmpty())
        assertFalse("the drop has ended", world.hasActiveBalls)
        assertTrue(
            "it exits on the step its centre passes exitY (prevY ${ball.prevY}, y ${ball.y})",
            ball.prevY <= params.exitY && ball.y > params.exitY,
        )

        repeat(50) { world.step() }
        assertEquals("never reported again", 1, log.exits.size)
    }

    @Test
    fun exitNeedsTheCentreStrictlyPastExitY() {
        val params = PhysicsParams(gravity = 0f)
        val log = EventLog()
        val world = emptyWorld(params, log)
        val ball = world.spawnBall(x = 500f, y = params.exitY, vx = 0f, vy = 0f)

        world.step()
        assertTrue("a centre exactly on exitY is still on the board", ball.active)
        assertTrue(log.exits.isEmpty())

        ball.vy = 1f
        world.step()
        assertFalse("a centre past exitY has left", ball.active)
        assertEquals(listOf(ball.id), log.exits.map { it.ballId })
    }

    /** Zero crash tolerance (§13): garbage state leaves the board instead of poisoning it. */
    @Test
    fun aNonFiniteBallIsExitedDefensivelyOnItsFirstStep() {
        val log = EventLog()
        val world = emptyWorld(listener = log)
        world.spawnBall(x = 500f, y = 500f, vx = Float.NaN, vy = 0f)
        world.spawnBall(x = Float.POSITIVE_INFINITY, y = 500f, vx = 0f, vy = 0f)
        world.spawnBall(x = 500f, y = 500f, vx = 0f, vy = Float.NEGATIVE_INFINITY)
        world.spawnBall(x = 500f, y = Float.NaN, vx = 0f, vy = 0f)
        val healthy = world.spawnBall(x = 500f, y = 500f, vx = 0f, vy = 0f)

        world.step()

        assertEquals("each exited once, in spawn order", listOf(0, 1, 2, 3), log.exits.map { it.ballId })
        assertEquals("all on the first step", setOf(0), log.exits.map { it.step }.toSet())
        assertEquals("only the healthy ball remains", listOf(healthy), world.balls)
        assertTrue("and it is still in play", world.hasActiveBalls)
        assertTrue("garbage state is never reported as a rescue", log.kicks.isEmpty())
    }

    // -- removal between steps ----------------------------------------------------------------------

    @Test
    fun removeBallBetweenStepsTakesEffectImmediatelyAndSilently() {
        val log = EventLog()
        val world = emptyWorld(listener = log)
        val a = world.spawnBall(x = 400f, y = 300f, vx = 0f, vy = 0f)
        val b = world.spawnBall(x = 600f, y = 300f, vx = 0f, vy = 0f)

        world.removeBall(a)
        assertFalse("deactivated", a.active)
        assertEquals("gone from balls at once", listOf(b), world.balls)
        assertTrue("b is still in play", world.hasActiveBalls)

        world.removeBall(a)
        assertEquals("idempotent", listOf(b), world.balls)

        world.removeBall(b)
        assertFalse("nothing left in play", world.hasActiveBalls)

        world.step()
        assertTrue("removal never reports an exit", log.exits.isEmpty())
        assertEquals("a removed ball is frozen where it was", 300f, a.y, 0f)
    }

    @Test
    fun removeBallIgnoresABallThisWorldDoesNotHold() {
        val mine = emptyWorld()
        val other = emptyWorld()
        val theirs = other.spawnBall(x = 500f, y = 300f, vx = 0f, vy = 0f)

        mine.removeBall(theirs)

        assertTrue("another world's ball is not deactivated", theirs.active)
        assertEquals("nor removed from its own world", listOf(theirs), other.balls)
    }

    // -- listener mutations mid-step ------------------------------------------------------------------

    @Test
    fun aBallSpawnedFromAfterStepJoinsAtTheEndOfTheStepAndFirstMovesOnTheNext() {
        // No other ball, so hasActiveBalls during the step can only be answering for the pending one.
        val world = emptyWorld()
        var spawned: Ball? = null
        var listedDuringTheStep = true
        var countedAsActiveDuringTheStep = false
        world.listener = object : PhysicsListener {
            override fun afterStep(world: PhysicsWorld) {
                if (spawned != null) return
                val ball = world.spawnBall(x = 250f, y = 400f, vx = 0f, vy = 0f)
                spawned = ball
                listedDuringTheStep = ball in world.balls
                countedAsActiveDuringTheStep = world.hasActiveBalls
            }
        }

        world.step()

        val ball = checkNotNull(spawned) { "afterStep never ran" }
        assertFalse("not in balls while its step is still running", listedDuringTheStep)
        assertTrue("but already counted as waiting to join", countedAsActiveDuringTheStep)
        assertEquals("joined at the end of the step", listOf(ball), world.balls)
        assertEquals("it has not moved yet", 400f, ball.y, 0f)
        assertEquals("nor gained velocity", 0f, ball.vy, 0f)

        world.step()
        assertEquals("it first moves on the next step", 400f, ball.prevY, 0f)
        assertTrue("it first moves on the next step", ball.y > 400f)
    }

    @Test
    fun removeBallDuringAStepTakesEffectAtItsEndWithoutAnExitEvent() {
        val log = EventLog()
        val world = emptyWorld()
        val keeper = world.spawnBall(x = 400f, y = 300f, vx = 0f, vy = 0f)
        val target = world.spawnBall(x = 600f, y = 300f, vx = 0f, vy = 0f)
        var listedAfterRemoval = false
        var activeAfterRemoval = true
        world.listener = object : PhysicsListener by log {
            override fun afterStep(world: PhysicsWorld) {
                log.afterStep(world)
                if (world.stepCount != 2L) return
                world.removeBall(target)
                world.removeBall(target) // idempotent mid-step too
                listedAfterRemoval = target in world.balls
                activeAfterRemoval = target.active
            }
        }

        repeat(5) { world.step() }

        assertTrue("still listed until the step ends", listedAfterRemoval)
        assertFalse("but deactivated at once", activeAfterRemoval)
        assertEquals("dropped at the end of that step", listOf(keeper), world.balls)
        assertTrue("removal never reports an exit", log.exits.isEmpty())
    }

    @Test
    fun aBallRemovedBeforeItsTurnInAStepIsNotMoved() {
        val params = PhysicsParams()
        val log = EventLog()
        val world = emptyWorld(params)
        // Ball 0 leaves on the first step; its exit callback removes ball 1, whose turn is next.
        val leaver = world.spawnBall(x = 500f, y = params.exitY - 1f, vx = 0f, vy = 1000f)
        val bystander = world.spawnBall(x = 300f, y = 300f, vx = 0f, vy = 0f)
        world.listener = object : PhysicsListener by log {
            override fun onBallExited(ball: Ball) {
                log.onBallExited(ball)
                world.removeBall(bystander)
            }
        }

        world.step()

        assertEquals("only the leaver exited", listOf(leaver.id), log.exits.map { it.ballId })
        assertEquals("the bystander was skipped: no gravity", 0f, bystander.vy, 0f)
        assertEquals("the bystander was skipped: no movement", 300f, bystander.y, 0f)
        assertTrue("both are gone", world.balls.isEmpty())
    }

    @Test
    fun aBallSpawnedAndRemovedInTheSameStepNeverJoins() {
        val world = emptyWorld()
        world.listener = object : PhysicsListener {
            override fun afterStep(world: PhysicsWorld) {
                if (world.stepCount != 0L) return
                val ghost = world.spawnBall(x = 500f, y = 300f, vx = 0f, vy = 0f)
                world.removeBall(ghost)
            }
        }

        world.step()

        assertTrue("never joined", world.balls.isEmpty())
        assertFalse("and is not waiting to", world.hasActiveBalls)
        assertEquals("its id was still spent", 1, world.spawnBall(500f, 300f, 0f, 0f).id)
    }

    /**
     * Pipeline step 4 keeps the survivors in spawn order and appends newcomers after them — the
     * order the next step iterates, so it is part of the determinism contract. Two identical worlds
     * cannot catch a reordering (both would reorder alike), so this pins it directly.
     */
    @Test
    fun survivingBallsKeepSpawnOrderAcrossSteps() {
        val params = PhysicsParams()
        val world = emptyWorld(params)
        val spawned = (0 until 5).map { i ->
            // Ball 1 starts just above exitY, moving down: it leaves on the first step.
            if (i == 1) world.spawnBall(x = 400f, y = params.exitY - 1f, vx = 0f, vy = 1000f)
            else world.spawnBall(x = 300f + 100f * i, y = 300f, vx = 0f, vy = 0f)
        }
        world.listener = object : PhysicsListener {
            override fun afterStep(world: PhysicsWorld) {
                when (world.stepCount) {
                    0L -> world.removeBall(spawned[3])
                    1L -> world.spawnBall(x = 250f, y = 300f, vx = 0f, vy = 0f)
                }
            }
        }

        world.step()
        assertEquals("after an exit and a removal", listOf(0, 2, 4), world.balls.map { it.id })

        world.step()
        assertEquals("a newcomer is appended", listOf(0, 2, 4, 5), world.balls.map { it.id })

        repeat(20) { n ->
            world.step()
            assertEquals("still in spawn order after step ${n + 3}", listOf(0, 2, 4, 5), world.balls.map { it.id })
        }
    }

    @Test
    fun stepOrAdvanceFromInsideACallbackThrows() {
        val world = emptyWorld()
        var reenter: (PhysicsWorld) -> Unit = { it.step() }
        world.listener = object : PhysicsListener {
            override fun afterStep(world: PhysicsWorld) = reenter(world)
        }

        assertThrows("a re-entrant step()", IllegalStateException::class.java) { world.step() }

        reenter = { it.advance(1_000_000_000L) }
        assertThrows("a re-entrant advance()", IllegalStateException::class.java) { world.step() }

        // A refused re-entry must not wedge the world: once the listener behaves, stepping works.
        reenter = {}
        world.step()
        assertEquals("only the completed step counts", 1L, world.stepCount)
    }

    // -- time ------------------------------------------------------------------------------------------

    @Test
    fun timeIsDerivedFromTheStepCounter() {
        val world = emptyWorld()
        assertEquals(0L, world.stepCount)
        assertEquals(0.0, world.timeSeconds, 0.0)

        repeat(7) { world.step() }
        assertEquals(7L, world.stepCount)
        assertEquals("stepCount / stepsPerSecond", 7.0 / 240, world.timeSeconds, 0.0)

        repeat(473) { world.step() }
        assertEquals("480 steps are exactly 2 s, with no drift", 2.0, world.timeSeconds, 0.0)
    }

    // -- advance -------------------------------------------------------------------------------------

    @Test
    fun advanceRunsFourStepsPerSixtyHertzFrame() {
        val world = emptyWorld()
        val ball = world.spawnBall(x = 500f, y = 200f, vx = 0f, vy = 0f)

        repeat(60) { frame ->
            assertEquals("steps released by frame $frame", 4, world.advance(SIXTY_HZ_FRAME_NANOS))
            val alpha = world.interpolationAlpha
            assertTrue("alpha $alpha after frame $frame is in [0, 1)", alpha >= 0f && alpha < 1f)
        }
        assertEquals("one second of frames is 240 steps", 240L, world.stepCount)

        // advance() runs exactly the steps it reports, no more and no fewer.
        val reference = emptyWorld()
        val referenceBall = reference.spawnBall(x = 500f, y = 200f, vx = 0f, vy = 0f)
        repeat(240) { reference.step() }
        assertEquals("same state as 240 direct steps", referenceBall.y.toRawBits(), ball.y.toRawBits())
    }

    @Test
    fun advanceBanksPartialStepsAndResetTimestepDiscardsThem() {
        val world = emptyWorld()

        // 2 ms is 0.48 of a 4.1667 ms step: nothing runs, the remainder is banked.
        assertEquals(0, world.advance(2_000_000L))
        assertEquals(0L, world.stepCount)
        assertEquals("alpha is the banked share of a step", 0.48f, world.interpolationAlpha, 1e-3f)

        // Another 2.5 ms completes the step: 4.5 ms banked, 1 step run, 0.08 of one left over.
        assertEquals(1, world.advance(2_500_000L))
        assertEquals(1L, world.stepCount)
        assertEquals(0.08f, world.interpolationAlpha, 1e-3f)

        // Banked time is discarded on reset (resume from pause), so the same 2.5 ms no longer
        // completes a step.
        assertEquals(0, world.advance(2_000_000L))
        world.resetTimestep()
        assertEquals("nothing banked after a reset", 0f, world.interpolationAlpha, 0f)
        assertEquals(0, world.advance(2_500_000L))
        assertEquals("the simulation itself is untouched by a reset", 1L, world.stepCount)
    }

    // -- construction --------------------------------------------------------------------------------

    @Test
    fun createBuildsTheConfiguredBoard() {
        val world = PhysicsWorld.create(GameConfig.DEFAULTS, pegs = emptyList())

        assertEquals("params resolved from the config", PhysicsParams.from(GameConfig.DEFAULTS), world.params)
        assertEquals("the two §3.1 walls", 2, world.walls.size)
        assertTrue("left wall first", world.walls[0].x2 < world.walls[1].x2)
        assertEquals("walls use restitutionWall", world.params.restitutionWall, world.walls[0].restitution, 0f)
        assertTrue("no balls yet", world.balls.isEmpty())
        assertFalse(world.hasActiveBalls)
    }

    // -- determinism smoke ---------------------------------------------------------------------------

    /**
     * Two worlds, identical inputs, identical calls: ball state bit-identical after every step and
     * the same event stream. A smoke test only — the §11 determinism suite is the heavy version.
     */
    @Test
    fun identicalWorldsGivenIdenticalCallsStayBitIdentical() {
        val logA = EventLog()
        val logB = EventLog()
        val a = smokeBoard(logA)
        val b = smokeBoard(logB)
        val aims = floatArrayOf(0.31f, -0.52f, 1.1f)

        for (step in 0 until 3 * 800) {
            if (step % 800 == 0) {
                val aim = aims[step / 800]
                a.launch(aim)
                b.launch(aim)
            }
            a.step()
            b.step()
            assertSameBalls("after step ${step + 1}", a, b)
        }

        assertTrue("the smoke test must actually hit pegs", logA.pegHits.isNotEmpty())
        assertEquals(
            "identical event streams",
            logA.all.map(Any::toString),
            logB.all.map(Any::toString),
        )
    }

    // -- helpers -------------------------------------------------------------------------------------

    /**
     * Records every event as plain data, in firing order. `step` on each record is the 0-based
     * index of the step it came from: the number of [afterStep] calls seen before it. Values like
     * `vxAfter` are the ball's state when the callback ran, i.e. after the bounce.
     *
     * Nested here so the other `PhysicsWorld*Test` suites can share it without claiming a
     * package-level name.
     */
    class EventLog : PhysicsListener {

        data class PegHit(
            val step: Int,
            val ballId: Int,
            val peg: Peg,
            val impactSpeed: Float,
            val vxAfter: Float,
            val vyAfter: Float,
            val pegVx: Float,
        )

        data class WallHit(
            val step: Int,
            val ballId: Int,
            val wall: WallSegment,
            val impactSpeed: Float,
            val vxAfter: Float,
            val vyAfter: Float,
        )

        data class Kick(val step: Int, val ballId: Int, val x: Float, val vx: Float, val vy: Float)

        data class Exit(val step: Int, val ballId: Int)

        var stepIndex = 0
            private set
        val pegHits = ArrayList<PegHit>()
        val wallHits = ArrayList<WallHit>()
        val kicks = ArrayList<Kick>()
        val exits = ArrayList<Exit>()

        /** Every record above, interleaved in the order the callbacks fired. */
        val all = ArrayList<Any>()

        override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
            val hit = PegHit(stepIndex, ball.id, peg, impactSpeed, ball.vx, ball.vy, peg.vx)
            pegHits += hit
            all += hit
        }

        override fun onWallContact(ball: Ball, wall: WallSegment, impactSpeed: Float) {
            val hit = WallHit(stepIndex, ball.id, wall, impactSpeed, ball.vx, ball.vy)
            wallHits += hit
            all += hit
        }

        override fun onStuckImpulse(ball: Ball) {
            val kick = Kick(stepIndex, ball.id, ball.x, ball.vx, ball.vy)
            kicks += kick
            all += kick
        }

        override fun onBallExited(ball: Ball) {
            val exit = Exit(stepIndex, ball.id)
            exits += exit
            all += exit
        }

        override fun afterStep(world: PhysicsWorld) {
            stepIndex++
        }
    }

    companion object {

        /** 1/60 s rounded up to a whole nanosecond, so each frame banks a hair over 4 steps. */
        private const val SIXTY_HZ_FRAME_NANOS = 16_666_667L

        /** Float slack on a displacement bound, u. */
        private const val EPSILON = 1e-3f

        /** A world with no walls and no pegs. */
        fun emptyWorld(
            params: PhysicsParams = PhysicsParams(),
            listener: PhysicsListener = PhysicsListener.NONE,
        ) = PhysicsWorld(params, walls = emptyList(), pegs = emptyList(), listener = listener)

        /** Steps until the drop ends; fails rather than spinning forever. */
        fun runUntilNoActiveBalls(world: PhysicsWorld, maxSteps: Int = 20_000) {
            var steps = 0
            while (world.hasActiveBalls) {
                check(steps++ < maxSteps) { "the drop did not end within $maxSteps steps" }
                world.step()
            }
        }

        /**
         * The real §3.1 walls and a small lattice with one gem: rows 69.28 u apart (d = 80), pegs
         * kept at least 50 u horizontally inside the walls.
         */
        private fun smokeBoard(listener: PhysicsListener): PhysicsWorld {
            val params = PhysicsParams()
            val pegs = ArrayList<Peg>()
            for (row in 0 until 10) {
                val y = 250f + row * 69.28f
                val offset = if (row % 2 == 0) 0f else 40f
                for (col in -6..6) {
                    val x = 500f + offset + col * 80f
                    if (abs(x - 500f) > y * 0.4f - 50f) continue
                    val gem = row == 5 && col == 0
                    pegs += Peg(
                        id = pegs.size,
                        x = x,
                        y = y,
                        radius = if (gem) params.gemRadius else params.pegRadius,
                        restitution = if (gem) params.restitutionGem else params.restitutionPeg,
                        kind = if (gem) ColliderKind.GEM else ColliderKind.PEG,
                    )
                }
            }
            return PhysicsWorld(
                params = params,
                walls = WallSegment.boardWalls(BoardConfig(), params.restitutionWall),
                pegs = pegs,
                listener = listener,
            )
        }

        private fun assertSameBalls(message: String, a: PhysicsWorld, b: PhysicsWorld) {
            assertEquals("$message: ball count", a.balls.size, b.balls.size)
            for (i in a.balls.indices) {
                val x = a.balls[i]
                val y = b.balls[i]
                assertEquals("$message: id", x.id, y.id)
                assertEquals("$message: ball ${x.id} x", x.x.toRawBits(), y.x.toRawBits())
                assertEquals("$message: ball ${x.id} y", x.y.toRawBits(), y.y.toRawBits())
                assertEquals("$message: ball ${x.id} vx", x.vx.toRawBits(), y.vx.toRawBits())
                assertEquals("$message: ball ${x.id} vy", x.vy.toRawBits(), y.vy.toRawBits())
                assertEquals("$message: ball ${x.id} stuckSteps", x.stuckSteps, y.stuckSteps)
            }
        }
    }
}
