package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.BallSkin
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one-way door from the main (Compose) thread to the game thread.
 *
 * `PhysicsWorld`, `Launcher` and `LevelSession` are confined to the game thread and are not
 * thread-safe; in particular `Launcher.predictTrajectory` temporarily moves the world's moving pegs
 * and restores them (B2 note 7), so it must never run concurrently with `step()`. This channel is
 * how a finger reaches them without ever touching them: Compose writes volatile scalars here, and
 * the loop drains them at the top of a frame, leaving `Launcher` written by exactly one thread.
 *
 * Every field is volatile or atomic and no method allocates — it is read once per frame at 60 Hz.
 * Setters are main-thread; `consume*` are game-thread-only.
 *
 * Owner: Agent 1 (`ui/board`).
 */
class GameCommandChannel {

    /**
     * The monitor `GameLoop` parks on while [paused]. Internal: not part of the contract Agent 2
     * compiles against — the ViewModel only ever writes [paused], and the setter does the waking.
     */
    internal val pauseLock: ReentrantLock = ReentrantLock()

    /** Signalled by the [paused] setter when it goes false. Awaited by the parked loop. */
    internal val unpaused: Condition = pauseLock.newCondition()

    /**
     * Parks the loop on its monitor. The surface keeps its last frame, so the board freezes.
     *
     * Clearing it signals [unpaused], so the loop wakes immediately instead of polling. The signal
     * is taken under [pauseLock] and the loop re-checks this field under the same lock, so there is
     * no lost-wakeup window.
     */
    @Volatile
    var paused: Boolean = false
        set(value) {
            field = value
            if (!value) pauseLock.withLock { unpaused.signalAll() }
        }

    /** True while a drag is in progress — gates the (allocating) aim-guide prediction. */
    @Volatile
    var aiming: Boolean = false

    /** The equipped skin, read at launch. Set once the ViewModel has resolved `ProgressStore`. */
    @Volatile
    var ballSkin: BallSkin = BallSkin.DEFAULT

    /**
     * The pending aim as raw float bits, or [NO_AIM_BITS] when there is nothing to consume.
     *
     * An `AtomicInteger`, not a plain volatile float, because "latest-wins **and** consumed once"
     * needs the read and the clear to be one operation: a plain read-then-write would drop a
     * `setAim` that landed between them, and a drag would end on a stale angle.
     */
    private val aimBits = AtomicInteger(NO_AIM_BITS)

    private val launchPending = AtomicBoolean(false)

    private val refusals = AtomicInteger(0)

    /** Main thread. Latest-wins; intermediate drag positions may be coalesced. */
    fun setAim(radians: Float) {
        aimBits.set(radians.toRawBits())
    }

    /** Main thread. At most one ball is launched per call, and only if the session allows it. */
    fun requestLaunch() {
        launchPending.set(true)
    }

    /**
     * Game thread. Returns the pending aim, or `Float.NaN` when it has not changed.
     *
     * A `setAim(Float.NaN)` is therefore indistinguishable from "unchanged" and is ignored — which
     * is what we want: a NaN angle must never reach `Launcher.aimRadians`.
     */
    fun consumeAimIfChanged(): Float = Float.fromBits(aimBits.getAndSet(NO_AIM_BITS))

    /** Game thread. True at most once per [requestLaunch]. */
    fun consumeLaunch(): Boolean = launchPending.getAndSet(false)

    /**
     * Game thread. Records that `LevelBoard.launch` returned null — balls exhausted, a drop already
     * active, or the level over. Never retried and never queued; the HUD may shake the counter.
     */
    fun noteLaunchRefused() {
        refusals.incrementAndGet()
    }

    val launchRefusals: Int get() = refusals.get()

    /**
     * Clears every pending command. Called when a board is attached or detached.
     *
     * Clears the pending aim, the pending launch, [launchRefusals] and [aiming] — the transient
     * state of one gesture on one board. Deliberately leaves [paused] and [ballSkin] alone: those
     * are ViewModel-owned session state, and a `reset()` that silently un-paused a level, or
     * dropped the equipped skin back to `DEFAULT`, would be a bug.
     */
    fun reset() {
        aimBits.set(NO_AIM_BITS)
        launchPending.set(false)
        refusals.set(0)
        aiming = false
    }

    /**
     * Game thread. Parks until [paused] goes false, [timeoutMillis] elapses, or the thread is
     * interrupted. [stillParked] is re-checked under [pauseLock], which is what closes the
     * lost-wakeup window against the [paused] setter.
     *
     * The timeout is belt and braces only — the setter always signals — so it can be generous and
     * still cost nothing: one no-op wakeup a second while the pause dialog is up.
     */
    internal fun awaitResume(timeoutMillis: Long, stillParked: () -> Boolean) {
        pauseLock.withLock {
            if (!stillParked()) return@withLock
            try {
                unpaused.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                // The view interrupts only after a join() timeout, i.e. during teardown. Restore
                // the flag so the loop's own `isInterrupted` check ends it.
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Wakes a parked loop without clearing [paused] — how `GameLoop.stop()` breaks the park. */
    internal fun wakeParkedLoop() {
        pauseLock.withLock { unpaused.signalAll() }
    }

    private companion object {
        /** `Float.NaN`'s raw bits: the "nothing pending" sentinel for [aimBits]. */
        val NO_AIM_BITS: Int = Float.NaN.toRawBits()
    }
}
