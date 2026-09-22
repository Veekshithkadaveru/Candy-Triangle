package app.krafted.candytriangle.level

import app.krafted.candytriangle.board.BoardState
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
}
