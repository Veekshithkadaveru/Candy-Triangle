package app.krafted.candytriangle.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * §3.3 moving peg rows: [Peg.updateMotion] against the closed form
 * `x(t) = baseX + A sin(2 PI t / T)`, `vx(t) = A (2 PI / T) cos(2 PI t / T)`, and [PhysicsWorld]
 * keeping every moving peg at its position for the world's own simulation time.
 *
 * Pure JVM. The reference values come from `kotlin.math` evaluated directly, not from the code
 * under test, so a wrong phase, a swapped sin/cos or a lost `2 PI` all show up as a red test.
 */
class PegMotionTest {

    // -- the formula at its landmarks -----------------------------------------------------------

    @Test
    fun atTimeZeroAPegSitsAtItsBaseMovingAtPeakSpeed() {
        val peg = movingPeg()
        peg.updateMotion(0.0)

        assertEquals("x(0) is baseX exactly: sin(0) is exactly 0", BASE_X, peg.x, 0f)
        assertF("vx(0) = A * 2 PI / T", AMPLITUDE * 2 * PI / PERIOD, peg.vx)
    }

    @Test
    fun atAQuarterPeriodAPegIsAtFullAmplitudeAndMomentarilyStill() {
        val peg = movingPeg()
        peg.updateMotion(PERIOD / 4)

        assertF("x(T/4) = baseX + A", BASE_X + AMPLITUDE, peg.x)
        assertF("vx(T/4) = 0: the turning point", 0.0, peg.vx)
    }

    @Test
    fun atHalfAPeriodAPegIsBackAtItsBaseMovingTheOtherWay() {
        val peg = movingPeg()
        peg.updateMotion(PERIOD / 2)

        assertF("x(T/2) = baseX", BASE_X.toDouble(), peg.x)
        assertF("vx(T/2) = -A * 2 PI / T", -AMPLITUDE * 2 * PI / PERIOD, peg.vx)
    }

    @Test
    fun matchesTheFormulaAcrossSeveralPeriods() {
        // A period with no exact float representation, to catch any float-precision phase math.
        val motion = PegMotion(baseX = 612.5f, amplitude = 27.5f, periodSeconds = 2.2f)
        val peg = Peg(id = 1, x = motion.baseX, y = 500f, radius = 7f, restitution = 0.6f, motion = motion)
        val omega = 2 * PI / motion.periodSeconds.toDouble()

        for (step in 0..2400 step 7) {
            val t = step / 240.0
            peg.updateMotion(t)
            assertF("x at t = $t", motion.baseX + motion.amplitude * sin(omega * t), peg.x)
            assertF("vx at t = $t", motion.amplitude * omega * cos(omega * t), peg.vx)
        }
    }

    // -- long sessions ------------------------------------------------------------------------------

    /**
     * The phase is reduced (`t mod T`) before it is scaled, and `mod` is exact in IEEE-754, so a row
     * a week into a session lands on exactly the same float as the same phase in its first period.
     */
    @Test
    fun aWeekIntoASessionThePhaseIsStillExact() {
        // One week plus T/4, built the way the world builds time: an integer step count over 240.
        val stepsPerWeek = 240L * 60 * 60 * 24 * 7
        val t = (stepsPerWeek + 180).toDouble() / 240
        val late = movingPeg()
        late.updateMotion(t)

        val early = movingPeg()
        early.updateMotion(PERIOD / 4)

        assertEquals("a week is a whole number of 3 s periods", 0.0, (t - PERIOD / 4) % PERIOD, 0.0)
        assertEquals("x bit-identical to the T/4 point", early.x.toRawBits(), late.x.toRawBits())
        assertEquals("vx bit-identical to the T/4 point", early.vx.toRawBits(), late.vx.toRawBits())
        assertF("and that point is baseX + A", BASE_X + AMPLITUDE, late.x)
    }

