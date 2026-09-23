package app.krafted.candytriangle.ui.board

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.krafted.candytriangle.board.LevelBoard

/**
 * Hosts the board: owns the render surface and the single game thread that drives it.
 *
 * The loop runs `world.advance(frameNanos)`, calls [GameLoopListener.onFrame], then draws — all on
 * one thread, sequentially, which is what satisfies `PhysicsWorld`'s single-thread confinement and
 * keeps `Launcher.predictTrajectory` away from `step()`.
 *
 * **Z-order (rule for Agent 2):** this stays at the default z-order. A plain `SurfaceView`
 * composites *below* the window and punches a transparent hole through it, so the Compose HUD
 * naturally draws on top — but only while the HUD's containers over the board area are transparent
 * or translucent. Never call `setZOrderOnTop(true)`: it would put the surface above the window and
 * hide the HUD entirely.
 *
 * The [LevelBoard] is owned by the ViewModel, not by this view, so it survives the surface being
 * destroyed and recreated (backgrounding). The main thread may hold the board *reference* and pass
 * it to [attach], but must never read a mutable field off it — every session read happens inside
 * [GameLoopListener.onFrame], on the game thread.
 *
 * Owner: Agent 1 (`ui/board`).
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /**
     * Binds a board and starts the loop once the surface has dimensions.
     *
     * @param worldIndex §6.1 world (1..4), selecting the backdrop and the peg tint. Sweet Rooms
     *   report world 0 and must be mapped by the caller.
     */
    fun attach(
        board: LevelBoard,
        channel: GameCommandChannel,
        listener: GameLoopListener,
        worldIndex: Int,
    ): Unit = TODO("owner: Agent 1")

    /** Stops the loop, joins the thread and releases the sprite bitmaps. Idempotent. */
    fun detach(): Unit = TODO("owner: Agent 1")

    /** HUD chrome heights in px; shrinks the letterbox so the board is never drawn under them. */
    fun setBoardInsets(topPx: Int, bottomPx: Int): Unit = TODO("owner: Agent 1")

    /** The live transform, or null before the first `surfaceChanged`. Readable from any thread. */
    @Volatile
    var transform: BoardTransform? = null
        private set

    /** Invoked on the main thread whenever [transform] changes, so Compose can re-key its gestures. */
    var onTransformChanged: ((BoardTransform) -> Unit)? = null

    val isLoopRunning: Boolean get() = TODO("owner: Agent 1")

    override fun surfaceCreated(holder: SurfaceHolder): Unit = TODO("owner: Agent 1")

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ): Unit = TODO("owner: Agent 1")

    /**
     * Must not return until the loop thread has joined: once this returns the `Surface` is invalid
     * and `lockHardwareCanvas()` throws.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder): Unit = TODO("owner: Agent 1")
}
