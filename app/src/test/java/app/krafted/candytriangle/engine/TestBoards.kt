package app.krafted.candytriangle.engine

import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.WallLine
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The engine suites' boards: §3.1's triangle from `GameConfig.DEFAULTS`, walls from
 * [WallSegment.boardWalls], and a peg lattice laid out the way §3.1 and §3.4 describe it.
 *
 * A test-only stand-in for B2's `LayoutBuilder`, which does not exist yet — just enough of it to put
 * realistic, rule-abiding boards under the engine:
 *
 * - **Lattice (§3.1):** equilateral with spacing `d`. Rows run `d * rowSpacingFactor` (d sqrt(3)/2)
 *   apart from `latticeBand.yTop` down to `latticeBand.yBottom`; odd rows are offset by `d / 2`, so
 *   every even row has a peg exactly on the axis x = 500 — row 0's sits directly under the spawn.
 * - **Wall clipping (§3.4 "ball between peg and wall"):** a node is kept only if its peg's edge
 *   stays at least `clearance.minPegEdgeToWall` (36 u) from both walls — over its whole swing, for
 *   a peg in a moving row.
 * - **Gems (§3.4 "ball between gem and peg"):** a gem body (`gemRadius`, `restitutionGem`,
 *   [ColliderKind.GEM]) replaces the peg at its node, and every lattice peg leaving less than
 *   `2 * ballRadius + ballClearancePadding` (36 u) of edge-to-edge room around it is removed — the
 *   six neighbours at `d` < 73, nothing at `d` >= 73.
 * - **Moving rows (§3.3):** every peg in the row oscillates with the config's default amplitude and
 *   period, `PegMotion(baseX, 40, 3 s)`.
 *
 * Pegs get ids in row-major order — by row, then left to right — after removals. Every call builds
 * fresh [Peg] instances: a moving (or deactivated) peg is mutable state, so two worlds must never
 * share one.
 */
object TestBoards {

    val CONFIG: GameConfig = GameConfig.DEFAULTS

    /** The engine parameters for [CONFIG] — the shipped §3.2 values. */
    val PARAMS: PhysicsParams = PhysicsParams.from(CONFIG)

    /** A lattice node: [row] counted down from the top of the lattice band, [col] from the axis. */
    data class Node(val row: Int, val col: Int)

    /** A board recipe; see [TestBoards]. */
    class Spec(
        val name: String,
        val spacing: Float,
        val gems: List<Node> = emptyList(),
        val movingRows: Set<Int> = emptySet(),
    ) {
        override fun toString(): String = name
    }

    /** World 1's most forgiving lattice, all static. */
    val D90 = Spec("d=90 static", 90f)

    /** World 4's densest lattice, with four gems (two mirrored) and their neighbours cleared. */
    val D66_GEMS = Spec(
        "d=66 gems",
        66f,
        gems = listOf(Node(4, 0), Node(8, -2), Node(8, 2), Node(13, 0)),
    )

    /** A World 3 lattice with two oscillating rows. */
    val D78_MOVING = Spec("d=78 moving rows", 78f, movingRows = setOf(5, 10))

    val ALL: List<Spec> = listOf(D90, D66_GEMS, D78_MOVING)

    /** The configured §3.1 walls as plain lines, left then right. */
    val WALL_LINES: List<WallLine> = listOf(CONFIG.board.walls.left, CONFIG.board.walls.right)

    /** The engine's walls for [CONFIG], left then right. */
    fun walls(params: PhysicsParams = PARAMS): List<WallSegment> =
        WallSegment.boardWalls(CONFIG.board, params.restitutionWall)

    /** A fresh world over [spec]'s board. */
    fun world(
        spec: Spec,
        params: PhysicsParams = PARAMS,
        listener: PhysicsListener = PhysicsListener.NONE,
    ): PhysicsWorld = PhysicsWorld(params, walls(params), pegs(spec, params), listener)

    /** A fresh world with the two walls and no pegs at all. */
    fun emptyWorld(
        params: PhysicsParams = PARAMS,
        listener: PhysicsListener = PhysicsListener.NONE,
    ): PhysicsWorld = PhysicsWorld(params, walls(params), emptyList(), listener)

