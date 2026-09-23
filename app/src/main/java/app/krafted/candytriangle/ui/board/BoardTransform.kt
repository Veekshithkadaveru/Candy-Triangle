package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.GameConfig

/**
 * Maps board units (§3.1: 1000 x 1346, origin at the apex, y growing **down**) to surface pixels.
 *
 * Pure, allocation-free and free of every Android type, so the letterbox arithmetic and its inverse
 * are unit-testable on the JVM with no emulator and no Robolectric. The inverse is what turns a
 * touch point into an aim angle, so a bug here is a bug in the only input the game has.
 *
 * The content box is taller than the board: the cup lane runs to y = 1330 and a ball is retired at
 * `params.exitY` = 1346, so a ball crossing the lane must stay on screen. See [contentHeight].
 *
 * Owner: Agent 1 (`ui/board`).
 */
data class BoardTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val boardWidth: Float,
    val boardHeight: Float,
) {

    /** Board x -> screen px. */
    fun sx(boardX: Float): Float = TODO("owner: Agent 1")

    /** Board y -> screen px. */
    fun sy(boardY: Float): Float = TODO("owner: Agent 1")

    /** A length in board units -> a length in screen px (no offset). */
    fun len(boardLength: Float): Float = TODO("owner: Agent 1")

    /** Screen px -> board x. Inverse of [sx]. */
    fun bx(screenX: Float): Float = TODO("owner: Agent 1")

    /** Screen px -> board y. Inverse of [sy]. */
    fun by(screenY: Float): Float = TODO("owner: Agent 1")

    /** Screen-px bounds of the letterboxed content box. */
    val playLeft: Float get() = TODO("owner: Agent 1")
    val playTop: Float get() = TODO("owner: Agent 1")
    val playRight: Float get() = TODO("owner: Agent 1")
    val playBottom: Float get() = TODO("owner: Agent 1")

    companion object {

        /** 0.5 centres the board in the band left over by the HUD insets; 0 pins the apex to it. */
        const val DEFAULT_VERTICAL_BIAS: Float = 0.5f

        /**
         * `max(board.height, cup.laneYBottom + physics.ballRadius)` — 1346 on `GameConfig.DEFAULTS`.
         *
         * Derived, never hardcoded: moving the cup lane in `config.json` must move the viewport too.
         */
        fun contentHeight(config: GameConfig): Float = TODO("owner: Agent 1")

        /**
         * Uniform letterbox fit of [boardWidth] x [boardHeight] into the surface, minus the insets
         * the HUD reports through `GameSurfaceView.setBoardInsets`.
         *
         * Must never produce a NaN, an infinity or a zero [scale], even for a zero-sized or
         * negative surface — the result feeds a touch handler that runs before the first layout.
         */
        fun fit(
            surfaceWidthPx: Float,
            surfaceHeightPx: Float,
            boardWidth: Float,
            boardHeight: Float,
            insetTopPx: Float = 0f,
            insetBottomPx: Float = 0f,
            insetLeftPx: Float = 0f,
            insetRightPx: Float = 0f,
            verticalBias: Float = DEFAULT_VERTICAL_BIAS,
        ): BoardTransform = TODO("owner: Agent 1")

        /** 1:1, no offset — the "no surface yet" value. */
        val IDENTITY: BoardTransform = BoardTransform(1f, 0f, 0f, 1000f, 1346f)
    }
}
