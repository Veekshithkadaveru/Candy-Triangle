package app.krafted.candytriangle.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [FixedTimestep]: whole steps out of variable frame times, exactly — no drift over a long session
 * at any display rate — with the spiral-of-death cap, and a render alpha that stays in `[0, 1)`.
 *
 * Frame times here are what a display actually delivers: 1/60 s is not a whole number of
 * nanoseconds, so an ideal 60 Hz stream is the repeating 16,666,667 / 16,666,667 / 16,666,666 ns
 * cycle that sums to exactly 50 ms — the vsync clock `ceil(k * 1e9 / 60)` ns. The streams for other
 * rates are built the same way, from frame boundaries at `ceil(k * 1e9 / hz)` ns.
 */
class FixedTimestepTest {

    // -- steady display rates ------------------------------------------------------------------

    @Test
    fun sixtyHertzFramesRunExactlyFourStepsEach() {
        val timestep = FixedTimestep(240)
        val cycle = longArrayOf(16_666_667L, 16_666_667L, 16_666_666L)
        for (frame in 0 until 60_000) { // 1000 s of 60 Hz frames
            assertEquals("frame $frame", 4, timestep.advance(cycle[frame % cycle.size]))
        }
    }

    /**
     * The zero-drift guarantee. 240 is not a multiple of 144 or 90, so these displays alternate
     * between step counts from frame to frame — but a million frames in (almost two hours of
     * 144 Hz), the running total must still be exactly `floor(elapsed x 240)` at *every* frame, and
     * exactly 240 per whole second. A float accumulator of `1f / 144` fails this within minutes.
     *
     * The frame clock reads `ceil(k * 1e9 / hz)` ns at vsync k — never behind the ideal clock, so
     * each frame's count is the floor or ceiling of `240 / hz` (a clock rounding *down* instead
     * shifts a step by one frame at exact multiples, which is correct but not what this checks).
     */
    @Test
    fun oneFortyFourAndNinetyHertzStreamsRunExactly240StepsPerSecondForever() {
        for (hz in listOf(144, 90, 120, 75, 165)) {
            val timestep = FixedTimestep(240)
            val fewest = 240 / hz
            val most = (240 + hz - 1) / hz
            var steps = 0L
            var previousBoundary = 0L
            for (frame in 1..1_000_000L) {
                val boundary = (frame * NANOS + hz - 1) / hz
                val released = timestep.advance(boundary - previousBoundary)
                previousBoundary = boundary
                steps += released
                if (released < fewest || released > most) {
                    fail("$hz Hz frame $frame released $released steps; want $fewest..$most")
                }
                if (steps != boundary * 240 / NANOS) {
                    fail("$hz Hz frame $frame: $steps steps after $boundary ns has drifted")
                }
            }
            assertEquals(
                "$hz Hz: a million frames is 240 steps per simulated second, to the step",
                1_000_000L * 240 / hz,
                steps,
            )
        }
    }

    @Test
    fun theStepCountDoesNotDependOnHowTimeIsSlicedIntoFrames() {
        // Ten simulated seconds, delivered as four very different frame streams.
        val slicings = listOf(
            LongArray(600) { 16_666_666L } + longArrayOf(400L), // 60 Hz, clock rounding down
            LongArray(10_000) { 1_000_000L }, // 1 kHz polling
            LongArray(80) { 125_000_000L }, // 8 Hz: 30 steps a frame, so the cap is lifted
            longArrayOf(3L, 999_999_997L) + LongArray(9) { 1_000_000_000L }, // lopsided
        )
        val uncapped = Int.MAX_VALUE
        for ((index, frames) in slicings.withIndex()) {
            val timestep = FixedTimestep(240, maxStepsPerAdvance = uncapped)
            var steps = 0L
            for (frame in frames) steps += timestep.advance(frame)
            assertEquals("slicing $index: 10 s is 2400 steps", 2400L, steps)
            assertEquals("slicing $index leaves nothing banked", 0f, timestep.alpha, 0f)
        }
    }

