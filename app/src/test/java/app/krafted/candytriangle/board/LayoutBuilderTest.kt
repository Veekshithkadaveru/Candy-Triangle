package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.ClusterDef
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemPlacementDef
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.MovingRowDef
import app.krafted.candytriangle.level.RowCol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** §3.4 clearance and §4.2 gem placement rules of [LayoutBuilder.build]. */
class LayoutBuilderTest {

    private val config = GameConfig.DEFAULTS
    private val params = PhysicsParams.from(config)
    private val board = config.board

    /** 2·ballRadius + ballClearancePadding = 36 u. */
    private val minBallGap = 2f * params.ballRadius + config.clearance.ballClearancePadding
    private val minWallGap = config.clearance.minPegEdgeToWall
    private val eps = 1e-3f

    private fun level(
        spacing: Float = 80f,
        world: Int = 1,
        pattern: LayoutPattern = LayoutPattern.FULL,
        holes: List<RowCol> = emptyList(),
        clusters: List<ClusterDef> = emptyList(),
        movingRows: List<MovingRowDef> = emptyList(),
        gems: List<GemPlacementDef> = emptyList(),
    ) = LevelDef(
        id = 1,
        code = null,
        world = world,
        isBonus = false,
        balls = 10,
        crowns = listOf(2, 4),
        layout = LayoutDef(spacing, pattern, holes, clusters, movingRows),
        candies = CandyPlacementDef(1L, 0, emptyMap(), emptyList()),
        gems = gems,
        cup = CupDef(260f),
        objectives = emptyList(),
    )

    private fun build(level: LevelDef) = LayoutBuilder.build(level, config, params)

    private fun gap(a: Peg, bx: Float, by: Float, br: Float): Float {
        val dx = a.x - bx
        val dy = a.y - by
        return sqrt(dx * dx + dy * dy) - a.radius - br
    }

    @Test
    fun gemOnHoleMaterialises() {
        val rc = RowCol(6, 3)
        val layout = build(level(holes = listOf(rc), gems = listOf(GemPlacementDef(GemType.BLAST, 6, 3))))
        assertTrue(layout.rejectedGems.isEmpty())
        assertEquals(1, layout.gems.size)
        val gem = layout.gems.single()
        assertEquals(rc, gem.rc)
        assertEquals(ColliderKind.GEM, gem.peg.kind)
        assertEquals(params.gemRadius, gem.peg.radius, 0f)
        assertEquals(LatticeGeometry.nodeX(rc, 80f, board), gem.x, 0f)
        assertTrue("gem body is one of the world's pegs", layout.pegs.any { it === gem.peg })
    }

    @Test
    fun gemOutsideEveryClusterMaterialises() {
        val layout = build(
            level(
                pattern = LayoutPattern.CLUSTERS,
                clusters = listOf(ClusterDef(row = 2, col = 0, rows = 2, cols = 2, nodes = emptyList())),
                gems = listOf(GemPlacementDef(GemType.SWEET, 9, 4)),
            ),
        )
        assertTrue(layout.rejectedGems.isEmpty())
        assertEquals(RowCol(9, 4), layout.gems.single().rc)
    }

    @Test
    fun gemNearWallRejected() {
        val d = 80f
        // An in-bounds node whose gem edge would sit inside the 36 u wall margin.
        var node: RowCol? = null
        search@ for (r in 1..LatticeGeometry.lastNodeRow(d, board)) {
            for (c in 0..r) {
                val rc = RowCol(r, c)
                val clearance = LatticeGeometry.wallClearance(
                    LatticeGeometry.nodeX(rc, d, board), LatticeGeometry.nodeY(rc, d, board), board,
                )
                if (clearance > params.gemRadius && clearance - params.gemRadius < minWallGap) {
                    node = rc
                    break@search
                }
            }
        }
        assertNotNull("fixture needs a node near the wall", node)
        val def = GemPlacementDef(GemType.BLAST, node!!.row, node.col)
        val layout = build(level(spacing = d, gems = listOf(def)))
        assertEquals(listOf(def), layout.rejectedGems)
        assertTrue(layout.gems.isEmpty())
        assertTrue(layout.pegs.none { it.kind == ColliderKind.GEM })
    }

