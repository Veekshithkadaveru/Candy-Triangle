package app.krafted.candytriangle.ui.board

import android.content.Context
import android.util.AttributeSet
import android.util.Log
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

    /** The live transform, or null before the first `surfaceChanged`. Readable from any thread. */
    @Volatile
    var transform: BoardTransform? = null
        private set

    /** Invoked on the main thread whenever [transform] changes, so Compose can re-key its gestures. */
    var onTransformChanged: ((BoardTransform) -> Unit)? = null

    private val renderer = BoardRenderer()
    private val sprites = SpriteCache()

    private var board: LevelBoard? = null
    private var channel: GameCommandChannel? = null
    private var loopListener: GameLoopListener = GameLoopListener.NONE
    private var worldIndex: Int = FALLBACK_WORLD_INDEX
    private var pegTintArgb: Int = FALLBACK_PEG_TINT_ARGB

    private var loop: GameLoop? = null
    private var loopThread: Thread? = null

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var insetTopPx: Int = 0
    private var insetBottomPx: Int = 0

    /**
     * Read by the game thread on every frame; cleared by the main thread in [surfaceDestroyed] and
     * on a join timeout, after which [GameLoop] draws nothing — `lockHardwareCanvas()` on a dead
     * `Surface` throws.
     */
    @Volatile
    private var surfaceValid: Boolean = false

    // Declared last so every field above is initialised before a callback can possibly arrive.
    init {
        holder.addCallback(this)
    }

    /**
     * Binds a board and starts the loop once the surface has dimensions.
     *
     * Safe to call before or after the surface exists: if it already has a size the loop starts
     * here, otherwise [surfaceChanged] starts it. Main thread only.
     *
     * **Idempotent** for the same four arguments, which matters because an `AndroidView`'s
     * `update` block runs on every recomposition: re-attaching the same board is a no-op rather
     * than a 2-second `join` on the main thread and a `channel.reset()` that would drop a launch
     * the player had already asked for.
     *
     * @param worldIndex §6.1 world (1..4), selecting the backdrop and the peg tint. Sweet Rooms
     *   report world 0 and must be mapped by the caller (`GameViewModel.backdropWorldFor`).
     */
    fun attach(
        board: LevelBoard,
        channel: GameCommandChannel,
        listener: GameLoopListener,
        worldIndex: Int,
    ) {
        val resolvedWorld = if (worldIndex in 1..MAX_WORLD_INDEX) worldIndex else FALLBACK_WORLD_INDEX

        if (this.board === board &&
            this.channel === channel &&
            this.loopListener === listener &&
            this.worldIndex == resolvedWorld
        ) {
            startLoopIfPossible()
            return
        }

        // A genuinely different board: the previous loop must not keep running on the old one.
        stopLoop()

        this.board = board
        this.channel = channel
        this.loopListener = listener
        this.worldIndex = resolvedWorld
        // From the *loaded* config, not the compiled-in table: config.json may retune a peg tint.
        this.pegTintArgb = pegTintFor(board, resolvedWorld)
        channel.reset()

        updateTransform()
        startLoopIfPossible()
    }

    /** Stops the loop, joins the thread and releases the sprite bitmaps. Idempotent. */
    fun detach() {
        stopLoop()
        // Only after the join: the game thread is the one that creates these bitmaps and the one
        // that draws them, so recycling them while it lives would be a use-after-free.
        sprites.release()
        board = null
        channel = null
        loopListener = GameLoopListener.NONE
    }

    /** HUD chrome heights in px; shrinks the letterbox so the board is never drawn under them. */
    fun setBoardInsets(topPx: Int, bottomPx: Int) {
        if (topPx == insetTopPx && bottomPx == insetBottomPx) return
        insetTopPx = topPx
        insetBottomPx = bottomPx
        // The scale may have changed, so the sprites are now the wrong size — but they are
        // re-prepared by the game thread at the top of its next frame, never from here.
        updateTransform()
    }

    val isLoopRunning: Boolean get() = loopThread?.isAlive == true

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Dimensions are not known yet — surfaceChanged always follows, and it does the work.
        surfaceValid = true
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceValid = true
        surfaceWidth = width
        surfaceHeight = height
        updateTransform()
        // A Thread cannot be restarted, so a surface recreated after backgrounding gets a *new*
        // one — driving the same LevelBoard, which lives in the ViewModel and outlived the surface.
        startLoopIfPossible()
    }

    /**
     * Must not return until the loop thread has joined: once this returns the `Surface` is invalid
     * and `lockHardwareCanvas()` throws.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceValid = false
        surfaceWidth = 0
        surfaceHeight = 0
        stopLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detach()
    }

    // -- internals ---------------------------------------------------------------------------------

    private fun updateTransform() {
        // Keep the contract Agent 2 codes against: `transform` is null until the surface has real
        // dimensions. Publishing a degenerate MIN_SCALE fit from attach() would look like a live
        // transform and have a Compose gesture key off it.
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        val config = board?.config
        val boardWidth = config?.board?.width ?: BoardTransform.FALLBACK_BOARD_WIDTH
        val boardHeight =
            if (config != null) BoardTransform.contentHeight(config)
            else BoardTransform.FALLBACK_BOARD_HEIGHT

        val next = BoardTransform.fit(
            surfaceWidthPx = surfaceWidth.toFloat(),
            surfaceHeightPx = surfaceHeight.toFloat(),
            boardWidth = boardWidth,
            boardHeight = boardHeight,
            insetTopPx = insetTopPx.toFloat(),
            insetBottomPx = insetBottomPx.toFloat(),
        )
        if (next == transform) return
        transform = next
        // Main thread: every caller of updateTransform is a main-thread entry point.
        onTransformChanged?.invoke(next)
    }

    private fun startLoopIfPossible() {
        if (!surfaceValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val currentBoard = board ?: return
        val currentChannel = channel ?: return
        if (loopThread?.isAlive == true) return

        val started = GameLoop(
            holder = holder,
            resources = resources,
            board = currentBoard,
            channel = currentChannel,
            listener = loopListener,
            renderer = renderer,
            sprites = sprites,
            worldIndex = worldIndex,
            pegTintArgb = pegTintArgb,
            transformProvider = { transform },
            surfaceValid = { surfaceValid },
        )
        loop = started
        loopThread = Thread(started, GameLoop.THREAD_NAME).also { it.start() }
    }

    private fun stopLoop() {
        val stopping = loop
        val thread = loopThread
        loop = null
        loopThread = null
        if (stopping == null || thread == null) return

        stopping.stop()
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            // The surface is about to go away under a thread that may still be drawing on it. Stop
            // it from touching the canvas at all, then interrupt whatever it is blocked on.
            surfaceValid = false
            thread.interrupt()
            Log.w(TAG, "candy-game-loop did not join in ${JOIN_TIMEOUT_MILLIS}ms; interrupted")
        }
    }

    private fun pegTintFor(board: LevelBoard, worldIndex: Int): Int {
        val worlds = board.config.worlds
        for (i in worlds.indices) {
            if (worlds[i].index == worldIndex) return worlds[i].pegTintArgb
        }
        return FALLBACK_PEG_TINT_ARGB
    }

    private companion object {

        const val TAG = "GameSurfaceView"

        /** The plan's budget for `surfaceDestroyed`: the join must finish before it returns. */
        const val JOIN_TIMEOUT_MILLIS: Long = 2_000L

        const val MAX_WORLD_INDEX: Int = 4

        /**
         * Used only for an out-of-range index. Sweet Rooms no longer land here: since D3,
         * `GameViewModel.backdropWorldFor` hands Bn world n, the map slice it is drawn on.
         */
        const val FALLBACK_WORLD_INDEX: Int = 1

        /** World 1's pink, if the loaded config has no matching world. */
        const val FALLBACK_PEG_TINT_ARGB: Int = 0xFFFF4FC8.toInt()
    }
}
