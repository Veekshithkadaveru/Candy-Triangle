package app.krafted.candytriangle.ui.game

import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.CrownCalculator
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.verification.RealLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD's whole contract with `LevelSession`, checked on the JVM.
 *
 * Two things are being proved. First, **the numbers come from the session's authoritative fields**
 * — score, balls, objective progress and the win/fail decision are compared against the session
 * itself, never against a `GameEvent`, because `events` has no replay and drops its oldest entries
 * under load. Second, **the dirty check holds**: [HudStateMapper.update] must return `null` on a
 * second call with nothing in between, or the 60 Hz game thread allocates a `HudState` per idle
 * frame.
 *
 * Everything here runs against a real [LevelBoard], built the way the game builds one. There is no
 * emulator and no Robolectric in this suite, which is exactly why the mapper is a separate,
 * Compose-free class.
 */
class HudStateMapperTest {

    // ------------------------------------------------------------------ initial snapshot

    @Test
    fun firstUpdateReportsFullBallsZeroScoreAndEmptyObjectives() {
        val board = boardOf(
            balls = 9,
            objectives = listOf(
                objective(ObjectiveType.COLLECT_CANDY, color = CandyColor.PINK, count = 12),
                objective(ObjectiveType.SCORE, score = 2_500),
            ),
        )
        val mapper = HudStateMapper(board)

        val state = assertNotNull(mapper.update(0))

        assertEquals(1, state.levelId)
        assertNull(state.levelCode)
        assertEquals(0, state.score)
        assertEquals(0, state.dropSubtotal)
        assertEquals(9, state.ballsRemaining)
        assertEquals(9, state.ballsTotal)
        assertEquals(0, state.ballsInFlight)
        assertTrue(state.canLaunch)
        assertFalse(state.dropActive)
        assertEquals(LevelStatus.PLAYING, state.status)
        assertEquals(listOf(0, 0), state.objectives.map { it.current })
        assertEquals(listOf(12, 2_500), state.objectives.map { it.target })
        assertEquals(listOf(false, false), state.objectives.map { it.complete })
    }

    /** A level whose JSON lost its `count`/`score` must still print a sane target, not "3 / 0". */
    @Test
    fun missingTargetsFallBackToOneJustLikeObjectiveTracker() {
        val board = boardOf(
            objectives = listOf(
                objective(ObjectiveType.COLLECT_CANDY, count = null),
                objective(ObjectiveType.SCORE, score = null),
                objective(ObjectiveType.CUP, count = null),
            ),
        )

        val state = assertNotNull(HudStateMapper(board).update(0))

        assertEquals(listOf(1, 1, 1), state.objectives.map { it.target })
    }

    // ------------------------------------------------------------------ against a shipped level

    /**
     * The one case that runs the real engine: a full drop on the shipped level 1, with score and
     * balls compared to the session **exactly**, not approximately and not against a replayed
     * event stream.
     */
    @Test
    fun scoreAndBallsMatchTheSessionExactlyAfterARealDrop() {
        val level = RealLevels.catalog.level(1)
            ?: throw AssertionError("levels.json has no level 1")
        val board = LevelBoard.create(level, RealLevels.config)
        val mapper = HudStateMapper(board)
        mapper.update(0)

        assertNotNull("level 1 must accept a straight-down launch", board.launch(0f))
        board.stepUntilDropEnds()

        val state = assertNotNull(mapper.update(0))
        assertEquals(board.session.totalScore, state.score)
        assertEquals(board.session.remainingBalls, state.ballsRemaining)
        assertEquals(board.session.activeBallsInFlight, state.ballsInFlight)
        assertEquals(level.balls, state.ballsTotal)
        assertEquals(0, state.dropSubtotal)
    }

    // ------------------------------------------------------------------ one case per objective type

    @Test
    fun collectCandyProgressTracksTheSession() {
        val board = boardOf(
            objectives = listOf(objective(ObjectiveType.COLLECT_CANDY, color = CandyColor.PINK, count = 3)),
        )
        val mapper = HudStateMapper(board)
        mapper.update(0)

        board.session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 1, boardState = board.boardState)