    @Test
    fun gemOnMovingRowRejected() {
        val def = GemPlacementDef(GemType.BLAST, 6, 3)
        val layout = build(
            level(world = 3, movingRows = listOf(MovingRowDef(6, 40f, 3f)), gems = listOf(def)),
        )
        assertEquals(listOf(def), layout.rejectedGems)
        assertTrue(layout.gems.isEmpty())
    }

    @Test
    fun gemOutsideLatticeRejected() {
        val last = LatticeGeometry.lastNodeRow(80f, board)
        val bad = listOf(
            GemPlacementDef(GemType.SWEET, last + 1, 2),
            GemPlacementDef(GemType.SWEET, 4, 5),
            GemPlacementDef(GemType.SWEET, 4, -1),
            GemPlacementDef(GemType.SWEET, -1, 0),
        )
        val layout = build(level(gems = bad))
        assertEquals(bad, layout.rejectedGems)
        assertTrue(layout.gems.isEmpty())
    }

    @Test
    fun duplicateGemKeepsFirst() {
        val first = GemPlacementDef(GemType.BLAST, 6, 3)
        val second = GemPlacementDef(GemType.LINE, 6, 3)
        val layout = build(level(gems = listOf(first, second)))
        assertEquals(GemType.BLAST, layout.gems.single().type)
        assertEquals(listOf(second), layout.rejectedGems)
    }

    @Test
    fun gemTooCloseToAnotherGemRejected() {
        // Lattice neighbours at d = 80: 80 - 60 = 20 u gap < 36.
        val first = GemPlacementDef(GemType.BLAST, 6, 3)
        val neighbour = GemPlacementDef(GemType.SWEET, 6, 4)
        val farEnough = GemPlacementDef(GemType.LINE, 8, 4) // two rows down: 2h - 60 = 78.6 u
        val layout = build(level(gems = listOf(first, neighbour, farEnough)))
        assertEquals(listOf(neighbour), layout.rejectedGems)
        assertEquals(listOf(RowCol(6, 3), RowCol(8, 4)), layout.gems.map { it.rc })
    }

    @Test
    fun gemRemovesAdjacentPegsBelow73() {
        val d = 66f
        val layout = build(level(spacing = d, gems = listOf(GemPlacementDef(GemType.BLAST, 7, 3))))
        val gem = layout.gems.single()
        val neighbours = listOf(RowCol(6, 2), RowCol(6, 3), RowCol(7, 2), RowCol(7, 4), RowCol(8, 3), RowCol(8, 4))
        for (rc in neighbours) {
            val x = LatticeGeometry.nodeX(rc, d, board)
            val y = LatticeGeometry.nodeY(rc, d, board)
            assertTrue("neighbour $rc removed", layout.pegs.none { it.x == x && it.y == y })
        }
        for (p in layout.pegs) {
            if (p === gem.peg) continue
            assertTrue(gap(p, gem.x, gem.y, params.gemRadius) >= minBallGap - eps)
        }
    }

    @Test
    fun gemKeepsAdjacentPegsAtWideSpacing() {
        // d = 90: 90 - 30 - 7 = 53 u >= 36, so no neighbour is removed.
        val d = 90f
        val withGem = build(level(spacing = d, gems = listOf(GemPlacementDef(GemType.BLAST, 7, 3))))
        val without = build(level(spacing = d))
        assertEquals(without.pegs.size, withGem.pegs.size)
    }

