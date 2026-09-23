package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.GameConfig
import kotlin.math.max
import kotlin.math.min

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
    fun sx(boardX: Float): Float = boardX * scale + offsetX

    /** Board y -> screen px. */
    fun sy(boardY: Float): Float = boardY * scale + offsetY

    /** A length in board units -> a length in screen px (no offset). */
    fun len(boardLength: Float): Float = boardLength * scale

    /** Screen px -> board x. Inverse of [sx]. */
    fun bx(screenX: Float): Float = (screenX - offsetX) / scale

    /** Screen px -> board y. Inverse of [sy]. */
    fun by(screenY: Float): Float = (screenY - offsetY) / scale

    /** Screen-px bounds of the letterboxed content box. */
    val playLeft: Float get() = offsetX
    val playTop: Float get() = offsetY
    val playRight: Float get() = offsetX + boardWidth * scale
    val playBottom: Float get() = offsetY + boardHeight * scale

    companion object {

        /** 0.5 centres the board in the band left over by the HUD insets; 0 pins the apex to it. */
        const val DEFAULT_VERTICAL_BIAS: Float = 0.5f

        /**
         * The floor [fit] clamps [scale] to.
         *
         * Never 0: [bx] / [by] divide by it, and a touch handler can run before the first layout,
         * when the surface is still 0 x 0. A 1e-4 scale maps the whole board into a tenth of a
         * pixel — visually nothing, arithmetically finite.
         */
        const val MIN_SCALE: Float = 1e-4f

        /** §3.1 board width, the fallback [fit] substitutes for an unusable `boardWidth`. */
        const val FALLBACK_BOARD_WIDTH: Float = 1000f

        /** §3.1 content height on `GameConfig.DEFAULTS`; the fallback for an unusable height. */
        const val FALLBACK_BOARD_HEIGHT: Float = 1346f

        /**
         * `max(board.height, cup.laneYBottom + physics.ballRadius)` — 1346 on `GameConfig.DEFAULTS`.
         *
         * Derived, never hardcoded: moving the cup lane in `config.json` must move the viewport too.
         */
        fun contentHeight(config: GameConfig): Float =
            max(config.board.height, config.cup.laneYBottom + config.physics.ballRadius)

        /**
         * Uniform letterbox fit of [boardWidth] x [boardHeight] into the surface, minus the insets
         * the HUD reports through `GameSurfaceView.setBoardInsets`.
         *
         * Must never produce a NaN, an infinity or a zero [scale], even for a zero-sized or
         * negative surface — the result feeds a touch handler that runs before the first layout.
         *
         * Every argument is sanitised first: a non-finite surface dimension or inset becomes 0, and
         * a non-finite or non-positive board dimension falls back to [FALLBACK_BOARD_WIDTH] /
         * [FALLBACK_BOARD_HEIGHT] so the stored [boardWidth]/[boardHeight] stay usable by
         * [playRight] / [playBottom]. [verticalBias] is coerced into 0..1.
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
        ): BoardTransform {
            val bw = positiveOr(boardWidth, FALLBACK_BOARD_WIDTH)
            val bh = positiveOr(boardHeight, FALLBACK_BOARD_HEIGHT)

            val sw = finiteOrZero(surfaceWidthPx)
            val sh = finiteOrZero(surfaceHeightPx)
            val left = finiteOrZero(insetLeftPx)
            val right = finiteOrZero(insetRightPx)
            val top = finiteOrZero(insetTopPx)
            val bottom = finiteOrZero(insetBottomPx)

            val availW = sw - left - right
            val availH = sh - top - bottom

            val raw = min(availW / bw, availH / bh)
            val scale = if (raw.isFinite() && raw > MIN_SCALE) raw else MIN_SCALE

            val bias = if (verticalBias.isFinite()) verticalBias.coerceIn(0f, 1f) else DEFAULT_VERTICAL_BIAS

            val offsetX = left + (availW - bw * scale) * 0.5f
            val offsetY = top + (availH - bh * scale) * bias

            return BoardTransform(
                scale = scale,
                offsetX = if (offsetX.isFinite()) offsetX else 0f,
                offsetY = if (offsetY.isFinite()) offsetY else 0f,
                boardWidth = bw,
                boardHeight = bh,
            )
        }

        /** 1:1, no offset — the "no surface yet" value. */
        val IDENTITY: BoardTransform = BoardTransform(1f, 0f, 0f, 1000f, 1346f)

        private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

        private fun positiveOr(value: Float, fallback: Float): Float =
            if (value.isFinite() && value > 0f) value else fallback
    }
}