        val state = assertNotNull(mapper.update(0))
        assertEquals(1, state.objectives.single().current)
        assertFalse(state.objectives.single().complete)
    }

    @Test
    fun scoreProgressOnlyMovesWhenTheDropBanks() {
        val board = boardOf(objectives = listOf(objective(ObjectiveType.SCORE, score = 100)))
        val mapper = HudStateMapper(board)
        mapper.update(0)

        board.launch(0f)
        board.session.onCandyPopped(CandyColor.BLUE, isDirect = true, chainPosition = 5, boardState = board.boardState)

        // §5.1 banks the subtotal only when the drop ends, so the objective is still at zero.
        assertEquals(0, assertNotNull(mapper.update(0)).objectives.single().current)

        board.session.onBallExited(board.boardState, board.chainTracker)

        val state = assertNotNull(mapper.update(0))
        assertEquals(board.session.totalScore, state.score)
        assertEquals(50, state.objectives.single().current)
    }

    @Test
    fun collectGemProgressTracksTheSession() {
        val board = boardOf(
            objectives = listOf(objective(ObjectiveType.COLLECT_GEM, gem = GemType.BLAST, count = 2)),
        )
        val mapper = HudStateMapper(board)
        mapper.update(0)

        board.session.onGemSmashed(GemType.BLAST, board.boardState)

        val state = assertNotNull(mapper.update(0))
        assertEquals(1, state.objectives.single().current)
        assertEquals(2, state.objectives.single().target)
        assertEquals(GemType.BLAST, state.objectives.single().gem)
    }

    @Test
    fun chainProgressCountsOnlyTheRequestedLength() {
        val board = boardOf(
            objectives = listOf(objective(ObjectiveType.CHAIN, chain = 3, count = 2)),
        )
        val mapper = HudStateMapper(board)
        mapper.update(0)

        // A shorter chain is not the objective; only the exact length counts (ObjectiveTracker).
        board.session.onChainAdvanced(CandyColor.GREEN, length = 2, boardState = board.boardState)
        assertNull(mapper.update(0))

        board.session.onChainAdvanced(CandyColor.GREEN, length = 3, boardState = board.boardState)

        val state = assertNotNull(mapper.update(0))
        assertEquals(1, state.objectives.single().current)
        assertEquals(3, state.objectives.single().chainLength)
    }

    @Test
    fun cupProgressTracksTheSessionAndTheRefundedBall() {
        val board = boardOf(balls = 4, objectives = listOf(objective(ObjectiveType.CUP, count = 2)))
        val mapper = HudStateMapper(board)
        mapper.update(0)

        board.session.onCupCaught(board.boardState)

        val state = assertNotNull(mapper.update(0))
        assertEquals(1, state.objectives.single().current)
        // §2: a catch refunds a ball, so the counter must follow the session, not the level def.
        assertEquals(board.session.remainingBalls, state.ballsRemaining)
        assertEquals(5, state.ballsRemaining)
    }

    /**
     * `CLEAR_COLOR` is the one objective `ObjectiveTracker` has no counter for — it is a live
     * predicate over `BoardState`. The mapper gives it the candies of that colour actually placed
     * as its target and counts the ones that have gone, and its dirty key is the number of active
     * candies on the board.
     */
    @Test
    fun clearColorProgressCountsCandiesRemovedFromTheBoard() {
        val board = boardOf(
            candies = CandyPlacementDef(
                seed = 4L,
                count = 6,
                weights = mapOf(CandyColor.PINK to 1),
                fixed = emptyList(),
            ),
            objectives = listOf(objective(ObjectiveType.CLEAR_COLOR, color = CandyColor.PINK)),
        )
        val placed = board.boardState.getCandiesByColor(CandyColor.PINK).size
        assertTrue("the fixture must place pink candies to clear", placed > 0)

        val mapper = HudStateMapper(board)
        val initial = assertNotNull(mapper.update(0))
        assertEquals(placed, initial.objectives.single().target)
        assertEquals(0, initial.objectives.single().current)
        assertFalse(initial.objectives.single().complete)

        board.boardState.candies.first { it.active }.active = false

        val partial = assertNotNull(mapper.update(0))
        assertEquals(1, partial.objectives.single().current)
        assertFalse(partial.objectives.single().complete)

        board.boardState.candies.forEach { it.active = false }

        val cleared = assertNotNull(mapper.update(0))
        assertEquals(placed, cleared.objectives.single().current)
        assertTrue(cleared.objectives.single().complete)
        assertEquals(1f, cleared.objectives.single().fraction, 0f)
    }

    // ------------------------------------------------------------------ status

    @Test
    fun statusFlipsToCompleteOffTheSessionFields() {
        val board = boardOf(balls = 3, objectives = listOf(objective(ObjectiveType.SCORE, score = 10)))
        val mapper = HudStateMapper(board)
        mapper.update(0)

        board.launch(0f)
        board.session.onCandyPopped(CandyColor.PINK, isDirect = true, chainPosition = 4, boardState = board.boardState)
        board.session.onBallExited(board.boardState, board.chainTracker)

        assertTrue(board.session.isLevelComplete)
        val state = assertNotNull(mapper.update(0))
        assertEquals(LevelStatus.COMPLETE, state.status)
        assertFalse(state.canLaunch)
        assertTrue(mapper.isTerminal)
    }

    @Test
    fun statusFlipsToFailedOffTheSessionFields() {
        val board = boardOf(balls = 1, objectives = listOf(objective(ObjectiveType.SCORE, score = 1_000_000)))
        val mapper = HudStateMapper(board)
        mapper.update(0)

        board.launch(0f)
        board.session.onBallExited(board.boardState, board.chainTracker)

        assertTrue(board.session.isLevelFailed)
        val state = assertNotNull(mapper.update(0))
        assertEquals(LevelStatus.FAILED, state.status)
        assertFalse(state.canLaunch)
        assertTrue(mapper.isTerminal)
    }

    @Test
    fun canLaunchMirrorsTheSessionsOwnGuardWhileADropIsActive() {
        val board = boardOf(balls = 2, objectives = listOf(objective(ObjectiveType.SCORE, score = 10)))
        val mapper = HudStateMapper(board)

        assertTrue(assertNotNull(mapper.update(0)).canLaunch)

        board.launch(0f)

        val inFlight = assertNotNull(mapper.update(0))
        assertFalse(inFlight.canLaunch)
        assertTrue(inFlight.dropActive)
        assertEquals(1, inFlight.ballsRemaining)
    }

    // ------------------------------------------------------------------ the dirty check

    /**
     * The contract that keeps an idle frame allocation-free. Without it the mapper builds a
     * `HudState` and an objective list sixty times a second for a board nobody has touched.
     */
    @Test
    fun updateReturnsNullOnASecondCallWithNoInterveningChange() {
        val board = boardOf(
            candies = CandyPlacementDef(1L, 4, mapOf(CandyColor.BLUE to 1), emptyList()),
            objectives = listOf(
                objective(ObjectiveType.COLLECT_CANDY, count = 5),
                objective(ObjectiveType.CLEAR_COLOR, color = CandyColor.BLUE),
            ),
        )
        val mapper = HudStateMapper(board)

        assertNotNull(mapper.update(0))
        assertNull(mapper.update(0))
        assertNull(mapper.update(0))
        assertNull(mapper.update(0))
    }

    @Test
    fun aRefusedLaunchIsItsOwnChange() {
        val board = boardOf(objectives = listOf(objective(ObjectiveType.SCORE, score = 10)))
        val mapper = HudStateMapper(board)

        assertNotNull(mapper.update(0))
        assertNull(mapper.update(0))

        val shaken = assertNotNull(mapper.update(1))
        assertEquals(1, shaken.launchRefusals)
        assertNull(mapper.update(1))
    }

    @Test
    fun aCandyOfAnotherColourDoesNotMoveAClearColourObjective() {
        val board = boardOf(
            candies = CandyPlacementDef(
                seed = 9L,
                count = 8,
                weights = mapOf(CandyColor.PINK to 1, CandyColor.BLUE to 1),
                fixed = emptyList(),
            ),
            objectives = listOf(objective(ObjectiveType.CLEAR_COLOR, color = CandyColor.PINK)),
        )
        val mapper = HudStateMapper(board)
        assertNotNull(mapper.update(0))

        val blue = board.boardState.candies.firstOrNull { it.active && it.color == CandyColor.BLUE }
        assertNotNull("the fixture must place a blue candy", blue)
        blue!!.active = false

        // The active-candy count moved, so the mapper re-evaluated; nothing the HUD shows changed.
        assertNull(mapper.update(0))
    }

    // ------------------------------------------------------------------ the terminal outcome

    /**
     * Crowns come from [CrownCalculator] against the session's own `remainingBalls`, not from
     * whatever the `LevelCompleted` event carried — that event is best-effort and a
     * board-clearing Sugar Storm can push it out of the buffer.
     */
    @Test
    fun outcomeCrownsEqualTheCrownCalculator() {
        val board = boardOf(
            balls = 5,
            crowns = listOf(2, 4),
            objectives = listOf(objective(ObjectiveType.SCORE, score = 10)),
        )
        val mapper = HudStateMapper(board)

        board.launch(0f)
        board.session.onCandyPopped(CandyColor.GREEN, isDirect = true, chainPosition = 4, boardState = board.boardState)
        board.session.onCandyPopped(CandyColor.GREEN, isDirect = true, chainPosition = 5, boardState = board.boardState)
        board.session.onBallExited(board.boardState, board.chainTracker)
        assertTrue(board.session.isLevelComplete)

        val outcome = mapper.outcome()
        assertEquals(
            CrownCalculator.calculateCrowns(board.level, board.session.remainingBalls, objectivesMet = true),
            outcome.crowns,
        )
        assertEquals(3, outcome.crowns)
        assertTrue(outcome.won)
        assertEquals(board.session.totalScore, outcome.score)
        assertEquals(board.session.remainingBalls, outcome.ballsRemaining)
        assertEquals(mapOf(CandyColor.GREEN to 2), outcome.collected)
    }

    @Test
    fun outcomeOnAFailedLevelIsZeroCrownsButStillCarriesTheCandies() {
        val board = boardOf(balls = 1, objectives = listOf(objective(ObjectiveType.SCORE, score = 1_000_000)))
        val mapper = HudStateMapper(board)

        board.launch(0f)
        board.session.onCandyPopped(CandyColor.PURPLE, isDirect = false, chainPosition = 0, boardState = board.boardState)
        board.session.onBallExited(board.boardState, board.chainTracker)
        assertTrue(board.session.isLevelFailed)

        val outcome = mapper.outcome()
        assertFalse(outcome.won)
        assertEquals(0, outcome.crowns)
        assertEquals(CrownCalculator.calculateCrowns(board.level, 0, objectivesMet = false), outcome.crowns)
        // §7 banks candies from a lost level too, so they have to survive the failure.
        assertEquals(mapOf(CandyColor.PURPLE to 1), outcome.collected)
    }

    // ------------------------------------------------------------------ fixtures

    private fun boardOf(
        balls: Int = 6,
        crowns: List<Int> = listOf(2, 4),
        candies: CandyPlacementDef = CandyPlacementDef(1L, 0, emptyMap(), emptyList()),
        objectives: List<ObjectiveDef> = listOf(objective(ObjectiveType.SCORE, score = 100)),
    ): LevelBoard = LevelBoard.create(
        LevelDef(
            id = 1,
            code = null,
            world = 1,
            isBonus = false,
            balls = balls,
            crowns = crowns,
            // CLUSTERS with no clusters means no pegs: the physics is not what these cases are
            // about, and a bare board keeps every one of them deterministic.
            layout = LayoutDef(
                spacing = 80f,
                pattern = LayoutPattern.CLUSTERS,
                holes = emptyList(),
                clusters = emptyList(),
                movingRows = emptyList(),
            ),
            candies = candies,
            gems = emptyList(),
            cup = CupDef(speed = 200f),
            objectives = objectives,
        ),
        GameConfig.DEFAULTS,
    )

    private fun objective(
        type: ObjectiveType,
        color: CandyColor? = null,
        gem: GemType? = null,
        count: Int? = null,
        score: Int? = null,
        chain: Int? = null,
    ) = ObjectiveDef(type = type, color = color, gem = gem, count = count, score = score, chain = chain)

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private fun <T : Any> assertNotNull(message: String, value: T?): T {
        org.junit.Assert.assertNotNull(message, value)
        return value!!
    }
}
