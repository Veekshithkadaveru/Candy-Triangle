package app.krafted.candytriangle.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * §11 #1, determinism: the same shot on the same board produces the same trajectory and the same
 * events every time — §1 pillar 1, "aim is the skill".
 *
 * §11 asks for agreement to 1e-5. These tests demand more: every float's **bits**, every event, in
 * the same order — which is what [PhysicsWorld]'s contract actually promises, and what a player
 * replaying a shot sees.
 *
 * ## What a run records
 *
 * A [Trace] is a flat int stream written by a recording [PhysicsListener]:
 * - every event, in the order the step fires it — its type, the ball's id, the peg id or wall
 *   index, the impact speed's bits, and the ball's position and velocity bits at that moment;
 * - at the end of every step (`afterStep`), a snapshot — the step count, then each ball's id, x, y,
 *   vx, vy, prevX, prevY, stuck counter and active flag, then every moving peg's x and vx.
 *
 * ## The scenario
 *
 * A fresh world over a [TestBoards] board, a ball launched at the aim before the first step, and a
 * second ball at the mirrored aim launched from inside the listener at step [SECOND_LAUNCH_STEP] —
 * so every run also exercises two balls in flight and the mid-step spawn path. A run ends once both
 * balls have left, or after [STEP_CAP] steps (60 simulated seconds).
 */
class DeterminismTest {

    // -- 100 identical runs (§11 #1) -----------------------------------------------------------

    @Test
    fun everyShotReplaysBitIdenticallyAHundredTimes() {
        var totalSteps = 0L
        for (spec in TestBoards.ALL) {
            for (aimDegrees in AIMS_DEGREES) {
                val label = "$spec, aim $aimDegrees deg"
                val reference = Scenario(spec, aimDegrees).runWithStep()
                assertTrue("$label: the reference run never touched a peg", reference.pegContacts > 0)
                assertEquals(
                    "$label: both balls must leave within 60 s (stopped at step " +
                        "${reference.world.stepCount})",
                    2,
                    reference.exits,
                )

                val replay = Trace()
                for (run in 2..RUNS) {
                    replay.clear()
                    Scenario(spec, aimDegrees, replay).runWithStep()
                    assertSameTrace("$label, run $run of $RUNS", reference.trace, replay)
                }
                totalSteps += reference.world.stepCount * RUNS
            }
        }
        println(
            "DeterminismTest: ${TestBoards.ALL.size} boards x ${AIMS_DEGREES.size} aims x " +
                "$RUNS runs, $totalSteps steps, all bit-identical",
        )
    }

    // -- frame pacing --------------------------------------------------------------------------

    /**
     * [PhysicsWorld.advance] decides only *when* steps run, never *what* they compute: driven by an
     * irregular frame stream — zero, negative, sub-step and capped frames included — a world must
     * be in exactly the state a plain [PhysicsWorld.step] loop reaches at every equal step count,
     * and must have fired exactly the same events.
     */
    @Test
    fun advanceWithIrregularFramesMatchesAPlainStepLoopAtEveryStepCount() {
        for (spec in TestBoards.ALL) {
            for (aimDegrees in ADVANCE_AIMS_DEGREES) {
                val label = "$spec, aim $aimDegrees deg"
                val stepped = Scenario(spec, aimDegrees)
                val advanced = Scenario(spec, aimDegrees)
                var frame = 0
                while (!advanced.done) {
                    val before = advanced.world.stepCount
                    val released = advanced.world.advance(FRAMES_NANOS[frame % FRAMES_NANOS.size])
                    frame++
                    assertEquals(
                        "$label, frame $frame: advance returns the steps it ran",
                        advanced.world.stepCount - before,
                        released.toLong(),
                    )
                    while (stepped.world.stepCount < advanced.world.stepCount) stepped.world.step()
                    assertSameState(
                        "$label, frame $frame, step ${advanced.world.stepCount}",
                        stepped.world,
                        advanced.world,
                    )
                }
                assertSameTrace("$label: events and in-step snapshots", stepped.trace, advanced.trace)
            }
        }
    }

    // -- no shared hidden state ----------------------------------------------------------------