    @Test
    fun matchesTheFormulaAtALargeUnalignedTime() {
        val motion = PegMotion(baseX = 450f, amplitude = 33f, periodSeconds = 2.2f)
        val peg = Peg(id = 1, x = motion.baseX, y = 500f, radius = 7f, restitution = 0.6f, motion = motion)
        // About 11.6 days of simulation, off any period boundary.
        val t = (240L * 1_000_003 + 29).toDouble() / 240
        peg.updateMotion(t)

        // Evaluated naively (scale first), the double phase near 2.9e6 rad is still good to ~1e-9
        // rad — far below float resolution — so it is a fair reference here.
        val omega = 2 * PI / motion.periodSeconds.toDouble()
        assertF("x at t = $t", motion.baseX + motion.amplitude * sin(omega * t), peg.x)
        assertF("vx at t = $t", motion.amplitude * omega * cos(omega * t), peg.vx)
    }

    // -- static and unusable motions ----------------------------------------------------------------

    @Test
    fun aStaticPegIsUntouchedByUpdateMotion() {
        val peg = Peg(id = 1, x = 321f, y = 400f, radius = 7f, restitution = 0.6f)
        for (t in doubleArrayOf(0.0, 0.75, 12_345.678, Double.NaN)) {
            peg.updateMotion(t)
            assertEquals("x at t = $t", 321f, peg.x, 0f)
            assertEquals("vx at t = $t", 0f, peg.vx, 0f)
        }
    }

    /**
     * Config-driven code degrades, never throws. `LevelDef` already keeps authored periods positive,
     * but a [PegMotion] can be built by hand, and a NaN peg would turn every ball that met it NaN.
     */
    @Test
    fun anUnusableMotionHoldsThePegStillAtItsBaseInsteadOfThrowing() {
        val unusable = listOf(
            PegMotion(BASE_X, AMPLITUDE.toFloat(), 0f),
            PegMotion(BASE_X, AMPLITUDE.toFloat(), -3f),
            PegMotion(BASE_X, AMPLITUDE.toFloat(), Float.NaN),
            PegMotion(BASE_X, Float.NaN, PERIOD.toFloat()),
            PegMotion(BASE_X, Float.POSITIVE_INFINITY, PERIOD.toFloat()),
            // Not an error, but the same outcome: an infinitely slow oscillation never leaves home.
            PegMotion(BASE_X, AMPLITUDE.toFloat(), Float.POSITIVE_INFINITY),
        )
        for (motion in unusable) {
            // Start it somewhere else, moving, so "held at base, still" is observable.
            val peg = Peg(id = 1, x = 999f, y = 500f, radius = 7f, restitution = 0.6f, motion = motion)
            peg.vx = 123f
            peg.updateMotion(1.0)
            assertEquals("x for $motion", BASE_X, peg.x, 0f)
            assertEquals("vx for $motion", 0f, peg.vx, 0f)
        }

        for (t in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val peg = movingPeg()
            peg.updateMotion(t)
            assertEquals("x at t = $t", BASE_X, peg.x, 0f)
            assertEquals("vx at t = $t", 0f, peg.vx, 0f)
        }
    }

    // -- inside a world ----------------------------------------------------------------------------

    @Test
    fun afterEveryStepEachMovingPegIsAtItsPositionForTheWorldsTime() {
        val a = movingPeg(id = 1)
        val still = Peg(id = 2, x = 500f, y = 700f, radius = 7f, restitution = 0.6f)
        val b = Peg(
            id = 3,
            x = 650f,
            y = 800f,
            radius = 30f,
            restitution = 0.65f,
            kind = ColliderKind.GEM,
            motion = PegMotion(baseX = 650f, amplitude = 25f, periodSeconds = 1.7f),
        )
        val world = PhysicsWorld(PhysicsParams(), walls = emptyList(), pegs = listOf(a, still, b))

        assertAtWorldTime("at construction (t = 0)", world, a)
        assertAtWorldTime("at construction (t = 0)", world, b)
        assertEquals("the world starts moving pegs at their t = 0 velocity", peakSpeed(a), a.vx, 1e-3f)

        repeat(2 * 240 + 17) { n ->
            world.step()
            assertAtWorldTime("after step ${n + 1}", world, a)
            assertAtWorldTime("after step ${n + 1}", world, b)
            assertEquals("a static peg never moves", 500f, still.x, 0f)
            assertEquals("a static peg never has velocity", 0f, still.vx, 0f)
        }
        assertTrue("the pegs really did move", abs(a.x - BASE_X) > 1f || abs(b.x - 650f) > 1f)
    }

