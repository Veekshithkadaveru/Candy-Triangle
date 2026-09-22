package app.krafted.candytriangle.engine

import app.krafted.candytriangle.engine.DeterministicTrig.cosTurns
import app.krafted.candytriangle.engine.DeterministicTrig.sinTurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * [DeterministicTrig]: sine and cosine in turns, the per-step trig behind moving pegs.
 *
 * Accuracy is measured against `StrictMath` (fdlibm, within an ulp of the true value) on an argument
 * reduced *exactly* first: `f - rint(f)` is a pure identity, not this implementation's reduction.
 * Unreduced, the reference's own rounding of `2 PI f` costs up to ~7e-16 near a full turn — most of
 * the 1e-15 budget, and error that belongs to the reference, not to the code under test.
 */
class DeterministicTrigTest {

    // -- accuracy -------------------------------------------------------------------------------

    @Test
    fun matchesStrictMathAcrossAWholeTurn() {
        var worstSin = 0.0
        var worstSinAt = 0.0
        var worstCos = 0.0
        var worstCosAt = 0.0
        for (f in SWEEP) {
            val reduced = f - Math.rint(f)
            val sinError = abs(sinTurns(f) - StrictMath.sin(2 * PI * reduced))
            val cosError = abs(cosTurns(f) - StrictMath.cos(2 * PI * reduced))
            if (sinError > worstSin) {
                worstSin = sinError
                worstSinAt = f
            }
            if (cosError > worstCos) {
                worstCos = cosError
                worstCosAt = f
            }
        }
        assertTrue("worst sin error $worstSin at f = $worstSinAt", worstSin < MAX_ERROR)
        assertTrue("worst cos error $worstCos at f = $worstCosAt", worstCos < MAX_ERROR)
    }

    /**
     * Quarter turns are exact, because the reduction works in turns: no rounded multiple of PI is
     * ever involved. (`StrictMath.cos(2 * PI * 0.25)`, by contrast, is 6.1e-17, not 0.)
     */
    @Test
    fun quarterTurnsAreExact() {
        val quarters = listOf(
            // turns to (sin, cos)
            0.0 to (0.0 to 1.0),
            0.25 to (1.0 to 0.0),
            0.5 to (0.0 to -1.0),
            0.75 to (-1.0 to 0.0),
            1.0 to (0.0 to 1.0),
            7.0 to (0.0 to 1.0),
        )
        for ((f, expected) in quarters) {
            assertEquals("sin at $f turns", expected.first, sinTurns(f), 0.0)
            assertEquals("cos at $f turns", expected.second, cosTurns(f), 0.0)
        }
    }

    @Test
    fun sineSquaredPlusCosineSquaredIsOne() {
        var worst = 0.0
        var worstAt = 0.0
        for (f in SWEEP) {
            val s = sinTurns(f)
            val c = cosTurns(f)
            val error = abs(s * s + c * c - 1.0)
            if (error > worst) {
                worst = error
                worstAt = f
            }
        }
        assertTrue("worst |sin^2 + cos^2 - 1| = $worst at f = $worstAt", worst < MAX_ERROR)
    }

    @Test
    fun outsideTheFirstTurnItStillWorks() {
        // Periodic in whole turns; a negative turn is reduced into [0, 1) first. Both reductions
        // round once here (f + 5 and 1 - |f| are not exact), so the bound is looser than MAX_ERROR.
        for (f in doubleArrayOf(0.1, 0.3, 0.6, 0.9)) {
            assertEquals("sin, 5 turns on from $f", sinTurns(f), sinTurns(f + 5.0), 1e-14)
            assertEquals("cos, 5 turns on from $f", cosTurns(f), cosTurns(f + 5.0), 1e-14)
            assertEquals("sin is odd at $f", -sinTurns(f), sinTurns(-f), 1e-14)
            assertEquals("cos is even at $f", cosTurns(f), cosTurns(-f), 1e-14)
        }
        assertTrue("NaN in, NaN out", sinTurns(Double.NaN).isNaN() && cosTurns(Double.NaN).isNaN())
        assertTrue("infinity in, NaN out", sinTurns(Double.POSITIVE_INFINITY).isNaN())
    }

    // -- determinism ------------------------------------------------------------------------------

    /**
     * The same input gives the same bits on every call, before and after a heavy JIT warm-up — the
     * point where a VM allowed to fuse `a * b + c` would diverge from its interpreter. Java forbids
     * that fusion; this checks the host honours it. (Whether the first pass is still interpreted
     * depends on which tests ran earlier in this JVM; the comparison holds either way.)
     */
    @Test
    fun resultsAreBitIdenticalBeforeAndAfterJitWarmUp() {
        val probe = DoubleArray(512) { i -> i / 512.0 + 1.0 / 1024 }
        val coldSin = LongArray(probe.size) { sinTurns(probe[it]).toRawBits() }
        val coldCos = LongArray(probe.size) { cosTurns(probe[it]).toRawBits() }

        var sink = 0.0
        repeat(200) { for (f in SWEEP_WARM_UP) sink += sinTurns(f) + cosTurns(f) }
        assertTrue("keeps the warm-up loop alive", sink.isFinite())

        for (i in probe.indices) {
            assertEquals("sin bits at ${probe[i]}", coldSin[i], sinTurns(probe[i]).toRawBits())
            assertEquals("cos bits at ${probe[i]}", coldCos[i], cosTurns(probe[i]).toRawBits())
        }
    }

    private companion object {

        const val MAX_ERROR = 1e-15

        /**
         * A million evenly spaced fractions of a turn, plus every octant boundary and its two
         * neighbouring doubles (where the quadrant choice and the reduction flip), plus fractions
         * just short of a whole turn.
         */
        val SWEEP: DoubleArray = run {
            val n = 1_000_000
            val edges = ArrayList<Double>()
            for (k in 0..8) {
                val boundary = k / 8.0
                for (f in doubleArrayOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary))) {
                    if (f >= 0.0 && f < 1.0) edges += f
                }
            }
            edges += listOf(Math.nextDown(1.0), 1.0 - 1e-15, 1.0 - 1e-12, 1.0 - 1e-9)
            DoubleArray(n) { it.toDouble() / n } + edges.toDoubleArray()
        }

        /** Enough calls for the JIT to compile both functions, whatever the host's thresholds. */
        val SWEEP_WARM_UP: DoubleArray = DoubleArray(2_000) { it / 2_000.0 }
    }
}
