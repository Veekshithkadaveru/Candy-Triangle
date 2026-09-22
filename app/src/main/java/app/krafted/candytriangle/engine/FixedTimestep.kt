package app.krafted.candytriangle.engine

/**
 * The fixed-timestep accumulator (§3.2 `physicsStepMs`): turns variable frame times into a whole
 * number of 1/[stepsPerSecond] physics steps.
 *
 * Time is banked in **integer** arithmetic (nanoseconds, or nanoseconds x Hz), never in a float
 * accumulator, so how many steps a given sequence of frame times produces can never depend on
 * rounding drift. The simulation itself only ever sees whole steps; this class decides *when* they
 * run, never *what* they compute — which is why frame pacing cannot affect determinism.
 *
 * D1's `GameThread` owns the real frame clock; [PhysicsWorld.advance] wraps one instance of this.
 *
 * ## The bank
 *
 * Elapsed time is banked as `frameNanos x stepsPerSecond`, so one step is exactly
 * [NANOS_PER_SECOND] units at any rate — there is no rounded step length (240 Hz is not a whole
 * number of nanoseconds) and so nothing to drift: a 144 Hz or 90 Hz display gets exactly 240 steps
 * per second of frames, forever. Between calls the bank holds only the sub-step remainder,
 * `0 <= bank < NANOS_PER_SECOND`, which is also what [alpha] reports.
 *
 * Allocation-free, like the step it paces. Confined to the game thread.
 */
class FixedTimestep(
    val stepsPerSecond: Int,
    /**
     * Spiral-of-death guard: the most steps one [advance] may return. Banked time beyond this is
     * discarded, so after a long hitch the game briefly runs slow instead of freezing to catch up.
     * 12 steps is 50 ms of simulation at 240 Hz.
     */
    val maxStepsPerAdvance: Int = DEFAULT_MAX_STEPS_PER_ADVANCE,
) {

    /** The bank's rate multiplier; at least 1, so a nonsensical rate never banks time backward. */
    private val rate: Long = stepsPerSecond.coerceAtLeast(1).toLong()

    /** The effective cap; a negative one means "never step", not a negative step count. */
    private val cap: Int = maxStepsPerAdvance.coerceAtLeast(0)

    /** The sub-step remainder, in nanosecond-hertz; always in `[0, NANOS_PER_SECOND)`. */
    private var bank: Long = 0L

    /**
     * Banks [frameNanos] of elapsed wall-clock time and returns how many whole steps to run now,
     * `0..maxStepsPerAdvance`. The remainder stays banked for the next call; time beyond the cap is
     * dropped. A negative [frameNanos] (a clock that stepped backward) counts as zero.
     *
     * Exact for every `Long`: the whole seconds of a frame are split off first, because each of
     * them releases exactly `stepsPerSecond` steps and contributes nothing to the remainder. Only
     * the sub-second part is multiplied by the rate, which keeps the product inside a `Long` for
     * any `Int` rate — so even `Long.MAX_VALUE` cannot overflow, and a capped hitch still keeps
     * its exact sub-step remainder.
     */
    fun advance(frameNanos: Long): Int {
        if (frameNanos <= 0L) return 0 // The bank alone never holds a whole step.

        val wholeSeconds = frameNanos / NANOS_PER_SECOND
        // < NANOS_PER_SECOND x (1 + Int.MAX_VALUE), comfortably inside a Long.
        val units = bank + (frameNanos - wholeSeconds * NANOS_PER_SECOND) * rate
        val fromRemainder = units / NANOS_PER_SECOND
        bank = units - fromRemainder * NANOS_PER_SECOND

        // Saturate before multiplying: once the whole seconds alone reach the cap (each is worth
        // at least one step), the exact product no longer matters and may not fit in a Long.
        val fromWholeSeconds = if (wholeSeconds >= cap) cap.toLong() else wholeSeconds * rate
        val steps = fromRemainder + fromWholeSeconds
        return if (steps > cap) cap else steps.toInt()
    }

    /**
     * How far the banked remainder is into the next step, in `[0, 1)` — the render interpolation
     * factor between a ball's previous and current position.
     *
     * The quotient is exact in double but can round *up* to `1f` when narrowed (a remainder of
     * 999,999,999 units, say, at a rate coprime to 10^9), so the result is held just below 1.
     */
    val alpha: Float
        get() {
            val fraction = (bank.toDouble() / NANOS_PER_SECOND).toFloat()
            return if (fraction < 1f) fraction else LARGEST_ALPHA
        }

    /**
     * Discards all banked time, e.g. on resume from pause, so the first frame back is not a hitch.
     */
    fun reset() {
        bank = 0L
    }

    companion object {
        const val DEFAULT_MAX_STEPS_PER_ADVANCE: Int = 12

        /** One step's worth of bank at any rate: a second, in nanoseconds (units: ns x Hz). */
        const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** The largest float below 1, `1 - 2^-24`: [alpha]'s ceiling. */
        private const val LARGEST_ALPHA: Float = 0.99999994f
    }
}
