package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
import app.krafted.candytriangle.engine.PhysicsParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveTrackerTest {

    @Test
    fun testChainObjectiveCountsInstancesNotHighWaterMark() {
        val def = ObjectiveDef(type = ObjectiveType.CHAIN, color = null, gem = null, count = 2, score = null, chain = 5)
        val tracker = ObjectiveTracker(listOf(def))
        val board = BoardState(emptyList(), emptyList(), app.krafted.candytriangle.engine.PhysicsParams.from(app.krafted.candytriangle.level.GameConfig.DEFAULTS))
        
        // Advance chain up to 4
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 1))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 2))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 3))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 4))
        
        assertEquals(0, tracker.getProgress(def))
        assertFalse(tracker.isComplete(def, board))
        
        // Hit 5! Should count +1
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 5))
        assertEquals(1, tracker.getProgress(def))
        assertFalse(tracker.isComplete(def, board))
        
        // Keep growing the same chain, shouldn't increment count again
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 6))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.PINK, 7))
        assertEquals(1, tracker.getProgress(def))
        
        // A new chain starts and hits 5
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.BLUE, 1))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.BLUE, 2))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.BLUE, 3))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.BLUE, 4))
        tracker.processEvent(GameEvent.ChainAdvanced(CandyColor.BLUE, 5))
        
        assertEquals(2, tracker.getProgress(def))
        assertTrue(tracker.isComplete(def, board))
        assertTrue(tracker.areAllComplete(board))
    }

    /**
     * Regression (B2–C2 audit): progress is kept per objective position. `ObjectiveDef` is a data
     * class, so the old map keyed by it folded two identical objectives into one entry that every
     * event bumped twice — the same goal authored twice completed after half its count, and a
     * SCORE listed twice banked each drop double.
     */
    @Test
    fun identicalObjectivesEachCountAnEventOnce() {
        val pink = ObjectiveDef(ObjectiveType.COLLECT_CANDY, CandyColor.PINK, null, count = 5, score = null, chain = null)
        val score = ObjectiveDef(ObjectiveType.SCORE, null, null, count = null, score = 1_000, chain = null)
        val tracker = ObjectiveTracker(listOf(pink, pink.copy(), score, score.copy()))
        val board = BoardState(emptyList(), emptyList(), PhysicsParams.from(GameConfig.DEFAULTS))

        repeat(3) { tracker.processEvent(GameEvent.CandyPopped(CandyColor.PINK, isDirect = true, points = 10)) }
        tracker.addScore(600)

        assertEquals(3, tracker.getProgress(pink))
        assertEquals(600, tracker.getProgress(score))
        assertFalse("3 of 5 pink must not complete either copy", tracker.isComplete(pink, board))
        assertFalse(tracker.areAllComplete(board))

        repeat(2) { tracker.processEvent(GameEvent.CandyPopped(CandyColor.PINK, isDirect = true, points = 10)) }
        tracker.addScore(400)

        assertEquals(5, tracker.getProgress(pink))
        assertEquals(1_000, tracker.getProgress(score))
        assertTrue(tracker.areAllComplete(board))
    }
}
