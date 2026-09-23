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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    private fun level(
        balls: Int = 3,
        objectives: List<ObjectiveDef> = listOf(
            ObjectiveDef(type = ObjectiveType.SCORE, color = null, gem = null, count = null, score = 100, chain = null),
        ),
    ) = LevelDef(
        id = 1, world = 1, balls = balls, code = null, isBonus = false, crowns = listOf(1, 2),
        objectives = objectives,
        layout = LayoutDef(spacing = 80f, pattern = LayoutPattern.FULL, holes = emptyList(), clusters = emptyList(), movingRows = emptyList()),
        candies = CandyPlacementDef(seed = 1L, count = 0, weights = emptyMap(), fixed = emptyList()),
        gems = emptyList(),
        cup = CupDef(speed = 260f),
    )

    private val boardState = BoardState(emptyList(), emptyList(), PhysicsParams.from(GameConfig.DEFAULTS))

    private fun world() = PhysicsWorld.create(GameConfig.DEFAULTS, emptyList())

    @Test
    fun collectedByColorCountsDirectAndEffectPops() {
        val session = LevelSession(level())
        assertTrue(session.collectedByColor.isEmpty())

        session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 1, boardState = boardState)
        session.onCandyPopped(CandyColor.PINK, isDirect = false, chainPosition = 0, boardState = boardState)
        session.onCandyPopped(CandyColor.BLUE, isDirect = false, chainPosition = 0, boardState = boardState)
        val snapshot = session.collectedByColor
        assertEquals(mapOf(CandyColor.PINK to 2, CandyColor.BLUE to 1), snapshot)

        session.onCandyPopped(CandyColor.GREEN, isDirect = true, chainPosition = 1, boardState = boardState)
        assertEquals("an earlier snapshot never changes", mapOf(CandyColor.PINK to 2, CandyColor.BLUE to 1), snapshot)
        assertEquals(3, session.collectedByColor.size)
        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot as MutableMap<CandyColor, Int>
        try {
            mutable[CandyColor.PURPLE] = 1
            org.junit.Assert.fail("snapshot must be read-only")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test
    fun scoringComesFromConfig() {
        val scoring = ScoringConfig(
            directCandyPointsPerChainPosition = 7,
            directCandyPointsCap = 30,
            poppedCandyPoints = 3,
            gemBrokenPoints = 11,
            cupCatchPoints = 13,
            sugarRushPointsPerRemainingBall = 250,
        )
        val session = LevelSession(level(objectives = listOf(ObjectiveDef(ObjectiveType.SCORE, null, null, null, 10, null))), scoring)
        session.launchBall(world(), 0f)
        session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 2, boardState = boardState) // 14
        session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 9, boardState = boardState) // cap 30
        session.onCandyPopped(CandyColor.PINK, isDirect = false, chainPosition = 0, boardState = boardState) // 3
        session.onGemSmashed(GemType.SWEET, boardState) // 11
        session.onCupCaught(boardState) // 13, +1 ball
        assertEquals(14 + 30 + 3 + 11 + 13, session.dropSubtotal)
        session.onBallExited(boardState)
        assertTrue(session.isLevelComplete)
        // 71 subtotal x1, + 3 balls remaining (3 - 1 + 1 cup refund) x 250.
        assertEquals(71 + 3 * 250, session.totalScore)
    }

    @Test
    fun sugarRushEmittedBeforeLevelCompleted() = runBlocking {
        val session = LevelSession(level(balls = 4))
        val events = mutableListOf<GameEvent>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { events += it }
        }
        session.launchBall(world(), 0.25f)
        session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 10, boardState = boardState)
        session.onBallExited(boardState)
        job.cancel()

        assertEquals(GameEvent.BallLaunched(0.25f, 3), events.first())
        val rushIndex = events.indexOfFirst { it is GameEvent.SugarRush }
        val completeIndex = events.indexOfFirst { it is GameEvent.LevelCompleted }
        assertTrue(rushIndex >= 0 && completeIndex == rushIndex + 1)
        assertEquals(GameEvent.SugarRush(balls = 3, bonus = 1500), events[rushIndex])
        assertEquals(GameEvent.LevelCompleted(crowns = 3, finalScore = 1600), events[completeIndex])
        assertEquals(1600, session.totalScore)
    }

    @Test
    fun burstOfEventsStillDeliversLevelCompleted() = runBlocking {
        val session = LevelSession(level(balls = 2))
        val completed = CompletableDeferred<GameEvent.LevelCompleted>()
        var received = 0
        // Subscribed now, but runs on this (busy) thread, so it can only drain after the burst.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect {
                received++
                if (it is GameEvent.LevelCompleted) completed.complete(it)
            }
        }
        session.launchBall(world(), 0f)
        repeat(150) { // 2 events each: CandyPopped + ObjectiveProgressUpdated
            session.onCandyPopped(CandyColor.BLUE, isDirect = false, chainPosition = 0, boardState = boardState)
        }
        session.onBallExited(boardState) // BallExited, DropCompleted, ..., SugarRush, LevelCompleted
        assertTrue(session.isLevelComplete)

        val event = withTimeout(5_000) { completed.await() }
        job.cancel()
        assertEquals(session.totalScore, event.finalScore)
        assertTrue("oldest events were dropped, not the newest", received <= LevelSession.EVENT_BUFFER_CAPACITY + 1)
        assertEquals(mapOf(CandyColor.BLUE to 150), session.collectedByColor)
    }

    @Test
    fun cupRefundComesFromCupConfig() {
        fun refundAfterCatch(cup: CupConfig?): Int {
            val session = if (cup == null) LevelSession(level(balls = 5)) else LevelSession(level(balls = 5), ScoringConfig(), cup)
            session.onCupCaught(boardState)
            return session.remainingBalls - 5
        }
        assertEquals("default refund is 1", 1, refundAfterCatch(null))
        assertEquals(2, refundAfterCatch(CupConfig(catchRefundBalls = 2)))
        assertEquals(0, refundAfterCatch(CupConfig(catchRefundBalls = 0)))
        assertEquals("negative refunds nothing", 0, refundAfterCatch(CupConfig(catchRefundBalls = -3)))
    }
}
