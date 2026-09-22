package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.krafted.candytriangle.level.CandyColor

class LevelSessionTest {

    @Test
    fun testOneBallInFlightAndSugarRushOnce() {
        val levelDef = LevelDef(
            id = 1,
            world = 1,
            balls = 2,
            code = null,
            isBonus = false,
            crowns = listOf(1, 2),
            objectives = listOf(ObjectiveDef(type = ObjectiveType.SCORE, color = null, gem = null, count = null, score = 100, chain = null)),
            layout = LayoutDef(spacing = 64f, pattern = LayoutPattern.FULL, holes = emptyList(), clusters = emptyList(), movingRows = emptyList()),
            candies = CandyPlacementDef(seed = 1L, count = 0, weights = emptyMap(), fixed = emptyList()),
            gems = emptyList(),
            cup = CupDef(speed = 100f)
        )
        val session = LevelSession(levelDef)
        val world = PhysicsWorld.create(GameConfig.DEFAULTS, emptyList())
        
        // Initial state
        assertEquals(2, session.remainingBalls)
        assertEquals(0, session.activeBallsInFlight)
        
        // Launch first ball
        val launchedBall = session.launchBall(world, 0f)
        // Since world is mocked, launch() might return null if not stubbed, but that's okay, activeBallsInFlight still increments
        assertEquals(1, session.remainingBalls)
        assertEquals(1, session.activeBallsInFlight)
        
        // Try launching second ball while first is in flight -> should be rejected
        val rejectedBall = session.launchBall(world, 0f)
        assertNull(rejectedBall)
        assertEquals(1, session.remainingBalls)
        assertEquals(1, session.activeBallsInFlight)
        
        val boardState = BoardState(emptyList(), emptyList(), PhysicsParams.from(GameConfig.DEFAULTS))
        
        // Simulate score objective met during this drop
        session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 10, boardState = boardState) // 100 points
        
        // Ball exits
        session.onBallExited(boardState)
        
        // Drop completes, active balls reach 0
        assertEquals(0, session.activeBallsInFlight)
        assertTrue(session.isLevelComplete)
        assertFalse(session.isLevelFailed)
        
        // Score should be 100 + Sugar Rush (1 remaining ball * 500) = 600
        assertEquals(600, session.totalScore)
        
        // If we artificially try to trigger onDropCompleted again (which shouldn't happen unless active balls drop below 0 by bug)
        // it should NOT apply Sugar Rush again
        session.onBallExited(boardState) // Drops to 0 again artificially in the logic
        
        // Score should remain 600
        assertEquals(600, session.totalScore)
    }
}
