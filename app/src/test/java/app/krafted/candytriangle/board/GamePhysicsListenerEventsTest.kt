package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.GemEffect
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelSession
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.level.RowCol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B3 follow-ups: config-driven Sugar Pop / Sugar Storm and the new VFX events. */
class GamePhysicsListenerEventsTest {

    private val d = 80f

    private val level = LevelDef(
        id = 1, code = null, world = 1, isBonus = false, balls = 5, crowns = listOf(1, 2),
        layout = LayoutDef(d, LayoutPattern.FULL, emptyList(), emptyList(), emptyList()),
        candies = CandyPlacementDef(1L, 0, emptyMap(), emptyList()),
        gems = emptyList(),
        cup = CupDef(0f),
        objectives = listOf(ObjectiveDef(ObjectiveType.SCORE, null, null, null, 1_000_000, null)),
    )

    private class Rig(
        val session: LevelSession,
        val state: BoardState,
        val listener: GamePhysicsListener,
        val world: PhysicsWorld,
        val events: MutableList<GameEvent>,
    )

    private fun rig(config: GameConfig, candies: List<Candy>, gems: List<Gem>, pegs: List<Peg>, body: (Rig) -> Unit) =
        runBlocking {
            val params = PhysicsParams.from(config)
            val session = LevelSession(level, config.scoring)
            val state = BoardState(candies, gems, params)
            val listener = GamePhysicsListener(
                session, state, ChainTracker(config.chains), CandyCup(180f, 0f), config, params, d,
            )
            val world = PhysicsWorld.create(config, pegs, listener)
            listener.world = world
            val events = mutableListOf<GameEvent>()
            val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { events += it }
            }
            body(Rig(session, state, listener, world, events))
            job.cancel()
        }

    private fun candy(id: Int, color: CandyColor, x: Float, y: Float) = Candy(id, color, x, y, RowCol(0, id))

    /** Three pink candies under a resting ball at (500, 600): chain 1, 2, 3 in one sensor pass. */
    private fun chainOfThree() = listOf(
        candy(0, CandyColor.PINK, 500f, 600f),
        candy(1, CandyColor.PINK, 505f, 600f),
        candy(2, CandyColor.PINK, 495f, 600f),
    )

    @Test
    fun sugarPopRadiusFollowsConfig() {
        val near = candy(3, CandyColor.PINK, 570f, 600f) // 70 u from the trigger
        val mid = candy(4, CandyColor.PINK, 640f, 600f) // 140 u
        val otherColour = candy(5, CandyColor.BLUE, 560f, 600f)

        // Factor 1.0 → radius 80: pops only `near`.
        val narrow = GameConfig.DEFAULTS.copy(chains = GameConfig.DEFAULTS.chains.copy(sugarPopRadiusFactor = 1f))
        val candies1 = chainOfThree() + listOf(near, mid, otherColour)
        rig(narrow, candies1, emptyList(), emptyList()) { r ->
            r.world.spawnBall(500f, 600f, 0f, 0f)
            r.listener.afterStep(r.world)
            assertFalse(near.active)
            assertTrue(mid.active)
            assertTrue(otherColour.active)
            val directHits = r.events.filterIsInstance<GameEvent.CandyPopped>().filter { it.isDirect }
            assertEquals(listOf(500f, 505f, 495f), directHits.map { it.x })
            assertEquals(listOf(600f, 600f, 600f), directHits.map { it.y })
            val pop = r.events.filterIsInstance<GameEvent.SugarPop>().single()
            assertEquals(GameEvent.SugarPop(CandyColor.PINK, 495f, 600f, 1), pop)
            assertEquals(mapOf(CandyColor.PINK to 4), r.session.collectedByColor)
        }

        // Default factor 2.0 → radius 160: pops both.
        val near2 = candy(3, CandyColor.PINK, 570f, 600f)
        val mid2 = candy(4, CandyColor.PINK, 640f, 600f)
        rig(GameConfig.DEFAULTS, chainOfThree() + listOf(near2, mid2), emptyList(), emptyList()) { r ->
            r.world.spawnBall(500f, 600f, 0f, 0f)
            r.listener.afterStep(r.world)
            assertFalse(near2.active)
            assertFalse(mid2.active)
            assertEquals(2, r.events.filterIsInstance<GameEvent.SugarPop>().single().popped)
        }
    }

    private fun gemAt(type: GemType, id: Int, x: Float, y: Float, params: PhysicsParams): Gem {
        val peg = Peg(id, x, y, params.gemRadius, params.restitutionGem, ColliderKind.GEM)
        return Gem(type, peg, RowCol(5, id))
    }

    @Test
    fun gemEffectTriggeredForEffectGemsOnly() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        val blast = gemAt(GemType.BLAST, 0, 400f, 500f, params)
        val sweet = gemAt(GemType.SWEET, 1, 600f, 500f, params)
        val candies = listOf(
            candy(0, CandyColor.GREEN, 450f, 500f), // inside 2.5·d = 200 of the Blast
            candy(1, CandyColor.BLUE, 400f, 650f),
            candy(2, CandyColor.BLUE, 900f, 900f), // far
        )
        rig(config, candies, listOf(blast, sweet), listOf(blast.peg, sweet.peg)) { r ->
            val ball = r.world.spawnBall(400f, 460f, 0f, 100f)
            r.listener.onPegContact(ball, sweet.peg, 100f)
            assertTrue(r.events.none { it is GameEvent.GemEffectTriggered })

            r.listener.onPegContact(ball, blast.peg, 100f)
            val fx = r.events.filterIsInstance<GameEvent.GemEffectTriggered>().single()
            assertEquals(GameEvent.GemEffectTriggered(GemType.BLAST, 400f, 500f, 2), fx)
            assertEquals(
                listOf(
                    GameEvent.GemSmashed(GemType.SWEET, config.scoring.gemBrokenPoints, 600f, 500f),
                    GameEvent.GemSmashed(GemType.BLAST, config.scoring.gemBrokenPoints, 400f, 500f),
                ),
                r.events.filterIsInstance<GameEvent.GemSmashed>(),
            )
            assertEquals(mapOf(CandyColor.GREEN to 1, CandyColor.BLUE to 1), r.session.collectedByColor)

            // A second contact with the smashed gem is ignored.
            r.listener.onPegContact(ball, blast.peg, 100f)
            assertEquals(1, r.events.filterIsInstance<GameEvent.GemEffectTriggered>().size)
        }
    }

    @Test
    fun sugarStormCapComesFromConfig() {
        val base = GameConfig.DEFAULTS
        assertEquals(1, GamePhysicsListener(
            LevelSession(level), BoardState(emptyList(), emptyList(), PhysicsParams.from(base)),
            ChainTracker(base.chains), CandyCup(180f, 0f), base, PhysicsParams.from(base), d,
        ).remainingSugarStorms)

        val twoStorms = base.copy(
            gems = base.gems.map {
                if (it.type == GemType.SUGAR_STORM) it.copy(effect = GemEffect.PopAllOfLargestColour(2)) else it
            },
        )
        val params = PhysicsParams.from(twoStorms)
        val storms = (0 until 3).map { gemAt(GemType.SUGAR_STORM, it, 300f + 200f * it, 500f, params) }
        val candies = (0 until 9).map { candy(it, CandyColor.entries[it % 3], 100f + 10f * it, 900f) }
        rig(twoStorms, candies, storms, storms.map { it.peg }) { r ->
            assertEquals(2, r.listener.remainingSugarStorms)
            val ball = r.world.spawnBall(500f, 460f, 0f, 100f)
            for (g in storms) r.listener.onPegContact(ball, g.peg, 100f)
            assertEquals(0, r.listener.remainingSugarStorms)
            val fx = r.events.filterIsInstance<GameEvent.GemEffectTriggered>()
            assertEquals("third storm is past the cap: no effect, no event", 2, fx.size)
            assertEquals(listOf(3, 3), fx.map { it.popped })
            assertEquals(3, r.state.candies.count { it.active })
        }
    }
}
