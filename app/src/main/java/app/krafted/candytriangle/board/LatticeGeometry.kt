package app.krafted.candytriangle.board

import app.krafted.candytriangle.level.BoardConfig
import app.krafted.candytriangle.level.BoardPoint
import app.krafted.candytriangle.level.RowCol
import app.krafted.candytriangle.level.WallLine
import kotlin.math.sqrt

/**
 * The single source of truth for where lattice nodes and lattice gaps sit on the board (§3.1).
 *
 * ### Nodes
 * Node `(r, c)`, `0 <= c <= r`, sits at `x = axis + (c - r/2)·d`, `y = latticeTop + r·h` with
 * row height `h = d·rowSpacingFactor` (`√3/2`). Row 0 is the single node on the axis.
 *
 * ### Gaps (candy slots, §4.1 "lattice gap centres")
 * Gap row `r` lies between node rows `r` and `r + 1` and has columns `0..2r`:
 * - even column `2k` is the **upward** triangle `(r,k) (r+1,k) (r+1,k+1)`; its centroid is directly
 *   below node `(r, k)` at `y + ⅔h`;
 * - odd column `2k + 1` is the **downward** triangle `(r,k) (r,k+1) (r+1,k+1)`; its centroid is at
 *   `x + d/2, y + ⅓h`.
 *
 * `levels.json`'s `candies.fixed[].row/col` use this gap addressing, not node addressing.
 */
object LatticeGeometry {

    fun rowHeight(spacing: Float, board: BoardConfig): Float = spacing * board.lattice.rowSpacingFactor

    /** Highest node row whose y is still inside the lattice band. */
    fun lastNodeRow(spacing: Float, board: BoardConfig): Int {
        val h = rowHeight(spacing, board)
        return ((board.latticeBand.yBottom - board.latticeBand.yTop) / h).toInt()
    }

    fun nodeX(rc: RowCol, spacing: Float, board: BoardConfig): Float =
        board.centerAxisX + (rc.col - rc.row / 2.0f) * spacing

    fun nodeY(rc: RowCol, spacing: Float, board: BoardConfig): Float =
        board.latticeBand.yTop + rc.row * rowHeight(spacing, board)

    /** Number of gap rows: one fewer than node rows. */
    fun gapRowCount(spacing: Float, board: BoardConfig): Int = lastNodeRow(spacing, board)

    fun isValidGap(gap: RowCol, spacing: Float, board: BoardConfig): Boolean =
        gap.row in 0 until gapRowCount(spacing, board) && gap.col in 0..(2 * gap.row)

    /** Centroid of gap [gap]. Does not check range; see [isValidGap]. */
    fun gapCenter(gap: RowCol, spacing: Float, board: BoardConfig): BoardPoint {
        val k = gap.col / 2
        val anchor = RowCol(gap.row, k)
        val x = nodeX(anchor, spacing, board)
        val y = nodeY(anchor, spacing, board)
        val h = rowHeight(spacing, board)
        return if (gap.col % 2 == 0) {
            BoardPoint(x, y + h * (2f / 3f))
        } else {
            BoardPoint(x + spacing / 2f, y + h * (1f / 3f))
        }
    }

    /**
     * Smallest perpendicular distance from (x, y) to the two slanted walls, positive inside the
     * triangle, negative outside.
     */
    fun wallClearance(x: Float, y: Float, board: BoardConfig): Float =
        minOf(signedDistanceInside(board.walls.left, x, y, board), signedDistanceInside(board.walls.right, x, y, board))

    private fun signedDistanceInside(wall: WallLine, x: Float, y: Float, board: BoardConfig): Float {
        val dx = wall.x2 - wall.x1
        val dy = wall.y2 - wall.y1
        val len = sqrt(dx * dx + dy * dy)
        var nx = -dy / len
        var ny = dx / len
        // Orient the normal toward the board axis so "inside" is positive for either wall.
        val midX = (wall.x1 + wall.x2) / 2f
        val midY = (wall.y1 + wall.y2) / 2f
        if ((board.centerAxisX - midX) * nx + (board.baseY * 0.5f - midY) * ny < 0f) {
            nx = -nx
            ny = -ny
        }
        return (x - wall.x1) * nx + (y - wall.y1) * ny
    }
}