    // -- allocation --------------------------------------------------------------------------------

    /**
     * [Peg.updateMotion] runs for every moving peg on every step, which must not allocate. It used
     * `StrictMath`, which on JDK 21+ allocates 32 B per `sin`/`cos` call — 64 MB over this loop — so
     * this is the regression guard for [DeterministicTrig]. Skipped (not failed) on a JVM without
     * per-thread allocation accounting.
     */
    @Test
    fun updateMotionAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            "this JVM has no per-thread allocation counter",
            bean != null && bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled,
        )
        val counter = checkNotNull(bean)
        @Suppress("DEPRECATION") // Thread.threadId() is JDK 19+; the tests target 11.
        val thread = Thread.currentThread().id
        val peg = movingPeg()

        // Warm-up, unmeasured: load, link and JIT-compile everything the measured loop touches,
        // the counter call included.
        counter.getThreadAllocatedBytes(thread)
        for (step in 0 until 200_000) peg.updateMotion(step / 240.0)

        val before = counter.getThreadAllocatedBytes(thread)
        for (step in 0 until ALLOCATION_CALLS) peg.updateMotion(step / 240.0)
        val allocated = counter.getThreadAllocatedBytes(thread) - before

        assertTrue(
            "$ALLOCATION_CALLS updateMotion calls allocated $allocated bytes " +
                "(Java ${System.getProperty("java.version")})",
            allocated < ALLOCATION_BUDGET_BYTES,
        )
        assertTrue("the loop really moved the peg", peg.x != BASE_X || peg.vx != 0f)
    }

    // -- helpers -----------------------------------------------------------------------------------

    private companion object {

        const val BASE_X = 400f
        const val AMPLITUDE = 40.0
        const val PERIOD = 3.0

        /** Generous for a float rounded once from double, tight enough to catch any real error. */
        const val DELTA = 1e-3

        /** A million steps' worth of one moving peg: over an hour of play at 240 Hz. */
        const val ALLOCATION_CALLS = 1_000_000

        /** JVM noise allowance for the whole loop. One small object per call would be ~16 MB. */
        const val ALLOCATION_BUDGET_BYTES = 64L * 1024

        fun movingPeg(id: Int = 1) = Peg(
            id = id,
            x = BASE_X,
            y = 600f,
            radius = 7f,
            restitution = 0.6f,
            motion = PegMotion(baseX = BASE_X, amplitude = AMPLITUDE.toFloat(), periodSeconds = PERIOD.toFloat()),
        )

        fun peakSpeed(peg: Peg): Float {
            val m = checkNotNull(peg.motion)
            return (m.amplitude * 2 * PI / m.periodSeconds).toFloat()
        }

        /**
         * [peg] must be bit-identical to a fresh copy of itself evaluated at [PhysicsWorld.timeSeconds]:
         * the world's step time and its reported time are the same double, and [Peg.updateMotion] is a
         * pure function of it.
         */
        fun assertAtWorldTime(message: String, world: PhysicsWorld, peg: Peg) {
            val motion = checkNotNull(peg.motion)
            val reference = Peg(peg.id, motion.baseX, peg.y, peg.radius, peg.restitution, peg.kind, motion)
            reference.updateMotion(world.timeSeconds)
            assertEquals("$message: peg ${peg.id} x", reference.x.toRawBits(), peg.x.toRawBits())
            assertEquals("$message: peg ${peg.id} vx", reference.vx.toRawBits(), peg.vx.toRawBits())
        }

        fun assertF(message: String, expected: Double, actual: Float) =
            assertEquals(message, expected, actual.toDouble(), DELTA)
    }
}
