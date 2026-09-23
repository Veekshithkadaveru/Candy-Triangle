package app.krafted.candytriangle.board

import android.util.Log
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PegMotion
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemPlacementDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.MovingRowDef
import app.krafted.candytriangle.level.RowCol
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Builds a level's solid colliders — lattice pegs and gem bodies — from its [LevelDef] (§3.1, §3.4,
 * §4.2, §8 `LayoutBuilder.kt`).
 *
 * ## Determinism
 *
 * Nodes are visited in **row-major order** (`row` ascending, then `col` ascending), and [Peg.id]s
 * are handed out sequentially in that order, gems and pegs alike. No hash-ordered collection is ever
 * iterated, so two builds of the same level produce identical peg lists — B1's determinism contract
 * requires pegs to reach `PhysicsWorld` in a fixed order.
 *
 * ## Gems (§4.2 "Gems replace pegs at lattice nodes")
 *
 * An authored gem always materialises at its node, whether or not the pattern would put a peg
 * there (a hole, a node outside every cluster, an odd `SPARSE` row). It is **rejected** — not
 * placed, listed in [BoardLayout.rejectedGems] and logged — when it:
 * 1. lies outside the lattice (`row` outside `0..lastNodeRow`, or `col` outside `0..row`);
 * 2. repeats a node an earlier gem already holds (the first authored gem wins);
 * 3. sits on an effective moving row (a gem must be a static body);
 * 4. has its edge closer than `clearance.minPegEdgeToWall` (36 u) to a wall, measured with
 *    `gemRadius`;
 * 5. leaves a gap narrower than `2·ballRadius + ballClearancePadding` (36 u) to an earlier
 *    accepted gem (authored order decides which one stays).
 *
 * ## Pegs (§3.4)
 *
 * A peg is kept only when, over its whole swing (at rest, `± |amplitude|` for a moving row):
 * - its edge stays at least `clearance.minPegEdgeToWall` from both walls ("Ball between Peg &
 *   Wall"); and
 * - its surface gap to every placed gem stays at least `2·ballRadius + ballClearancePadding`.
 *
 * The second rule generalises §3.4's "Ball between Gem & Peg" row: at `d < 73` it removes exactly
 * the six lattice neighbours of a static gem, as the table asks, but it holds for any `d` and also
 * catches a moving peg that swings into a gem from farther along its row.
 *
 * Peg-to-peg clearance needs no enforcement: `d ≥ 66` (clamped by `LevelDef`) already satisfies
 * §3.4's first two rows, and a moving row moves as a rigid body, so its pegs keep their spacing.
 */
object LayoutBuilder {

    private const val TAG = "LayoutBuilder"

    /** Pegs only; kept for existing callers. Prefer [build]. */
    fun buildPegs(
        level: LevelDef,
        config: GameConfig,
        params: PhysicsParams,
    ): List<Peg> = build(level, config, params).pegs

    /**
     * Builds the lattice of pegs and gem bodies for [level]. Every call creates fresh [Peg]
     * instances (B1 note 14: a world owns its pegs). See the class docs for the rules.
     */
    fun build(
        level: LevelDef,
        config: GameConfig,
        params: PhysicsParams,
    ): BoardLayout {
        val board = config.board
        val d = level.layout.spacing
        val lastRow = LatticeGeometry.lastNodeRow(d, board)
        val minWallGap = config.clearance.minPegEdgeToWall
        val minBallGap = 2f * params.ballRadius + config.clearance.ballClearancePadding

        // World gate: moving rows only exist in worlds that allow them (§6.1, World 3+). A level in
        // no listed world (Sweet Rooms) keeps whatever it authors.
        val movingAllowed = config.worlds.firstOrNull { it.index == level.world }?.movingPegRows != false
        val movingRows: Map<Int, MovingRowDef> =
            if (movingAllowed) level.layout.movingRows.associateBy { it.row } else emptyMap()

        // --- Gems, in authored order ------------------------------------------------------------
        val acceptedGems = ArrayList<GemSite>()
        val rejected = ArrayList<GemPlacementDef>()
        for (def in level.gems) {
            val rc = RowCol(def.row, def.col)
            val reason: String? = when {
                def.row !in 0..lastRow || def.col !in 0..def.row -> "outside the lattice (last row $lastRow)"
                acceptedGems.any { it.rc == rc } -> "duplicate of an earlier gem at the same node"
                rejected.any { it.row == def.row && it.col == def.col } -> "duplicate of an earlier gem at the same node"
                def.row in movingRows -> "on moving row ${def.row}"
                else -> {
                    val x = LatticeGeometry.nodeX(rc, d, board)
                    val y = LatticeGeometry.nodeY(rc, d, board)
                    val wallGap = LatticeGeometry.wallClearance(x, y, board) - params.gemRadius
                    val clash = acceptedGems.firstOrNull { other ->
                        surfaceGap(x, y, params.gemRadius, other.x, other.y, params.gemRadius) < minBallGap
                    }
                    when {
                        wallGap < minWallGap -> "edge ${"%.1f".format(wallGap)} u from a wall (< $minWallGap)"
                        clash != null -> "within $minBallGap u of the gem at ${clash.rc}"
                        else -> {
                            acceptedGems += GemSite(def, rc, x, y)
                            null
                        }
                    }
                }
            }
            if (reason != null) {
                Log.w(TAG, "Level ${level.id}: ${def.type} gem at $rc rejected: $reason")
                rejected += def
            }
        }
        val gemAt = HashMap<RowCol, GemSite>(acceptedGems.size * 2)
        for (g in acceptedGems) gemAt[g.rc] = g

        // --- Which nodes the pattern fills --------------------------------------------------------
        val pattern = level.layout.pattern
        val clusterNodes: Set<RowCol> =
            if (pattern == LayoutPattern.CLUSTERS) level.layout.clusters.flatMap { it.resolveNodes() }.toHashSet()
            else emptySet()
        val holes: Set<RowCol> = level.layout.holes.toHashSet()

        fun patternHasPeg(rc: RowCol): Boolean = when (pattern) {
            LayoutPattern.FULL -> rc !in holes
            LayoutPattern.SPARSE -> rc.row % 2 == 0 && rc !in holes
            LayoutPattern.CLUSTERS -> rc in clusterNodes
        }

        // --- Row-major traversal: gems and pegs, ids in visit order -------------------------------
        val pegs = ArrayList<Peg>()
        val gems = ArrayList<Gem>()
        var nextId = 0
        for (r in 0..lastRow) {
            val moving = movingRows[r]
            val swing = if (moving != null) abs(moving.amplitude) else 0f
            for (c in 0..r) {
                val rc = RowCol(r, c)
                val x = LatticeGeometry.nodeX(rc, d, board)
                val y = LatticeGeometry.nodeY(rc, d, board)

                val site = gemAt[rc]
                if (site != null) {
                    val peg = Peg(
                        id = nextId++,
                        x = x,
                        y = y,
                        radius = params.gemRadius,
                        restitution = params.restitutionGem,
                        kind = ColliderKind.GEM,
                    )
                    pegs += peg
                    gems += Gem(site.def.type, peg, rc)
                    continue
                }

                if (!patternHasPeg(rc)) continue

                // Ball between Peg & Wall, over the whole swing. wallClearance is linear in x along
                // each wall, so the two extremes bound every position in between.
                val wallGap = minOf(
                    LatticeGeometry.wallClearance(x - swing, y, board),
                    LatticeGeometry.wallClearance(x + swing, y, board),
                ) - params.pegRadius
                if (wallGap < minWallGap) continue

                // Ball between Gem & Peg, over the whole swing.
                var blocked = false
                for (g in acceptedGems) {
                    if (sweptSurfaceGap(x, swing, y, params.pegRadius, g.x, g.y, params.gemRadius) < minBallGap) {
                        blocked = true
                        break
                    }
                }
                if (blocked) continue

                pegs += Peg(
                    id = nextId++,
                    x = x,
                    y = y,
                    radius = params.pegRadius,
                    restitution = params.restitutionPeg,
                    kind = ColliderKind.PEG,
                    motion = moving?.let { PegMotion(x, it.amplitude, it.periodSeconds) },
                )
            }
        }

        return BoardLayout(pegs, gems, rejected)
    }

    /** Edge-to-edge distance between two circles; negative when they overlap. */
    private fun surfaceGap(ax: Float, ay: Float, ar: Float, bx: Float, by: Float, br: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy) - ar - br
    }

    /**
     * Smallest [surfaceGap] between a circle whose centre sweeps the horizontal segment
     * `[x - swing, x + swing]` at height [y] and a static circle at ([bx], [by]).
     */
    private fun sweptSurfaceGap(x: Float, swing: Float, y: Float, r: Float, bx: Float, by: Float, br: Float): Float {
        val nearestX = bx.coerceIn(x - swing, x + swing)
        return surfaceGap(nearestX, y, r, bx, by, br)
    }

    private class GemSite(val def: GemPlacementDef, val rc: RowCol, val x: Float, val y: Float)
}

/**
 * Output of [LayoutBuilder.build]: every solid collider for one level.
 *
 * @property pegs all solids handed to `PhysicsWorld`, gems included (a gem is a `Peg` of kind GEM),
 *   in row-major node order with ids `0, 1, 2, ...` in that same order.
 * @property gems the gameplay view of the GEM-kind entries of [pegs], sharing the same `Peg`, in
 *   the same row-major order.
 * @property rejectedGems authored gems that could not be placed (§3.4 clearance, bad node), in
 *   authored order. C2 asserts this is empty for every shipped level.
 */
data class BoardLayout(
    val pegs: List<Peg>,
    val gems: List<Gem>,
    val rejectedGems: List<GemPlacementDef>,
)