    @Test
    fun movingRowSwingStaysClearOfGemsAndWalls() {
        for (d in listOf(66f, 72f, 80f, 90f)) {
            val lvl = level(
                spacing = d,
                world = 3,
                movingRows = listOf(
                    MovingRowDef(4, 60f, 3f),
                    MovingRowDef(6, -90f, 2.5f),
                    MovingRowDef(9, 45f, 4f),
                ),
                gems = listOf(GemPlacementDef(GemType.BLAST, 5, 2), GemPlacementDef(GemType.MAGNET, 8, 5)),
            )
            val layout = build(lvl)
            assertTrue("d=$d: gems placed", layout.rejectedGems.isEmpty())
            assertTrue("d=$d: moving pegs exist", layout.pegs.any { it.motion != null })
            for (p in layout.pegs) {
                val amp = p.motion?.amplitude ?: 0f
                if (p.motion != null) assertEquals(p.x, p.motion!!.baseX, 0f)
                // Sample the swing densely, extremes included.
                for (s in 0..200) {
                    val x = p.x - amp + 2f * amp * s / 200f
                    val wall = LatticeGeometry.wallClearance(x, p.y, board) - p.radius
                    assertTrue("d=$d $p wall gap $wall", wall >= minWallGap - eps)
                    for (g in layout.gems) {
                        if (g.peg === p) continue
                        val dx = x - g.x
                        val dy = p.y - g.y
                        val gap = sqrt(dx * dx + dy * dy) - p.radius - params.gemRadius
                        assertTrue("d=$d $p to gem ${g.rc}: $gap", gap >= minBallGap - eps)
                    }
                }
            }
        }
    }

    @Test
    fun movingRowsIgnoredInEarlyWorlds() {
        val layout = build(level(world = 1, movingRows = listOf(MovingRowDef(4, 60f, 3f))))
        assertTrue(layout.pegs.none { it.motion != null })
    }

    @Test
    fun buildIsDeterministic() {
        val lvl = level(
            spacing = 72f,
            world = 4,
            holes = listOf(RowCol(3, 1), RowCol(10, 5)),
            movingRows = listOf(MovingRowDef(7, 50f, 3f)),
            gems = listOf(
                GemPlacementDef(GemType.SPLIT, 10, 5),
                GemPlacementDef(GemType.BLAST, 4, 2),
                GemPlacementDef(GemType.SWEET, 12, 6),
            ),
        )
        fun snapshot(layout: BoardLayout) = layout.pegs.map { listOf(it.id, it.x, it.y, it.radius, it.kind, it.motion) }
        val a = build(lvl)
        val b = build(lvl)
        assertEquals(snapshot(a), snapshot(b))
        assertEquals(a.gems.map { it.rc to it.peg.id }, b.gems.map { it.rc to it.peg.id })
        assertTrue("fresh Peg instances per build", a.pegs.indices.all { a.pegs[it] !== b.pegs[it] })
    }

    @Test
    fun idsAreSequentialInRowMajorOrder() {
        val layout = build(
            level(gems = listOf(GemPlacementDef(GemType.BLAST, 9, 4), GemPlacementDef(GemType.SWEET, 3, 1))),
        )
        assertEquals(layout.pegs.indices.toList(), layout.pegs.map { it.id })
        for (i in 1 until layout.pegs.size) {
            val prev = layout.pegs[i - 1]
            val cur = layout.pegs[i]
            assertTrue("row-major: $prev before $cur", prev.y < cur.y || (prev.y == cur.y && prev.x < cur.x))
        }
        // Gems are listed in node order too, not authored order.
        assertEquals(listOf(RowCol(3, 1), RowCol(9, 4)), layout.gems.map { it.rc })
    }

    @Test
    fun pegSpacingAtLeastD() {
        for (d in listOf(66f, 75f, 90f)) {
            for (pattern in LayoutPattern.entries) {
                val layout = build(
                    level(
                        spacing = d,
                        pattern = pattern,
                        clusters = listOf(ClusterDef(3, 0, 4, 3, emptyList())),
                        gems = listOf(GemPlacementDef(GemType.BLAST, 6, 3)),
                    ),
                )
                val pegs = layout.pegs
                for (i in pegs.indices) for (j in i + 1 until pegs.size) {
                    val dx = pegs[i].x - pegs[j].x
                    val dy = pegs[i].y - pegs[j].y
                    val dist = sqrt(dx * dx + dy * dy)
                    assertTrue("d=$d $pattern ${pegs[i]} ${pegs[j]}: $dist", dist >= d - eps)
                }
            }
        }
    }

    @Test
    fun everyPegClearsTheWalls() {
        for (d in listOf(66f, 80f, 90f)) {
            for (p in build(level(spacing = d)).pegs) {
                assertTrue(LatticeGeometry.wallClearance(p.x, p.y, board) - p.radius >= minWallGap - eps)
            }
        }
    }
}
