package app.krafted.candytriangle.board

import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.FixedCandy
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemPlacementDef
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.level.RowCol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end wiring of [LevelBoard.create]: layout, candies, session and physics together. */
class LevelBoardTest {

    private val config = GameConfig.DEFAULTS

    private val fixed = listOf(
        FixedCandy(CandyColor.PINK, 3, 2),
        FixedCandy(CandyColor.PINK, 4, 5),
        FixedCandy(CandyColor.BLUE, 10, 10),
    )

    private fun level(seed: Long = 42L, cupSpeed: Float = 260f) = LevelDef(
        id = 7,
        code = null,
        world = 2,
        isBonus = false,
        balls = 8,
        crowns = listOf(2, 4),
        layout = LayoutDef(80f, LayoutPattern.FULL, emptyList(), emptyList(), emptyList()),
        candies = CandyPlacementDef(seed, 40, CandyColor.entries.associateWith { 1 }, fixed),
        gems = listOf(
            GemPlacementDef(GemType.BLAST, 6, 3),
            GemPlacementDef(GemType.SWEET, 9, 4),
            GemPlacementDef(GemType.LINE, 11, 7),
        ),
        cup = CupDef(cupSpeed),
        // Unreachable, so every ball gets played.
        objectives = listOf(ObjectiveDef(ObjectiveType.COLLECT_CANDY, null, null, 10_000, null, null)),
    )

    private val aims = listOf(-0.9f, -0.5f, -0.2f, 0.05f, 0.3f, 0.6f, 1.0f, -1.1f)

    /** Plays every ball; returns the per-drop total score after each drop. */
    private fun playOut(board: LevelBoard): List<Int> {
        val scores = ArrayList<Int>()
        var k = 0
        while (k < 60) {
            val ball = board.launch(aims[k % aims.size]) ?: break
            assertNotNull(ball)
            assertTrue(board.isDropActive)
            val steps = board.stepUntilDropEnds()
            assertTrue("drop $k ends before the step cap", steps < LevelBoard.DEFAULT_MAX_DROP_STEPS)
            assertFalse(board.isDropActive)
            scores += board.session.totalScore
            k++
        }
        return scores
    }

    @Test
    fun createWiresEverything() {
        val board = LevelBoard.create(level(), config)
        assertTrue(board.layout.rejectedGems.isEmpty())
        assertEquals(3, board.layout.gems.size)
        assertEquals(board.layout.gems, board.boardState.gems)
        assertTrue("fixed + seeded candies placed", board.candies.size > fixed.size)
        assertEquals(fixed.map { it.color }, board.candies.take(fixed.size).map { it.color })
        assertEquals(fixed.map { RowCol(it.row, it.col) }, board.candies.take(fixed.size).map { it.rc })
        assertEquals(board.layout.pegs.size, board.world.pegs.size)
        assertFalse(board.isDropActive)
        assertEquals(8, board.session.remainingBalls)
    }

    @Test
    fun cupSpeedClampedToConfigRange() {
        assertEquals(config.cup.speedMax, LevelBoard.create(level(cupSpeed = 5_000f), config).cup.speed, 0f)
        assertEquals(config.cup.speedMin, LevelBoard.create(level(cupSpeed = 1f), config).cup.speed, 0f)
    }

    @Test
    fun launchesPlayOutAndScore() {
        val board = LevelBoard.create(level(), config)
        val scores = playOut(board)
        assertTrue("at least the starting balls were played", scores.size >= 8)
        assertTrue("something scored", board.session.totalScore > 0)
        assertTrue(board.session.isLevelFailed)
        assertNull("no launch after the level ends", board.launch(0f))

        // Every candy that left the board was counted, per colour.
        val gone = board.candies.filter { !it.active }.groupingBy { it.color }.eachCount()
        assertEquals(gone, board.session.collectedByColor)
    }

    @Test
    fun sameSeedSameCandies() {
        fun snapshot(b: LevelBoard) = b.candies.map { listOf(it.id, it.color, it.x, it.y, it.rc) }
        val a = LevelBoard.create(level(seed = 99L), config)
        val b = LevelBoard.create(level(seed = 99L), config)
        assertEquals(snapshot(a), snapshot(b))
        val c = LevelBoard.create(level(seed = 100L), config)
        assertNotEquals(snapshot(a), snapshot(c))
    }

    @Test
    fun sameAimsSameOutcome() {
        val a = LevelBoard.create(level(), config)
        val b = LevelBoard.create(level(), config)
        assertEquals(playOut(a), playOut(b))
        assertEquals(a.candies.map { it.active }, b.candies.map { it.active })
        assertEquals(a.session.collectedByColor, b.session.collectedByColor)
    }
}
