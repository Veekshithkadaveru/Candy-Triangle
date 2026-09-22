package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.ChainConfig
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.GemPlacementDef
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelSession
import app.krafted.candytriangle.level.MovingRowDef
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.level.RowCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePhysicsListenerTest {

    private fun createTestLevel(): LevelDef {
        return LevelDef(
            id = 1,
            world = 1,
            balls = 5,
            code = null,
            isBonus = false,
            crowns = listOf(1, 2),
            objectives = listOf(ObjectiveDef(type = ObjectiveType.SCORE, color = null, gem = null, count = null, score = 1000, chain = null)),
            layout = LayoutDef(spacing = 64f, pattern = LayoutPattern.FULL, holes = emptyList(), clusters = emptyList(), movingRows = emptyList()),
            candies = CandyPlacementDef(seed = 1L, count = 0, weights = emptyMap(), fixed = emptyList()),
            gems = emptyList(),
            cup = CupDef(speed = 100f)
        )
    }

    @Test
    fun testSplitGemCloneVelocityMirrored() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        val levelDef = createTestLevel()
        val session = LevelSession(levelDef)

        val peg = Peg(1, 400f, 400f, params.gemRadius, params.restitutionGem, ColliderKind.GEM)
        val gem = Gem(GemType.SPLIT, peg, RowCol(2, 2))
        val boardState = BoardState(emptyList(), listOf(gem), params)
        val chainTracker = ChainTracker(ChainConfig())
        val cup = CandyCup(width = 180f, speed = 100f)

        val listener = GamePhysicsListener(
            session = session,
            boardState = boardState,
            chainTracker = chainTracker,
            cup = cup,
            config = config,
            params = params,
            latticeSpacing = 64f
        )

        val world = PhysicsWorld.create(config, listOf(peg), listener)
        listener.world = world

        val ball = Ball(id = 1, x = 400f, y = 400f, vx = 120f, vy = 200f, radius = params.ballRadius, skin = app.krafted.candytriangle.level.BallSkin.DEFAULT)

        listener.onPegContact(ball, peg, impactSpeed = 233f)

        // Verify clone spawned
        val spawnedClones = world.balls.filter { it.id != ball.id }
        assertEquals("Exactly one clone should be spawned for Split Gem", 1, spawnedClones.size)

        val clone = spawnedClones.first()
        assertEquals("Clone horizontal velocity should be mirrored (-120f)", -120f, clone.vx, 0.001f)
        assertEquals("Clone vertical velocity should remain identical (200f)", 200f, clone.vy, 0.001f)
    }

    @Test
    fun testDropStateResetsOnLaunchAndDropCompletion() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        val levelDef = createTestLevel()
        val session = LevelSession(levelDef)

        val peg = Peg(1, 200f, 200f, params.gemRadius, params.restitutionGem, ColliderKind.GEM)
        val gem = Gem(GemType.SWEET, peg, RowCol(1, 1)) // Multiplier = 2
        val boardState = BoardState(emptyList(), listOf(gem), params)
        val chainTracker = ChainTracker(ChainConfig(extraBallChain = 1, extraBallMaxPerLaunchedBall = 1))

        // Trigger gem break and chain advancement
        boardState.registerGemBreak(gem)
        assertEquals(5, boardState.dropMultiplier)

        val candy = Candy(1, CandyColor.GREEN, 100f, 100f, RowCol(0, 0))
        chainTracker.onCandyPopped(candy, direct = true)
        assertEquals(1, chainTracker.currentChain)
        assertEquals(1, chainTracker.extraBallsAwarded)

        val world = PhysicsWorld.create(config, emptyList())

        // Launch next ball -> drop state should reset
        session.launchBall(world, 0f, boardState = boardState, chainTracker = chainTracker)

        assertEquals("Board drop multiplier should reset to 1 on launch", 1, boardState.dropMultiplier)
        assertEquals("ChainTracker chain length should reset to 0 on launch", 0, chainTracker.currentChain)
        assertEquals("ChainTracker extra balls awarded should reset to 0 on launch", 0, chainTracker.extraBallsAwarded)

        // Smash gem again during current drop
        gem.active = true
        boardState.registerGemBreak(gem)
        assertEquals(5, boardState.dropMultiplier)

        // Drop ends via ball exit
        session.onBallExited(boardState, chainTracker)

        assertEquals("Board drop multiplier should reset to 1 on drop completion", 1, boardState.dropMultiplier)
        assertEquals("ChainTracker chain length should reset to 0 on drop completion", 0, chainTracker.currentChain)
    }

    @Test
    fun testSharedFlowBroadcastToMultipleCollectors() = runBlocking {
        val levelDef = createTestLevel()
        val session = LevelSession(levelDef)
        val boardState = BoardState(emptyList(), emptyList(), PhysicsParams.from(GameConfig.DEFAULTS))

        val collector1Events = mutableListOf<GameEvent>()
        val collector2Events = mutableListOf<GameEvent>()

        val job1 = launch(Dispatchers.Unconfined) {
            session.events.collect { collector1Events.add(it) }
        }
        val job2 = launch(Dispatchers.Unconfined) {
            session.events.collect { collector2Events.add(it) }
        }

        // Emit events
        session.onGemSmashed(GemType.BLAST, boardState)
        session.onCandyPopped(CandyColor.BLUE, isDirect = true, chainPosition = 1, boardState = boardState)

        job1.cancel()
        job2.cancel()

        assertTrue("Collector 1 should receive events", collector1Events.isNotEmpty())
        assertTrue("Collector 2 should receive events", collector2Events.isNotEmpty())
        assertEquals("Both collectors should receive exact same number of events", collector1Events.size, collector2Events.size)
        assertEquals(collector1Events, collector2Events)
    }

    @Test
    fun testCupCatchInAfterStep() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        val levelDef = createTestLevel()
        val session = LevelSession(levelDef)
        val boardState = BoardState(emptyList(), emptyList(), params)
        val chainTracker = ChainTracker(ChainConfig())
        val cup = CandyCup(width = 200f, speed = 0f) // Stationary cup at center (x = 500)

        val listener = GamePhysicsListener(
            session = session,
            boardState = boardState,
            chainTracker = chainTracker,
            cup = cup,
            config = config,
            params = params,
            latticeSpacing = 64f
        )

        val world = PhysicsWorld.create(config, emptyList(), listener)
        listener.world = world

        // Spawn ball in flight right above cup lane
        val ball = world.spawnBall(500f, 1300f, 0f, 100f)
        session.launchBall(world, 0f) // activeBallsInFlight = 1

        listener.afterStep(world)

        assertFalse("Ball should be removed from world after cup catch", world.balls.contains(ball))
        assertEquals("Session totalScore should increase by cup points (100)", 100, session.totalScore)
    }

    @Test
    fun testNegativeMovingRowAmplitudeClearanceCheck() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)

        // Level with a moving row having negative amplitude (-80f)
        val level = LevelDef(
            id = 1,
            code = null,
            world = 1,
            isBonus = false,
            balls = 10,
            crowns = listOf(1, 2),
            layout = LayoutDef(
                spacing = 80f,
                pattern = LayoutPattern.FULL,
                holes = emptyList(),
                clusters = emptyList(),
                movingRows = listOf(MovingRowDef(row = 0, amplitude = -80f, periodSeconds = 2f)),
            ),
            candies = CandyPlacementDef(1, 0, emptyMap(), emptyList()),
            gems = emptyList(),
            cup = CupDef(200f),
            objectives = emptyList(),
        )

        val pegs = LayoutBuilder.buildPegs(level, config, params)
        
        // Verify wall clearance rule holds even with negative amplitude
        val walls = app.krafted.candytriangle.engine.WallSegment.boardWalls(config.board, params.restitutionWall)
        val contact = app.krafted.candytriangle.engine.Contact()
        var violated = false
        val minRadius = config.clearance.minPegEdgeToWall + params.pegRadius
        for (p in pegs) {
            for (w in walls) {
                if (w.contact(p.x, p.y, minRadius, contact)) {
                    violated = true
                }
            }
        }
        assertFalse("No peg should violate minimum wall clearance", violated)
    }
}
