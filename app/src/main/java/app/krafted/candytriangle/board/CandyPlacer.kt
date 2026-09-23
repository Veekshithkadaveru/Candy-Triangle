package app.krafted.candytriangle.board

import android.util.Log
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.RowCol
import java.util.SplittableRandom

/**
 * Seeded candy placement at lattice gap centres (§4.1, §6.3 `candies`).
 *
 * Deterministic by construction: gaps are enumerated in row-major order and the RNG is
 * `java.util.SplittableRandom`, whose sequence for a given seed is specified by the JDK and is the
 * same on the JVM and ART (unlike `kotlin.random`, whose algorithm is an implementation detail).
 *
 * Order of placement:
 * 1. `fixed` candies, in authored order. An out-of-range, ineligible or duplicate slot is skipped
 *    with a warning, never thrown (§13, zero-crash).
 * 2. `count` seeded candies. Each draw picks a free eligible gap uniformly, then a colour by
 *    `weights`. `count` is capped at the number of free eligible gaps.
 *
 * A gap is eligible when the whole candy lies inside the walls and it does not overlap a gem.
 */
object CandyPlacer {

    private const val TAG = "CandyPlacer"

    fun place(level: LevelDef, gems: List<Gem>, config: GameConfig): List<Candy> {
        val board = config.board
        val d = level.layout.spacing
        val candyRadius = config.physics.candyRadius
        val gemReach = config.physics.gemRadius + candyRadius

        fun eligible(gap: RowCol): Boolean {
            if (!LatticeGeometry.isValidGap(gap, d, board)) return false
            val p = LatticeGeometry.gapCenter(gap, d, board)
            if (LatticeGeometry.wallClearance(p.x, p.y, board) < candyRadius) return false
            for (gem in gems) {
                val dx = gem.x - p.x
                val dy = gem.y - p.y
                if (dx * dx + dy * dy < gemReach * gemReach) return false
            }
            return true
        }

        val out = ArrayList<Candy>()
        val taken = HashSet<RowCol>()

        for (fixed in level.candies.fixed) {
            val gap = RowCol(fixed.row, fixed.col)
            if (gap in taken || !eligible(gap)) {
                Log.w(TAG, "Level ${level.id}: fixed candy at gap $gap is unusable; skipped")
                continue
            }
            taken += gap
            val p = LatticeGeometry.gapCenter(gap, d, board)
            out += Candy(out.size, fixed.color, p.x, p.y, gap)
        }

        val free = ArrayList<RowCol>()
        for (r in 0 until LatticeGeometry.gapRowCount(d, board)) {
            for (c in 0..(2 * r)) {
                val gap = RowCol(r, c)
                if (gap !in taken && eligible(gap)) free += gap
            }
        }

        val weights = CandyColor.entries.map { (level.candies.weights[it] ?: 0).coerceAtLeast(0) }
        val totalWeight = weights.sum()
        val wanted = level.candies.count.coerceAtLeast(0)
        if (wanted > free.size) {
            Log.w(TAG, "Level ${level.id}: wants $wanted seeded candies, only ${free.size} free gaps")
        }
        if (totalWeight <= 0) return out

        val rng = SplittableRandom(level.candies.seed)
        repeat(minOf(wanted, free.size)) {
            val i = rng.nextInt(free.size)
            val gap = free[i]
            // Swap-remove: O(1) and still fully deterministic.
            free[i] = free[free.size - 1]
            free.removeAt(free.size - 1)

            var roll = rng.nextInt(totalWeight)
            var color = CandyColor.entries.last()
            for ((idx, w) in weights.withIndex()) {
                if (roll < w) {
                    color = CandyColor.entries[idx]
                    break
                }
                roll -= w
            }
            val p = LatticeGeometry.gapCenter(gap, d, board)
            out += Candy(out.size, color, p.x, p.y, gap)
        }
        return out
    }
}
