package app.krafted.candytriangle.ui.board

/**
 * The game loop's wall clock: turns successive `nanoTime` readings into a clamped frame delta.
 *
 * Confined to the game thread, allocation-free, and — because the clock is injected — completely
 * JVM-testable. That injection is the whole point: a frame-pacing bug is otherwise only reachable
 * on a device, and we verify nothing on a device.
 *
 * Three hazards it absorbs before `PhysicsWorld.advance` ever sees a number:
 * - **The first tick** has no previous reading, so it is 0: no step runs on the frame that starts
 *   the loop, and the simulation cannot jump on attach.
 * - **A backwards clock** (a clock change, or a vendor `nanoTime` that is not truly monotonic)
 *   would bank a negative delta. It yields 0 and re-syncs.
 * - **A hitch** — a GC pause, a cold sprite decode, an app returning from the background — is
 *   capped at [MAX_FRAME_NANOS]. `FixedTimestep` already caps at 12 steps / 50 ms and *discards*
 *   the excess, so capping here only means the loop and the engine agree on what was dropped.
 *
 * Owner: Agent 1 (`ui/board`).
 */
class FrameClock(
    /** Injected so tests can drive it; `System::nanoTime` in production. */
    private val nowNanos: () -> Long = System::nanoTime,
) {

    private var lastNanos: Long = 0L
    private var started: Boolean = false

    /** The reading [tick] took last, for the loop's pacing arithmetic. 0 before the first tick. */
    val lastTickNanos: Long get() = lastNanos

    /**
     * Samples the clock and returns the nanoseconds since the previous [tick], clamped to
     * `0..`[MAX_FRAME_NANOS]. The first call after construction or [reset] returns 0.
     */
    fun tick(): Long {
        val now = nowNanos()
        if (!started) {
            started = true
            lastNanos = now
            return 0L
        }
        val delta = now - lastNanos
        lastNanos = now
        return when {
            delta < 0L -> 0L
            delta > MAX_FRAME_NANOS -> MAX_FRAME_NANOS
            else -> delta
        }
    }

    /**
     * Forgets the previous reading, so the next [tick] is 0.
     *
     * Called on resume from pause, paired with `PhysicsWorld.resetTimestep()`: this one stops a
     * multi-second delta arriving on the first frame back, that one discards the `FixedTimestep`
     * sub-step remainder so the first frame back is not a partial-step hitch. Both matter.
     */
    fun reset() {
        started = false
        lastNanos = 0L
    }

    companion object {

        /**
         * 50 ms — `FixedTimestep`'s own cap of 12 steps at 240 Hz (B1 note 11). Anything longer is
         * simulated time the engine would discard anyway.
         */
        const val MAX_FRAME_NANOS: Long = 50_000_000L

        /** The 60 fps budget the loop paces to: 1/60 s, rounded to the nearest ns. */
        const val TARGET_FRAME_NANOS: Long = 16_666_667L
    }
}