    /** Fresh colliders for [spec]'s board, ids in row-major order; see [TestBoards]. */
    fun pegs(spec: Spec, params: PhysicsParams = PARAMS): List<Peg> {
        require(spec.gems.isEmpty() || spec.movingRows.isEmpty()) {
            "${spec.name}: gems next to moving rows would need swing-aware gem clearance"
        }
        val board = CONFIG.board
        val moving = CONFIG.physics.movingPegs
        val d = spec.spacing.toDouble()
        val axis = board.centerAxisX.toDouble()
        val rowHeight = d * board.lattice.rowSpacingFactor
        val maxCol = ceil(board.width / 2.0 / d).toInt() + 1

        val sites = ArrayList<Site>()
        var row = 0
        while (true) {
            val y = board.latticeBand.yTop + row * rowHeight
            if (y > board.latticeBand.yBottom) break
            val offset = if (row % 2 == 0) 0.0 else d / 2.0
            val swing = if (row in spec.movingRows) moving.defaultAmplitude.toDouble() else 0.0
            for (col in -maxCol..maxCol) {
                val x = axis + offset + col * d
                if (clearsWalls(x, y, params.pegRadius.toDouble(), swing)) {
                    sites.add(Site(Node(row, col), x, y))
                }
            }
            row++
        }

        val gemSites = spec.gems.map { node ->
            sites.firstOrNull { it.node == node }
                ?: error("${spec.name}: gem node $node was clipped or is off the lattice")
        }
        val room = 2.0 * params.ballRadius + CONFIG.clearance.ballClearancePadding
        for ((i, gem) in gemSites.withIndex()) {
            require(clearsWalls(gem.x, gem.y, params.gemRadius.toDouble(), 0.0)) {
                "${spec.name}: gem at ${gem.node} is too close to a wall"
            }
            for (other in gemSites.drop(i + 1)) {
                require(distance(gem, other) - 2.0 * params.gemRadius >= room) {
                    "${spec.name}: gems at ${gem.node} and ${other.node} crowd each other"
                }
            }
        }
        val kept = sites.filter { site ->
            site in gemSites || gemSites.none { gem ->
                distance(gem, site) - params.gemRadius - params.pegRadius < room
            }
        }

        return kept.mapIndexed { id, site ->
            val x = site.x.toFloat()
            val y = site.y.toFloat()
            when {
                site in gemSites ->
                    Peg(id, x, y, params.gemRadius, params.restitutionGem, ColliderKind.GEM)
                site.node.row in spec.movingRows -> Peg(
                    id,
                    x,
                    y,
                    params.pegRadius,
                    params.restitutionPeg,
                    ColliderKind.PEG,
                    PegMotion(x, moving.defaultAmplitude, moving.defaultPeriodSeconds),
                )
                else -> Peg(id, x, y, params.pegRadius, params.restitutionPeg)
            }
        }
    }

    /**
     * Signed perpendicular distance, u, from ([x], [y]) to [line], positive on the board's interior
     * side. Double precision straight from the config geometry — deliberately independent of
     * [WallSegment], so the suites can check the engine's walls against it.
     */
    fun interiorDistance(line: WallLine, x: Double, y: Double): Double {
        val wx = line.x2.toDouble() - line.x1
        val wy = line.y2.toDouble() - line.y1
        val length = sqrt(wx * wx + wy * wy)
        val side = (wx * (y - line.y1) - wy * (x - line.x1)) / length
        val interiorSide = wx * (CENTROID_Y - line.y1) - wy * (CENTROID_X - line.x1)
        return if (interiorSide > 0.0) side else -side
    }

    /** The fastest any peg in [world] moves, u/s: `A * 2 PI / T` over its moving pegs, else 0. */
    fun maxPegSpeed(world: PhysicsWorld): Float {
        var fastest = 0.0
        for (peg in world.pegs) {
            val m = peg.motion ?: continue
            fastest = maxOf(fastest, m.amplitude * 2.0 * PI / m.periodSeconds)
        }
        return fastest.toFloat()
    }

    private data class Site(val node: Node, val x: Double, val y: Double)

    /** The triangle's centroid, (500, 833.3): a point certainly inside both walls. */
    private val CENTROID_X: Double =
        (WALL_LINES[0].x1.toDouble() + WALL_LINES[0].x2 + WALL_LINES[1].x2) / 3.0
    private val CENTROID_Y: Double =
        (WALL_LINES[0].y1.toDouble() + WALL_LINES[0].y2 + WALL_LINES[1].y2) / 3.0

    /**
     * Whether a collider of [radius] at ([x], [y]), swinging [swing] u either way, keeps its edge
     * `minPegEdgeToWall` from both walls. The distance is linear in x, so the swing's two extremes
     * bound it.
     */
    private fun clearsWalls(x: Double, y: Double, radius: Double, swing: Double): Boolean {
        val required = radius + CONFIG.clearance.minPegEdgeToWall
        for (line in WALL_LINES) {
            if (interiorDistance(line, x - swing, y) < required) return false
            if (interiorDistance(line, x + swing, y) < required) return false
        }
        return true
    }

    private fun distance(a: Site, b: Site): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
