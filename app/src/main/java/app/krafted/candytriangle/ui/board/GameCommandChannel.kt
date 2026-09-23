package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.BallSkin

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

    /** Parks the loop on its monitor. The surface keeps its last frame, so the board freezes. */
    @Volatile
    var paused: Boolean = false

    /** True while a drag is in progress — gates the (allocating) aim-guide prediction. */
    @Volatile
    var aiming: Boolean = false

    /** The equipped skin, read at launch. Set once the ViewModel has resolved `ProgressStore`. */
    @Volatile
    var ballSkin: BallSkin = BallSkin.DEFAULT

    /** Main thread. Latest-wins; intermediate drag positions may be coalesced. */
    fun setAim(radians: Float): Unit = TODO("owner: Agent 1")

    /** Main thread. At most one ball is launched per call, and only if the session allows it. */
    fun requestLaunch(): Unit = TODO("owner: Agent 1")

    /** Game thread. Returns the pending aim, or `Float.NaN` when it has not changed. */
    fun consumeAimIfChanged(): Float = TODO("owner: Agent 1")

    /** Game thread. True at most once per [requestLaunch]. */
    fun consumeLaunch(): Boolean = TODO("owner: Agent 1")

    /**
     * Game thread. Records that `LevelBoard.launch` returned null — balls exhausted, a drop already
     * active, or the level over. Never retried and never queued; the HUD may shake the counter.
     */
    fun noteLaunchRefused(): Unit = TODO("owner: Agent 1")

    val launchRefusals: Int get() = TODO("owner: Agent 1")

    /** Clears every pending command. Called when a board is attached or detached. */
    fun reset(): Unit = TODO("owner: Agent 1")
}
