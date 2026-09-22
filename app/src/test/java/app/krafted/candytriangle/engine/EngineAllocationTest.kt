package app.krafted.candytriangle.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * The step must not allocate ([PhysicsWorld]'s contract): it runs about 240 times a second on the
 * game thread, and a GC pause there is a dropped frame (§13's 60 fps budget).
 *
 * Measured with HotSpot's per-thread allocation counter over real drops — 1 to 3 balls in flight,
 * through peg, gem and wall contacts, the stuck rescue and exits, driven by both
 * [PhysicsWorld.step] and [PhysicsWorld.advance]. Balls are launched *between* measurement windows
 * (a ball is an object; spawning one is supposed to allocate). A per-step allocation of even one
 * small object adds up to hundreds of kilobytes over these runs; [BUDGET_BYTES] only absorbs the
 * JVM's own noise.
 *
 * Skipped (not failed) on a JVM without per-thread allocation accounting.
 */
class EngineAllocationTest {

    @Test
    fun steppingStaticBoardsAllocatesNothing() {
        val result = measure(listOf(TestBoards.D90, TestBoards.D66_GEMS), minSteps = 10_000)
        println("EngineAllocationTest, static boards: $result")
        assertTrue("the step allocates: $result", result.bytes < BUDGET_BYTES)
    }

    /**
     * The same on a board with two moving rows (§3.3).
     *
     * This is the test that caught [Peg.updateMotion] calling `StrictMath.sin`/`cos` per moving peg
     * per step: since JDK 21 `StrictMath` is a pure-Java fdlibm port that allocates a `double[2]`
     * (32 B) on every call, whatever the argument — 576 B a step on this 9-peg board. The per-step
     * trig now goes through [DeterministicTrig], which is plain double arithmetic, so the moving
     * board must allocate nothing, exactly like the static ones.
     */
    @Test
    fun steppingMovingPegRowsAllocatesNothing() {
        val result = measure(listOf(TestBoards.D78_MOVING), minSteps = 5_000)
        println("EngineAllocationTest, moving rows: $result")
        assertTrue("the step allocates: $result", result.bytes < BUDGET_BYTES)
    }

    // -- measurement ---------------------------------------------------------------------------

    private class Result(
        val bytes: Long,
        val steps: Long,
        val windows: Int,
        val worstWindowBytes: Long,
        val movingPegs: Int,
    ) {
        override fun toString(): String =
            "$bytes bytes over $steps steps (${"%.2f".format(bytes.toDouble() / steps)} B/step) in " +
                "$windows windows, worst window $worstWindowBytes bytes, $movingPegs moving pegs, " +
                "Java ${System.getProperty("java.version")}"
    }

    private fun measure(specs: List<TestBoards.Spec>, minSteps: Long): Result {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            "this JVM has no per-thread allocation counter",
            bean != null &&
                bean.isThreadAllocatedMemorySupported &&
                bean.isThreadAllocatedMemoryEnabled,
        )
        val counter = checkNotNull(bean)
        @Suppress("DEPRECATION") // Thread.threadId() is JDK 19+; the tests target 11.
        val thread = Thread.currentThread().id

        val worlds = specs.map { TestBoards.world(it) }

        // Warm-up, unmeasured: every class and branch the measured windows will touch gets loaded
        // and linked, including the counter call itself.
        counter.getThreadAllocatedBytes(thread)
        for ((index, world) in worlds.withIndex()) {
            for (drop in 0 until WARM_UP_DROPS) {
                runDrop(world, window = index * WARM_UP_DROPS + drop) {
                    counter.getThreadAllocatedBytes(thread)
                }
            }
        }

        var steps = 0L
        var bytes = 0L
        var worst = 0L
        var windows = 0
        while (steps < minSteps) {
            val world = worlds[windows % worlds.size]
            steps += runDrop(world, window = windows) { counter.getThreadAllocatedBytes(thread) }
            bytes += lastWindowBytes
            worst = maxOf(worst, lastWindowBytes)
            windows++
        }
        val movingPegs = worlds.sumOf { world -> world.pegs.count { it.motion != null } }
        return Result(bytes, steps, windows, worst, movingPegs)
    }

    /** The bytes the last [runDrop] window allocated. */
    private var lastWindowBytes = 0L

    /**
     * Launches 1 to 3 balls on [world] (unmeasured), then runs the drop to its end inside one
     * measurement window read through [allocatedBytes]. Returns the steps run; the window's bytes
     * land in [lastWindowBytes]. Odd windows are driven frame by frame through
     * [PhysicsWorld.advance].
     */
    private inline fun runDrop(world: PhysicsWorld, window: Int, allocatedBytes: () -> Long): Int {
        val balls = 1 + window % 3
        for (b in 0 until balls) {
            world.launch(radians(AIMS_DEGREES[(window * 3 + b) % AIMS_DEGREES.size]))
        }
        val byAdvance = window % 2 == 1

        val before = allocatedBytes()
        var steps = 0
        while (world.hasActiveBalls && steps < WINDOW_STEP_CAP) {
            if (byAdvance) {
                steps += world.advance(FRAME_NANOS)
            } else {
                world.step()
                steps++
            }
        }
        val after = allocatedBytes()
        lastWindowBytes = after - before

        // Anything still in flight is cleared outside the window.
        for (ball in world.balls.toList()) world.removeBall(ball)
        return steps
    }

    private companion object {

        const val WARM_UP_DROPS = 3

        /** A hung window is not the question here; EngineScenarioTest owns "every ball exits". */
        const val WINDOW_STEP_CAP = 20 * 240

        /** One 60 Hz frame, ns: four steps. */
        const val FRAME_NANOS = 16_666_667L

        /** JVM noise allowance for a whole measurement. Real per-step allocation is megabytes. */
        const val BUDGET_BYTES = 64L * 1024

        /** Includes straight down, so a ball rebounding into the apex corner is measured too. */
        val AIMS_DEGREES = floatArrayOf(-62f, 0f, 37f, -18f, 55f, 9f, -44f, 23f, -3f, 68f)

        fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()
    }
}
