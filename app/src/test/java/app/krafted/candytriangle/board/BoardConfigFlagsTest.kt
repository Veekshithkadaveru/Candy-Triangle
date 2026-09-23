package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.ChainConfig
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemEffect
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelSession
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.level.RowCol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Config knobs that B3 used to hard-code or ignore: Line band row factor and the chain flags. */
class BoardConfigFlagsTest {

    private val params = PhysicsParams.from(GameConfig.DEFAULTS)

    private fun candy(id: Int, color: CandyColor, x: Float, y: Float) = Candy(id, color, x, y, RowCol(0, id))

    // --- (1) Line Gem band uses the lattice row factor --------------------------------------------

    @Test
    fun lineBandHeightFollowsRowSpacingFactor() {
        val gem = Gem(GemType.LINE, Peg(0, 500f, 500f, params.gemRadius, params.restitutionGem, ColliderKind.GEM), RowCol(5, 2))
        val effect = GemEffect.PopBand(bandRows = 1f)
        fun popsAt(dy: Float, factor: Float?): Boolean {
            val c = candy(0, CandyColor.PINK, 100f, 500f + dy)
            val state = BoardState(listOf(c), listOf(gem), params)
            val result = if (factor == null) {
                GemEffectHandlers.applyEffect(effect, gem, state, 80f)
            } else {
                GemEffectHandlers.applyEffect(effect, gem, state, 80f, rowSpacingFactor = factor)
            }
            return result.poppedCandies.isNotEmpty()
        }
        // Default √3/2: half-height 69.3 u.
        assertTrue(popsAt(60f, null))
        assertFalse(popsAt(75f, null))
        // Factor 0.5: half-height 40 u.
        assertFalse(popsAt(60f, 0.5f))
        assertTrue(popsAt(35f, 0.5f))
        // Factor 1.0: half-height 80 u.
        assertTrue(popsAt(75f, 1f))
    }

    @Test
    fun listenerPassesConfigRowFactorToLineGem() {
        val base = GameConfig.DEFAULTS
        val config = base.copy(
            board = base.board.copy(lattice = base.board.lattice.copy(rowSpacingFactor = 0.5f)),
            gems = base.gems.map { if (it.type == GemType.LINE) it.copy(effect = GemEffect.PopBand(1f)) else it },
        )
        val peg = Peg(0, 500f, 500f, params.gemRadius, params.restitutionGem, ColliderKind.GEM)
        val gem = Gem(GemType.LINE, peg, RowCol(5, 2))
        val inBand = candy(0, CandyColor.PINK, 100f, 530f) // 30 u < 0.5 · 80 · 1 = 40 u
        val outOfBand = candy(1, CandyColor.PINK, 100f, 560f) // 60 u: inside the old hard-coded 69.3 u
        val state = BoardState(listOf(inBand, outOfBand), listOf(gem), params)
        val listener = listener(config, state)
        listener.onPegContact(listener.world.spawnBall(500f, 460f, 0f, 100f), peg, 100f)
        assertFalse(inBand.active)
        assertTrue(outOfBand.active)
    }

    private fun level() = LevelDef(
        id = 1, code = null, world = 1, isBonus = false, balls = 5, crowns = listOf(1, 2),
        layout = LayoutDef(80f, LayoutPattern.FULL, emptyList(), emptyList(), emptyList()),
        candies = CandyPlacementDef(1L, 0, emptyMap(), emptyList()),
        gems = emptyList(),
        cup = CupDef(0f),
        objectives = listOf(ObjectiveDef(ObjectiveType.SCORE, null, null, null, 1_000_000, null)),
    )

    private fun listener(config: GameConfig, state: BoardState, session: LevelSession = LevelSession(level(), config.scoring)): GamePhysicsListener {
        val p = PhysicsParams.from(config)
        val l = GamePhysicsListener(session, state, ChainTracker(config.chains), CandyCup(180f, 0f), config, p, 80f)
        l.world = PhysicsWorld.create(config, state.gems.map { it.peg }, l)
        return l
    }

    // --- (3) Chain flags --------------------------------------------------------------------------

    @Test
    fun effectPopsExtendOnlyWhenBothFlagsAllowIt() {
        val c = candy(0, CandyColor.GREEN, 0f, 0f)
        fun chainAfterDirectThenEffect(cfg: ChainConfig): Int {
            val t = ChainTracker(cfg)
            t.onCandyPopped(c, direct = true)
            return t.onCandyPopped(c, direct = false).chainLength
        }
        // Defaults: only direct contact builds chains.
        assertEquals(1, chainAfterDirectThenEffect(ChainConfig()))
        assertFalse(ChainTracker(ChainConfig()).onCandyPopped(c, direct = false).extended)
        // effectPopped alone is overridden by onlyDirect = true.
        assertEquals(1, chainAfterDirectThenEffect(ChainConfig(effectPoppedCandiesExtendChain = true)))
        // onlyDirect = false alone does not opt effect pops in.
        assertEquals(1, chainAfterDirectThenEffect(ChainConfig(onlyDirectContactExtendsChain = false)))
        // Both agree: effect pops extend.
        assertEquals(
            2,
            chainAfterDirectThenEffect(
                ChainConfig(onlyDirectContactExtendsChain = false, effectPoppedCandiesExtendChain = true),
            ),
        )
    }

    @Test
    fun countsAsDirectHonoursMagnetFlag() {
        val on = ChainTracker(ChainConfig())
        assertTrue(on.countsAsDirect(touched = true))
        assertTrue(on.countsAsDirect(touched = true, magnetPulled = true))
        assertFalse(on.countsAsDirect(touched = false))
        val off = ChainTracker(ChainConfig(magnetPullCountsAsDirectContact = false))
        assertTrue(off.countsAsDirect(touched = true))
        assertFalse(off.countsAsDirect(touched = true, magnetPulled = true))
    }

    /** Two pink candies touched in one sensor pass; the second was pulled in by the Magnet. */
    private fun magnetScenario(magnetCountsDirect: Boolean): Pair<LevelSession, Int> {
        val base = GameConfig.DEFAULTS
        val config = base.copy(chains = base.chains.copy(magnetPullCountsAsDirectContact = magnetCountsDirect))
        val first = candy(0, CandyColor.PINK, 500f, 600f)
        val pulled = candy(1, CandyColor.PINK, 505f, 600f).apply { magnetPulled = true }
        val state = BoardState(listOf(first, pulled), emptyList(), params)
        val session = LevelSession(level(), config.scoring)
        val l = listener(config, state, session)
        l.world.spawnBall(500f, 600f, 0f, 0f)
        l.afterStep(l.world)
        return session to session.dropSubtotal
    }

    @Test
    fun magnetPulledCandyScoresAndChainsPerFlag() {
        // Default: pulled candy is direct → chain position 2 → 10 + 20.
        assertEquals(30, magnetScenario(magnetCountsDirect = true).second)
        // Flag off: pulled candy is an effect pop → 10 + 10 flat, and no chain advance.
        val (session, subtotal) = magnetScenario(magnetCountsDirect = false)
        assertEquals(20, subtotal)
        assertEquals(mapOf(CandyColor.PINK to 2), session.collectedByColor)
    }
}