    /**
     * Worlds share nothing: stepping several in lockstep — two of them the very same scenario —
     * leaves each exactly where it would have been alone. Hidden shared mutable state (a static
     * ball-id counter, a shared clock, pegs moved by the wrong world) would show up here.
     */
    @Test
    fun worldsSteppedInterleavedMatchTheirSoloRuns() {
        val solo = CROSS_CASES.map { (spec, aim) -> Scenario(spec, aim).runWithStep().trace }
        val together = CROSS_CASES.map { (spec, aim) -> Scenario(spec, aim) }
        while (together.any { !it.done }) {
            for (scenario in together) if (!scenario.done) scenario.world.step()
        }
        for (i in CROSS_CASES.indices) {
            assertSameTrace("interleaved case $i ${CROSS_CASES[i]}", solo[i], together[i].trace)
        }
    }

    /**
     * The same, across threads. A static scratch object — a shared [Contact], say — keeps the
     * single-threaded interleaving above green, because each test reads it back immediately; it
     * would corrupt a second world stepped on another thread (a test harness, or a trajectory
     * preview computed off the game thread).
     */
    @Test
    fun worldsSteppedOnSeparateThreadsMatchTheirSoloRuns() {
        val solo = CROSS_CASES.map { (spec, aim) -> Scenario(spec, aim).runWithStep().trace }
        val pool = Executors.newFixedThreadPool(CROSS_CASES.size)
        try {
            val start = CyclicBarrier(CROSS_CASES.size)
            val futures = CROSS_CASES.map { (spec, aim) ->
                pool.submit(
                    Callable {
                        val scenario = Scenario(spec, aim)
                        start.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        scenario.runWithStep().trace
                    },
                )
            }
            for ((i, future) in futures.withIndex()) {
                val trace = future.get(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertSameTrace("thread case $i ${CROSS_CASES[i]}", solo[i], trace)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // -- the scenario and its recorder ---------------------------------------------------------

    /** One shot on one board, recorded into [trace]. See the class docs. */
    private class Scenario(
        spec: TestBoards.Spec,
        aimDegrees: Float,
        val trace: Trace = Trace(),
    ) {
        private val recorder: Recorder
        val world: PhysicsWorld

        init {
            val aim = radians(aimDegrees)
            recorder = Recorder(trace, secondAim = -aim)
            world = TestBoards.world(spec, listener = recorder)
            recorder.walls = world.walls
            world.launch(aim)
        }

        val exits: Int get() = recorder.exits
        val pegContacts: Int get() = recorder.pegContacts

        /** Both balls are out (the second one has been launched), or the step cap is reached. */
        val done: Boolean
            get() = world.stepCount >= STEP_CAP ||
                (world.stepCount > SECOND_LAUNCH_STEP && !world.hasActiveBalls)

        fun runWithStep(): Scenario {
            while (!done) world.step()
            return this
        }
    }

    private class Recorder(
        private val trace: Trace,
        private val secondAim: Float,
    ) : PhysicsListener {

        var walls: List<WallSegment> = emptyList()
        var exits = 0
        var pegContacts = 0

        override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
            pegContacts++
            trace.event(EVENT_PEG, ball, peg.id, impactSpeed)
        }

        override fun onWallContact(ball: Ball, wall: WallSegment, impactSpeed: Float) {
            trace.event(EVENT_WALL, ball, walls.indexOfFirst { it === wall }, impactSpeed)
        }

        override fun onStuckImpulse(ball: Ball) = trace.event(EVENT_STUCK, ball, -1, 0f)

        override fun onBallExited(ball: Ball) {
            exits++
            trace.event(EVENT_EXIT, ball, -1, 0f)
        }

        override fun afterStep(world: PhysicsWorld) {
            trace.markStep()
            writeState(world, trace)
            // Mid-step spawn: the ball joins at the end of this step and first moves on the next.
            if (world.stepCount == SECOND_LAUNCH_STEP) world.launch(secondAim)
        }
    }

    /** A growable int stream, plus where each step's snapshot starts (for failure messages). */
    private class Trace {
        var data = IntArray(1 shl 16)
        var size = 0
        private var stepStarts = IntArray(1 shl 11)
        private var steps = 0

        fun clear() {
            size = 0
            steps = 0
        }

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun add(value: Float) = add(value.toRawBits())

        fun event(type: Int, ball: Ball, other: Int, impactSpeed: Float) {
            add(type)
            add(ball.id)
            add(other)
            add(impactSpeed)
            add(ball.x)
            add(ball.y)
            add(ball.vx)
            add(ball.vy)
        }

        fun markStep() {
            if (steps == stepStarts.size) stepStarts = stepStarts.copyOf(steps * 2)
            stepStarts[steps++] = size
        }

        /** The index of the step whose records contain position [index] of the stream. */
        fun stepContaining(index: Int): Int {
            var step = 0
            while (step < steps && stepStarts[step] <= index) step++
            return step
        }
    }

    private companion object {

        const val RUNS = 100

        /** Sixty simulated seconds at 240 Hz. */
        const val STEP_CAP = 60L * 240

        /** The second ball joins at the end of this step (0.375 s in). */
        const val SECOND_LAUNCH_STEP = 90L

        const val THREAD_TIMEOUT_SECONDS = 120L

        const val EVENT_PEG = 1
        const val EVENT_WALL = 2
        const val EVENT_STUCK = 3
        const val EVENT_EXIT = 4
        const val SNAPSHOT = 5

        /** A spread from wall to wall, including straight down onto the axis peg. */
        val AIMS_DEGREES = floatArrayOf(-70f, -33.3f, -5f, 0f, 12.5f, 45f, 70f)

        val ADVANCE_AIMS_DEGREES = floatArrayOf(-33.3f, 0f, 45f)

        /**
         * Irregular frame times, ns: 60, 120, 144 and 30 Hz frames, jitter, a zero-length frame, a
         * clock that steps backward, sub-step frames, and two hitches past the 12-step cap.
         */
        val FRAMES_NANOS = longArrayOf(
            16_666_667L, 8_333_333L, 0L, 33_333_334L, 4_166_666L, 1_000_000L, 50_000_000L,
            6_944_444L, 11_111_111L, 16_666_666L, -5_000_000L, 2_500_000L, 100_000_000L,
            7_000_000L, 17_900_000L, 15_300_000L, 4_166_667L, 20_833_333L,
        )

        /** Mixed boards, and one scenario twice: identical twins must stay identical. */
        val CROSS_CASES = listOf(
            TestBoards.D90 to 12.5f,
            TestBoards.D66_GEMS to -33.3f,
            TestBoards.D78_MOVING to 45f,
            TestBoards.D90 to 12.5f,
        )

        fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

        fun writeState(world: PhysicsWorld, out: Trace) {
            out.add(SNAPSHOT)
            out.add(world.stepCount.toInt())
            out.add(world.balls.size)
            for (ball in world.balls) {
                out.add(ball.id)
                out.add(ball.x)
                out.add(ball.y)
                out.add(ball.vx)
                out.add(ball.vy)
                out.add(ball.prevX)
                out.add(ball.prevY)
                out.add(ball.stuckSteps)
                out.add(if (ball.active) 1 else 0)
            }
            for (peg in world.pegs) {
                if (peg.motion == null) continue
                out.add(peg.x)
                out.add(peg.vx)
            }
        }

        fun assertSameTrace(message: String, expected: Trace, actual: Trace) {
            val shorter = minOf(expected.size, actual.size)
            var i = 0
            while (i < shorter && expected.data[i] == actual.data[i]) i++
            if (i == expected.size && i == actual.size) return
            fail(
                "$message: the runs diverge at int $i of ${expected.size} (vs ${actual.size}), " +
                    "during step ${expected.stepContaining(i)}",
            )
        }

        /** The two worlds' between-steps state, bit for bit. */
        fun assertSameState(message: String, expected: PhysicsWorld, actual: PhysicsWorld) {
            val a = Trace().also { writeState(expected, it) }
            val b = Trace().also { writeState(actual, it) }
            assertSameTrace(message, a, b)
        }
    }
}
