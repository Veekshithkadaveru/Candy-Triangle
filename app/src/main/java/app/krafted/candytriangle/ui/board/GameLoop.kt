package app.krafted.candytriangle.ui.board

import android.content.res.Resources
import android.graphics.Canvas
import android.util.Log
import android.view.SurfaceHolder
import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.level.BoardPoint

/**
 * The game thread: one `Runnable`, one board, one surface, run on a thread named `candy-game-loop`.
 *
 * ## Why everything is on this one thread
 *
 * `PhysicsWorld` is not thread-safe and throws `IllegalStateException` on re-entry;
 * `Launcher.predictTrajectory` *temporarily moves the world's moving pegs and restores them*
 * (B2 note 7), so it can never overlap a `step()`; and `LevelSession`'s fields are plain
 * non-volatile vars. Draining commands, stepping, publishing the HUD and drawing therefore happen
 * here, sequentially, in this order and nowhere else. The main thread reaches the board only
 * through [GameCommandChannel]; the ViewModel is reached only through [GameLoopListener.onFrame],
 * which is called between `advance()` and the draw, when the world is quiescent.
 *
 * ## Pause
 *
 * A paused loop draws one last frame and then parks on [GameCommandChannel]'s condition — zero CPU.
 * The surface keeps its last posted buffer, so the Compose pause dialog draws over a frozen board
 * for free, and because the cup is advanced inside `GamePhysicsListener.afterStep`, a parked loop
 * freezes the cup too.
 *
 * On waking, the loop resets [FrameClock] and then `PhysicsWorld.resetTimestep()` — in that order,
 * **on this thread**. The first stops a multi-second delta arriving on the first frame back; the
 * second discards the `FixedTimestep` sub-step remainder so that frame is not a partial-step hitch.
 *
 * Owner: Agent 1 (`ui/board`).
 */
