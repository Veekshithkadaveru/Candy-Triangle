package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BoardTransform` is the only input path the game has: a touch becomes an aim through its inverse.
 * It is also completely Android-free, so all of it is verifiable here, with no emulator.
 */
class BoardTransformTest {

    private val boardW = 1000f
    private val boardH = 1346f

    // -- content box ------------------------------------------------------------------------------

    @Test
    fun `contentHeight on DEFAULTS is 1346, derived from the cup lane`() {
        assertEquals(1346f, BoardTransform.contentHeight(GameConfig.DEFAULTS), 0f)
    }

    @Test
    fun `contentHeight follows the cup lane when config moves it`() {
        val defaults = GameConfig.DEFAULTS
        val deeper = defaults.copy(cup = defaults.cup.copy(laneYBottom = 1500f))
        // 1500 + ballRadius 16, not the board height of 1250 and not a hardcoded 1346.
        assertEquals(1516f, BoardTransform.contentHeight(deeper), 0f)
    }

    @Test
    fun `contentHeight falls back to the board height when the lane is shallow`() {
        val defaults = GameConfig.DEFAULTS
        val shallow = defaults.copy(cup = defaults.cup.copy(laneYBottom = 900f))
        assertEquals(defaults.board.height, BoardTransform.contentHeight(shallow), 0f)
    }

    // -- fit --------------------------------------------------------------------------------------

    @Test
    fun `a 1080x2400 portrait surface is width-limited`() {
        val t = BoardTransform.fit(1080f, 2400f, boardW, boardH)
        assertEquals(1080f / 1000f, t.scale, 1e-6f)
        assertEquals(0f, t.offsetX, 1e-4f)
        assertEquals(1080f, t.playRight - t.playLeft, 1e-3f)
        // Vertical slack is centred by the default bias of 0.5.
        val slack = 2400f - boardH * t.scale
        assertEquals(slack * 0.5f, t.offsetY, 1e-3f)
    }

    @Test
    fun `a square surface is height-limited`() {
        val t = BoardTransform.fit(1000f, 1000f, boardW, boardH)
        assertEquals(1000f / 1346f, t.scale, 1e-6f)
        // The board no longer fills the width, so it is centred horizontally.
        assertTrue(t.offsetX > 0f)
        assertEquals(0f, t.offsetY, 1e-3f)
        assertEquals(1000f, t.playBottom - t.playTop, 1e-3f)
    }

    @Test
    fun `insets shrink the available box`() {
        val plain = BoardTransform.fit(1080f, 2400f, boardW, boardH)
        val inset = BoardTransform.fit(
            surfaceWidthPx = 1080f,
            surfaceHeightPx = 2400f,
            boardWidth = boardW,
            boardHeight = boardH,
            insetTopPx = 200f,
            insetBottomPx = 300f,
            insetLeftPx = 40f,
            insetRightPx = 40f,
        )
        assertTrue("insets must not widen the fit", inset.scale < plain.scale)
        assertEquals(1000f / 1000f, inset.scale, 1e-6f) // availW = 1000
        assertEquals(40f, inset.offsetX, 1e-4f)
        // availH = 1900, content = 1346 -> 554 of slack, centred inside the inset band.
        assertEquals(200f + (1900f - 1346f) * 0.5f, inset.offsetY, 1e-3f)
        assertTrue(inset.playTop >= 200f)
        assertTrue(inset.playBottom <= 2400f - 300f)
    }

    @Test
    fun `verticalBias of zero pins the apex to the top inset`() {
        val t = BoardTransform.fit(
            surfaceWidthPx = 1080f,
            surfaceHeightPx = 2400f,
            boardWidth = boardW,
            boardHeight = boardH,
            insetTopPx = 150f,
            verticalBias = 0f,
        )
        assertEquals(150f, t.offsetY, 1e-4f)
        assertEquals(150f, t.sy(0f), 1e-4f) // the apex is at board y = 0
        assertEquals(150f, t.playTop, 1e-4f)
    }

    @Test
    fun `verticalBias of one pins the cup lane to the bottom inset`() {
        val t = BoardTransform.fit(
            surfaceWidthPx = 1080f,
            surfaceHeightPx = 2400f,
            boardWidth = boardW,
            boardHeight = boardH,
            insetBottomPx = 100f,
            verticalBias = 1f,
        )
        assertEquals(2400f - 100f, t.playBottom, 1e-3f)
    }

    // -- the inverse ------------------------------------------------------------------------------

    @Test
    fun `bx and by invert sx and sy across the whole board`() {
        val t = BoardTransform.fit(1080f, 2400f, boardW, boardH, insetTopPx = 180f, insetBottomPx = 240f)
        var x = 0f
        while (x <= boardW) {
            assertEquals(x, t.bx(t.sx(x)), 1e-3f)
            x += 25f
        }
        var y = 0f
        while (y <= boardH) {
            assertEquals(y, t.by(t.sy(y)), 1e-3f)
            y += 25f
        }
    }

    @Test
    fun `len is scale without offset`() {
        val t = BoardTransform.fit(1080f, 2400f, boardW, boardH, insetTopPx = 180f)
        assertEquals(t.sx(100f) - t.sx(0f), t.len(100f), 1e-3f)
        assertEquals(0f, t.len(0f), 0f)
    }

    @Test
    fun `the cup lane exit stays inside the viewport`() {
        val t = BoardTransform.fit(1080f, 2400f, boardW, BoardTransform.contentHeight(GameConfig.DEFAULTS))
        // params.exitY = 1346: a ball is retired only after it has crossed the whole lane, so the
        // lane must be on screen or the catch happens off the bottom edge.
        assertTrue(t.sy(1346f) <= t.playBottom + 1e-3f)
        assertTrue(t.sy(GameConfig.DEFAULTS.cup.laneYTop) < t.playBottom)
    }

    // -- degenerate input -------------------------------------------------------------------------

    @Test
    fun `a zero-sized surface still gives a finite invertible transform`() {
        val t = BoardTransform.fit(0f, 0f, boardW, boardH)
        assertFinite(t)
        assertTrue(t.scale >= BoardTransform.MIN_SCALE)
        assertTrue(t.bx(0f).isFinite())
        assertTrue(t.by(0f).isFinite())
    }

    @Test
    fun `negative surface dimensions still give a finite transform`() {
        val t = BoardTransform.fit(-1080f, -2400f, boardW, boardH)
        assertFinite(t)
        assertEquals(BoardTransform.MIN_SCALE, t.scale, 0f)
    }

    @Test
    fun `insets larger than the surface still give a finite transform`() {
        val t = BoardTransform.fit(1080f, 2400f, boardW, boardH, insetTopPx = 2000f, insetBottomPx = 2000f)
        assertFinite(t)
        assertEquals(BoardTransform.MIN_SCALE, t.scale, 0f)
    }

    @Test
    fun `non-finite surface dimensions and insets are treated as zero`() {
        val t = BoardTransform.fit(
            surfaceWidthPx = Float.NaN,
            surfaceHeightPx = Float.POSITIVE_INFINITY,
            boardWidth = boardW,
            boardHeight = boardH,
            insetTopPx = Float.NEGATIVE_INFINITY,
            insetLeftPx = Float.NaN,
        )
        assertFinite(t)
    }

    @Test
    fun `non-positive board dimensions fall back rather than divide by zero`() {
        val t = BoardTransform.fit(1080f, 2400f, 0f, -5f)
        assertFinite(t)
        assertEquals(BoardTransform.FALLBACK_BOARD_WIDTH, t.boardWidth, 0f)
        assertEquals(BoardTransform.FALLBACK_BOARD_HEIGHT, t.boardHeight, 0f)
    }

    @Test
    fun `a non-finite verticalBias falls back to the default`() {
        val t = BoardTransform.fit(1080f, 2400f, boardW, boardH, verticalBias = Float.NaN)
        val expected = BoardTransform.fit(1080f, 2400f, boardW, boardH)
        assertEquals(expected.offsetY, t.offsetY, 1e-4f)
    }

    @Test
    fun `IDENTITY is the no-surface-yet value`() {
        val t = BoardTransform.IDENTITY
        assertEquals(1f, t.scale, 0f)
        assertEquals(7f, t.sx(7f), 0f)
        assertEquals(7f, t.by(7f), 0f)
    }

    private fun assertFinite(t: BoardTransform) {
        assertTrue("scale $t", t.scale.isFinite() && t.scale > 0f)
        assertTrue("offsetX $t", t.offsetX.isFinite())
        assertTrue("offsetY $t", t.offsetY.isFinite())
        assertTrue("playLeft $t", t.playLeft.isFinite())
        assertTrue("playTop $t", t.playTop.isFinite())
        assertTrue("playRight $t", t.playRight.isFinite())
        assertTrue("playBottom $t", t.playBottom.isFinite())
    }
}