    // -- sub-step frames -----------------------------------------------------------------------

    @Test
    fun subStepFramesAccumulateUntilAWholeStepIsBanked() {
        val timestep = FixedTimestep(240)
        // 1 ms frames: a 240 Hz step is 4.1667 ms, so four frames bank 0.96 of a step...
        repeat(4) { assertEquals("1 ms frame ${it + 1}", 0, timestep.advance(1_000_000L)) }
        assertEquals("0.96 of a step banked", 0.96f, timestep.alpha, 1e-6f)
        // ...and the fifth crosses it, carrying 0.2 of a step over.
        assertEquals("fifth 1 ms frame", 1, timestep.advance(1_000_000L))
        assertEquals("0.2 of a step carried", 0.2f, timestep.alpha, 1e-6f)
    }

    @Test
    fun aZeroLengthFrameReleasesNothingAndKeepsTheBank() {
        val timestep = FixedTimestep(240)
        timestep.advance(2_083_333L) // half a step
        val banked = timestep.alpha
        assertEquals(0, timestep.advance(0L))
        assertEquals("the bank is untouched", banked, timestep.alpha, 0f)
    }

    // -- the spiral-of-death cap ---------------------------------------------------------------

    @Test
    fun aLongHitchIsCappedAndTheExcessDroppedButTheSubStepRemainderKept() {
        val timestep = FixedTimestep(240)
        // 100.5 ms = 24.12 steps: 12 run, 12 are dropped, 0.12 of a step stays banked.
        val capped = FixedTimestep.DEFAULT_MAX_STEPS_PER_ADVANCE
        assertEquals("capped", capped, timestep.advance(100_500_000L))
        assertEquals("the sub-step remainder survives the cap", 0.12f, timestep.alpha, 1e-6f)
        assertEquals("the dropped steps are gone, not deferred", 0, timestep.advance(0L))
        // 0.88 of a step (3,666,667 ns) completes the banked 0.12: exactly one step.
        assertEquals("the remainder completes a step", 1, timestep.advance(3_666_667L))
    }

    @Test
    fun aCustomCapIsHonoured() {
        val timestep = FixedTimestep(240, maxStepsPerAdvance = 3)
        assertEquals(3, timestep.advance(1_000_000_000L))
        assertEquals("exactly 12.5 ms: 3 steps, under the cap", 3, timestep.advance(12_500_000L))
        assertEquals("0.25 s", 3, timestep.advance(250_000_000L))
    }

    @Test
    fun aZeroOrNegativeCapNeverSteps() {
        for (cap in listOf(0, -1, Int.MIN_VALUE)) {
            val timestep = FixedTimestep(240, maxStepsPerAdvance = cap)
            assertEquals("cap $cap, 1 s", 0, timestep.advance(1_000_000_000L))
            assertEquals("cap $cap, Long.MAX_VALUE", 0, timestep.advance(Long.MAX_VALUE))
            assertTrue("cap $cap: alpha in [0, 1)", timestep.alpha >= 0f && timestep.alpha < 1f)
        }
    }

    // -- hostile clocks ------------------------------------------------------------------------

    @Test
    fun aNegativeFrameCountsAsZero() {
        val timestep = FixedTimestep(240)
        timestep.advance(3_000_000L) // 0.72 of a step
        val banked = timestep.alpha
        for (frame in listOf(-1L, -16_666_667L, Long.MIN_VALUE)) {
            assertEquals("frame $frame", 0, timestep.advance(frame))
            assertEquals("frame $frame must not drain the bank", banked, timestep.alpha, 0f)
        }
        // 0.28 of a step (1,166,667 ns) completes it: the bank really was left alone.
        assertEquals(1, timestep.advance(1_166_667L))
    }

