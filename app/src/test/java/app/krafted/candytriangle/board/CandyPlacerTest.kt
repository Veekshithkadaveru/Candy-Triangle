package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.FixedCandy
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.RowCol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CandyPlacerTest {

    private val config = GameConfig.DEFAULTS
    private val board = config.board

    private fun level(
        spacing: Float = 80f,
        seed: Long = 42L,
        count: Int = 30,
        weights: Map<CandyColor, Int> = CandyColor.entries.associateWith { 1 },
        fixed: List<FixedCandy> = emptyList(),
    ) = LevelDef(
        id = 1, code = null, world = 1, isBonus = false, balls = 10, crowns = listOf(2, 4),
        layout = LayoutDef(spacing, LayoutPattern.FULL, emptyList(), emptyList(), emptyList()),
        candies = CandyPlacementDef(seed, count, weights, fixed),
        gems = emptyList(), cup = CupDef(260f), objectives = emptyList(),
    )

    @Test
    fun gapCentresAreEquidistantFromTheirThreeNodes() {
        val d = 72f
        for (r in 0 until LatticeGeometry.gapRowCount(d, board)) {
            for (c in 0..(2 * r)) {
                val gap = RowCol(r, c)
                val p = LatticeGeometry.gapCenter(gap, d, board)
                val k = c / 2
                val corners = if (c % 2 == 0) {
                    listOf(RowCol(r, k), RowCol(r + 1, k), RowCol(r + 1, k + 1))
                } else {
                    listOf(RowCol(r, k), RowCol(r, k + 1), RowCol(r + 1, k + 1))
                }
                for (n in corners) {
                    val dx = LatticeGeometry.nodeX(n, d, board) - p.x
                    val dy = LatticeGeometry.nodeY(n, d, board) - p.y
                    assertEquals("gap $gap to $n", d / sqrt(3f), sqrt(dx * dx + dy * dy), 1e-2f)
                }
            }
        }
    }

    @Test
    fun wallClearanceIsZeroOnTheWallsAndPositiveOnTheAxis() {
        assertEquals(0f, LatticeGeometry.wallClearance(250f, 625f, board), 1e-3f)
        assertEquals(0f, LatticeGeometry.wallClearance(750f, 625f, board), 1e-3f)
        assertTrue(LatticeGeometry.wallClearance(500f, 625f, board) > 200f)
        assertTrue(LatticeGeometry.wallClearance(100f, 300f, board) < 0f)
    }

    @Test
    fun sameSeedGivesIdenticalPlacement() {
        val a = CandyPlacer.place(level(), emptyList(), config)
        val b = CandyPlacer.place(level(), emptyList(), config)
        assertEquals(30, a.size)
        assertEquals(a.map { Triple(it.color, it.x, it.y) }, b.map { Triple(it.color, it.x, it.y) })
        val c = CandyPlacer.place(level(seed = 43L), emptyList(), config)
        assertFalse(a.map { it.rc } == c.map { it.rc })
    }

    @Test
    fun candiesSitInsideWallsAndNeverShareAGap() {
        val candies = CandyPlacer.place(level(spacing = 66f, count = 500), emptyList(), config)
        assertEquals(candies.size, candies.map { it.rc }.toSet().size)
        for (c in candies) {
            assertTrue(LatticeGeometry.wallClearance(c.x, c.y, board) >= config.physics.candyRadius)
        }
        assertEquals(candies.indices.toList(), candies.map { it.id })
    }

    @Test
    fun fixedCandiesComeFirstAndBadOnesAreSkipped() {
        val fixed = listOf(
            FixedCandy(CandyColor.PINK, 5, 4),
            FixedCandy(CandyColor.BLUE, 5, 4), // duplicate slot
            FixedCandy(CandyColor.BLUE, 3, 99), // out of range
        )
        val candies = CandyPlacer.place(level(count = 5, fixed = fixed), emptyList(), config)
        assertEquals(6, candies.size)
        assertEquals(CandyColor.PINK, candies[0].color)
        assertEquals(RowCol(5, 4), candies[0].rc)
    }

    @Test
    fun zeroWeightColoursAreNeverPicked() {
        val candies = CandyPlacer.place(
            level(count = 80, weights = mapOf(CandyColor.PINK to 1)),
            emptyList(),
            config,
        )
        assertTrue(candies.all { it.color == CandyColor.PINK })
    }

    @Test
    fun noCandyOverlapsAGem() {
        val d = 80f
        val node = RowCol(6, 3)
        val peg = Peg(
            0, LatticeGeometry.nodeX(node, d, board), LatticeGeometry.nodeY(node, d, board),
            config.physics.gemRadius, 0.65f, ColliderKind.GEM,
        )
        val gem = Gem(GemType.BLAST, peg, node)
        val candies = CandyPlacer.place(level(spacing = d, count = 500), listOf(gem), config)
        val reach = config.physics.gemRadius + config.physics.candyRadius
        for (c in candies) {
            val dx = c.x - gem.x
            val dy = c.y - gem.y
            assertTrue(dx * dx + dy * dy >= reach * reach)
        }
    }
}