internal class GameLoop(
    private val holder: SurfaceHolder,
    private val resources: Resources,
    private val board: LevelBoard,
    private val channel: GameCommandChannel,
    private val listener: GameLoopListener,
    private val renderer: BoardRenderer,
    private val sprites: SpriteCache,
    /** §6.1 world 1..4: picks the backdrop. Sweet Rooms report 0 and are mapped by the caller
     *  (`GameViewModel.backdropWorldFor`: Bn plays in world n). */
    private val worldIndex: Int,
    /** `WorldDef.pegTintArgb`: the peg colour and the glow tint cached for it. */
    private val pegTintArgb: Int,
    private val transformProvider: () -> BoardTransform?,
    private val surfaceValid: () -> Boolean,
    private val frameClock: FrameClock = FrameClock(),
) : Runnable {

    @Volatile
    private var running: Boolean = true

    /** The last predicted aim path. Game-thread-only, so no volatile and no copy. */
    private var aimPath: List<BoardPoint>? = null
    private var aimPathDirty: Boolean = true
    private var wasAiming: Boolean = false
    private var wasDropActive: Boolean = false
    private var pausedFrameDrawn: Boolean = false

    private val ballDiameter: Float = board.params.ballRadius * 2f
    private val candyDiameter: Float = board.config.physics.candyRadius * 2f
    private val gemDiameter: Float = board.params.gemRadius * 2f

    /** Allocated once, not per park: the parked loop's re-check, taken under the channel's lock. */
    private val stillParked: () -> Boolean = { running && channel.paused }

    /** True while the thread is inside [run]. */
    val isRunning: Boolean get() = running

    override fun run() {
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                if (channel.paused) {
                    park()
                    continue
                }
                if (pausedFrameDrawn) {
                    // Resuming. Both resets matter, and both belong on this thread: the main
                    // thread must not touch PhysicsWorld, even a parked one.
                    pausedFrameDrawn = false
                    frameClock.reset()
                    board.world.resetTimestep()
                }
                frame()
            }
        } catch (error: Throwable) {
            // A crash here would otherwise take the process down with no frame on screen and no
            // trace tying it to the loop. Zero crash tolerance (§13): log it and stop cleanly.
            Log.e(TAG, "candy-game-loop stopped by an unhandled exception", error)
        } finally {
            running = false
            runCatching { listener.onLoopStopped() }
        }
    }

    /** Stops the loop and wakes it if it is parked. Safe from any thread; the caller then joins. */
    fun stop() {
        running = false
        channel.wakeParkedLoop()
    }

    // -- one frame ---------------------------------------------------------------------------------

    private fun frame() {
        val frameStartNanos = System.nanoTime()

        // 1. Drain the main thread's commands. Launcher is written here and only here, which is
        //    what makes predictTrajectory below safe.
        val aim = channel.consumeAimIfChanged()
        if (!aim.isNaN()) {
            board.launcher.aimRadians = aim // the setter clamps
            aimPathDirty = true
        }

        // 2. Launch. A refusal (balls exhausted, a drop already active, the level over) is counted
        //    and dropped: never retried, never queued.
        if (channel.consumeLaunch()) {
            if (board.launch(board.launcher.aimRadians, channel.ballSkin) == null) {
                channel.noteLaunchRefused()
            } else {
                aimPathDirty = true
            }
        }

        // 3. Physics. advance() caps at 12 steps / 50 ms and discards the excess, so a hitch
        //    cannot spiral into a catch-up storm.
        val frameNanos = frameClock.tick()
        val steps = board.world.advance(frameNanos)

        // 4. The HUD publish point: the world is quiescent, so the session's non-volatile fields
        //    and the ObjectiveTracker are mutually consistent right now and nowhere else.
        listener.onFrame(board, frameNanos, steps)

        // 5. Aim guide. Allocating (~250 BoardPoints) and it moves the world's moving pegs, so it
        //    runs only while aiming, only between drops, and only when the aim actually changed.
        val aiming = channel.aiming
        val dropActive = board.isDropActive
        if (aiming && !wasAiming) aimPathDirty = true
        if (wasDropActive && !dropActive) aimPathDirty = true
        wasAiming = aiming
        wasDropActive = dropActive

        if (aiming && !dropActive && aimPathDirty) {
            aimPath = board.launcher.predictTrajectory(board.world, AIM_MAX_STEPS, AIM_RECORD_INTERVAL)
            aimPathDirty = false
        }

        drawFrame()
        pace(frameStartNanos)
    }

    // -- pause -------------------------------------------------------------------------------------

    private fun park() {
        if (!pausedFrameDrawn) {
            drawFrame()
            pausedFrameDrawn = true
        }
        // A generous timeout, purely as belt and braces: the `paused` setter always signals under
        // the same lock the re-check is taken under, so there is no lost-wakeup window to cover.
        channel.awaitResume(PARK_TIMEOUT_MILLIS, stillParked)
    }

    // -- drawing -----------------------------------------------------------------------------------

    private fun drawFrame() {
        if (!surfaceValid()) return
        val transform = transformProvider() ?: return
        val surface = holder.surface
        if (surface == null || !surface.isValid) return

        val frame = holder.surfaceFrame
        // Idempotent, and deliberately on this thread: bitmaps are then created and recycled by
        // the one thread that draws them, so a HUD inset change can never pull a bitmap out from
        // under a drawBitmap.
        sprites.prepare(
            resources = resources,
            scale = transform.scale,
            worldIndex = worldIndex,
            surfaceWidth = frame.width(),
            surfaceHeight = frame.height(),
            ballDiameter = ballDiameter,
            candyDiameter = candyDiameter,
            gemDiameter = gemDiameter,
            pegTintArgb = pegTintArgb,
        )

        val showAim = channel.aiming && !board.isDropActive && board.session.remainingBalls > 0

        var canvas: Canvas? = null
        try {
            // Hardware, not lockCanvas(): a software canvas would CPU-rasterise a full-screen
            // scaled backdrop every frame. The cost is that BlurMaskFilter is ignored, which is
            // why every glow is a cached radial-gradient bitmap.
            canvas = holder.lockHardwareCanvas()
            if (canvas == null) return
            renderer.draw(canvas, board, transform, sprites, pegTintArgb, aimPath, showAim, channel.trail)
        } catch (dropped: RuntimeException) {
            // §13 is zero crash tolerance, and every way a frame can fail is a RuntimeException a
            // dropped frame survives: an IllegalStateException from a surface that went away
            // mid-frame, an IllegalArgumentException from a rejected canvas, or "trying to use a
            // recycled bitmap" if teardown beat the loop to the sprites.
            Log.w(TAG, "frame dropped", dropped)
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (gone: IllegalStateException) {
                    Log.w(TAG, "surface became unavailable before post", gone)
                }
            }
        }
    }

    // -- pacing ------------------------------------------------------------------------------------

    private fun pace(frameStartNanos: Long) {
        val remaining = FrameClock.TARGET_FRAME_NANOS - (System.nanoTime() - frameStartNanos)
        if (remaining <= 0L) return
        try {
            Thread.sleep(remaining / NANOS_PER_MILLI, (remaining % NANOS_PER_MILLI).toInt())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    companion object {

        const val THREAD_NAME: String = "candy-game-loop"

        private const val TAG = "GameLoop"

        /** Risk R3: 600 steps is 2.5 s of simulated flight, far past any first contact. */
        const val AIM_MAX_STEPS: Int = 600

        /** Every 6th step, so a long path is ~100 dots rather than 600. */
        const val AIM_RECORD_INTERVAL: Int = 6

        /** One no-op wakeup a second while parked. Belt and braces; the signal does the work. */
        private const val PARK_TIMEOUT_MILLIS: Long = 1_000L

        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