    /**
     * `Long.MAX_VALUE` ns (292 years) x 240 Hz is far past a `Long`. It must cap, not overflow into
     * a negative or garbage step count, and must keep its exact sub-step remainder:
     * `(Long.MAX_VALUE mod 1e9) x 240 mod 1e9` = 146,193,680 units, 0.14619368 of a step.
     */
    @Test
    fun aHugeFrameIsCappedWithoutOverflowing() {
        val timestep = FixedTimestep(240)
        assertEquals(FixedTimestep.DEFAULT_MAX_STEPS_PER_ADVANCE, timestep.advance(Long.MAX_VALUE))
        assertEquals("exact remainder", 0.14619368f, timestep.alpha, 1e-7f)
        assertEquals("nothing else was banked", 0, timestep.advance(0L))
        assertEquals("a normal frame afterwards", 4, timestep.advance(16_666_667L))

        // Nor at the most extreme rate an Int can express, uncapped.
        val extreme = FixedTimestep(Int.MAX_VALUE, maxStepsPerAdvance = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, extreme.advance(Long.MAX_VALUE))
        assertTrue("alpha in [0, 1)", extreme.alpha >= 0f && extreme.alpha < 1f)
        assertEquals("one second at Int.MAX_VALUE Hz", Int.MAX_VALUE, extreme.advance(NANOS))
    }

    @Test
    fun aNonsensicalRateNeverBanksTimeBackward() {
        for (rate in listOf(0, -240, Int.MIN_VALUE)) {
            val timestep = FixedTimestep(rate)
            var steps = 0
            repeat(100) { steps += timestep.advance(16_666_667L) }
            assertTrue("rate $rate stepped $steps times", steps >= 0)
            assertTrue("rate $rate: alpha in [0, 1)", timestep.alpha >= 0f && timestep.alpha < 1f)
        }
    }

    // -- alpha ---------------------------------------------------------------------------------

    @Test
    fun alphaIsTheBankedFractionOfAStep() {
        val timestep = FixedTimestep(240)
        assertEquals("nothing banked yet", 0f, timestep.alpha, 0f)

        timestep.advance(2_083_333L) // 499,999,920 units: just under half a step
        assertEquals("half a step", 0.5f, timestep.alpha, 1e-6f)

        timestep.advance(2_083_334L) // 1,000,000,080 units in total: one step, 80 units over
        assertEquals("a whole step leaves ~nothing banked", 0f, timestep.alpha, 1e-6f)
    }

    /**
     * The largest possible remainder, 999,999,999 units, is 0.999999999 of a step: exact in double,
     * but it rounds to exactly `1f` when narrowed. At 1 Hz every nanosecond is a unit, so a
     * 999,999,999 ns frame produces it directly.
     */
    @Test
    fun alphaStaysBelowOneEvenWhenTheRemainderRoundsUpToIt() {
        val timestep = FixedTimestep(1)
        assertEquals("not yet a whole step", 0, timestep.advance(999_999_999L))
        assertTrue("alpha ${timestep.alpha} must be < 1", timestep.alpha < 1f)
        assertTrue("and still ~1", timestep.alpha > 0.9999f)
    }

    @Test
    fun alphaStaysInRangeAcrossAnIrregularStream() {
        val timestep = FixedTimestep(240)
        var frame = 1L
        repeat(100_000) {
            // A deterministic, irregular frame stream: 0 to ~33 ms.
            frame = (frame * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
            val nanos = (frame ushr 40) % 33_000_000L
            timestep.advance(nanos)
            val alpha = timestep.alpha
            if (!(alpha >= 0f && alpha < 1f)) fail("alpha $alpha after a $nanos ns frame")
        }
    }

    // -- reset ---------------------------------------------------------------------------------

    @Test
    fun resetDiscardsTheBank() {
        val timestep = FixedTimestep(240)
        timestep.advance(3_750_000L) // 0.9 of a step
        assertEquals(0.9f, timestep.alpha, 1e-6f)

        timestep.reset()
        assertEquals("reset empties the bank", 0f, timestep.alpha, 0f)
        assertEquals("the 0.9 is gone: 0.9 more is short of a step", 0, timestep.advance(3_750_000L))
        assertEquals("and the stream carries on normally", 1, timestep.advance(416_667L))
    }

    private companion object {
        const val NANOS = 1_000_000_000L
    }
}
