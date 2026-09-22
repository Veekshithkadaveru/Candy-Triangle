package app.krafted.candytriangle.engine

import android.util.Log
import app.krafted.candytriangle.level.BoardConfig
import app.krafted.candytriangle.level.WallLine
import app.krafted.candytriangle.level.WallsConfig
import kotlin.math.sqrt

/**
 * A slanted side wall of the triangle (§3.1: 21.8 degrees from vertical) — a solid line segment
 * from ([x1], [y1]) to ([x2], [y2]) that the ball bounces off (§3.3 circle-segment collision).
 *
 * A wall knows which side of its line is the board interior: [nx], [ny] is the unit normal pointing
 * inward, fixed at construction from any point known to be inside. That orientation is what lets
 * [contact] push a ball back in even if a step ever carried its centre across the line.
 *
 * Immutable and allocation-free to query. Everything [contact] needs besides the ball — the segment
 * vector, its squared length and the inward normal — is computed once here, so a query is a handful
 * of multiply-adds and at most one `sqrt`. Build the two §3.1 walls with [boardWalls].
 *
 * @throws IllegalArgumentException if the segment is degenerate (a non-finite endpoint, or a length
 *   that is zero or overflows) or the interior point is non-finite or lies exactly on the segment's
 *   line — either way "inward" would be undefined. Those are programmer errors: the config path,
 *   [boardWalls], never passes them.
 */
class WallSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    /**
     * Coefficient of restitution for a ball bouncing off this wall (`restitutionWall`), 0..1. Taken
     * as given: sanitising the configured value is [PhysicsParams.from]'s job.
     */
    val restitution: Float,
    /** Any point strictly inside the board; decides which way [nx], [ny] faces. */
    interiorX: Float,
    interiorY: Float,
) {

    /** The segment vector `W = p2 - p1` (§3.3), u. */
    private val wx: Float = x2 - x1
    private val wy: Float = y2 - y1

    /**
     * `|W|^2`, u^2. The same float expression [contact] evaluates for the projection, so a centre
     * exactly on ([x2], [y2]) projects to exactly this value — `t = 1` on the nose, not within a
     * rounding error of it.
     */
    private val lengthSquared: Float = wx * wx + wy * wy

    /**
     * Unit normal of the segment pointing into the board interior.
     *
     * It is the perpendicular `(-Wy, Wx) / |W|`, flipped if that points away from the interior
     * point. Normalised in double: it runs once per wall, and it makes [nx], [ny] the correctly
     * rounded unit vector rather than one carrying float error from the `sqrt`.
     */
    val nx: Float
    val ny: Float

    init {
        require(isUsableSegment(x1, y1, x2, y2)) {
            "Degenerate wall segment ($x1, $y1) -> ($x2, $y2): zero or non-finite length"
        }
        val side = sideOfLine(x1, y1, x2, y2, interiorX, interiorY)
        require(side != 0.0 && side.isFinite()) {
            "Interior point ($interiorX, $interiorY) must lie strictly to one side of the wall " +
                "($x1, $y1) -> ($x2, $y2)"
        }
        val length = sqrt(wx.toDouble() * wx + wy.toDouble() * wy)
        val towardInterior = if (side > 0.0) 1.0 else -1.0
        nx = (-wy * towardInterior / length).toFloat()
        ny = (wx * towardInterior / length).toFloat()
    }

    /**
     * Circle-vs-segment overlap test (§3.3) for a ball of [radius] centred at ([cx], [cy]).
     *
     * Returns `true` and fills [out] when the ball overlaps the wall; returns `false` and leaves
     * [out] untouched otherwise. With `t` the projection of the centre onto the segment,
     * `((c - p1) . (p2 - p1)) / |p2 - p1|^2`:
     *
     * - **`0 <= t <= 1` (alongside the segment):** a side test against the inward normal. With
     *   `s = (c - p1) . n`, the ball overlaps iff `s < radius`; the contact normal is the inward
     *   normal and the penetration is `radius - s`. This deliberately also catches a centre that has
     *   crossed the line (`s <= 0`) and pushes it back inside — the anti-tunnelling backstop.
     * - **Otherwise (past an end):** circle-vs-point against the nearer endpoint. With `d` the
     *   distance to it, the ball overlaps iff `d < radius`; the normal is `(c - endpoint) / d`, or
     *   the inward normal when `d == 0`; the penetration is `radius - d`. This is what makes the
     *   open base's two corners solid without closing the base.
     *
     * The region test is evaluated as `0 <= (c - p1) . W <= |W|^2` — the same comparison as on `t`,
     * without the division or its rounding. Past an end, a squared-distance reject runs before the
     * `sqrt`; it never changes the answer. A non-finite centre never reports a contact (every
     * comparison is written so NaN reads as "no overlap"), so garbage state cannot reach the
     * listener as a bounce.
     */
    fun contact(cx: Float, cy: Float, radius: Float, out: Contact): Boolean {
        val px = cx - x1
        val py = cy - y1
        val projection = px * wx + py * wy

        if (projection >= 0f && projection <= lengthSquared) {
            // Alongside: signed distance from the line, positive on the interior side.
            val s = px * nx + py * ny
            if (!(s < radius)) return false
            out.set(nx, ny, radius - s)
            return true
        }

        // Past an end: circle-vs-point against the nearer endpoint.
        val ex: Float
        val ey: Float
        if (projection < 0f) {
            ex = px
            ey = py
        } else {
            ex = cx - x2
            ey = cy - y2
        }
        val distanceSquared = ex * ex + ey * ey
        if (!(distanceSquared < radius * radius)) return false
        val d = sqrt(distanceSquared)
        if (!(d < radius)) return false
        if (d > 0f) out.set(ex / d, ey / d, radius - d) else out.set(nx, ny, radius)
        return true
    }

    override fun toString(): String =
        "WallSegment(($x1, $y1) -> ($x2, $y2), n=($nx, $ny), e=$restitution)"

    companion object {

        /**
         * The two §3.1 walls from [board], **left then right** — the fixed order the step tests them
         * in. Inward normals are oriented toward the triangle's centroid. A degenerate (zero-length
         * or non-finite) configured wall degrades to the §3.1 default rather than throwing.
         *
         * The centroid is taken over the apex — the midpoint of the two walls' first endpoints,
         * which §3.1 has coincide at (500, 0) — and the two base corners, the walls' second
         * endpoints: (500, 833.3) on the default board. Should the resolved walls still leave that
         * point on one of their lines (collinear walls: no triangle at all), both fall back to the
         * defaults, so this never throws for any [BoardConfig]. Every fallback logs a warning.
         *
         * [restitution] is passed to both walls as given.
         */
        fun boardWalls(board: BoardConfig, restitution: Float): List<WallSegment> {
            val defaults = WallsConfig()
            var left = board.walls.left.orDefault(defaults.left, "left")
            var right = board.walls.right.orDefault(defaults.right, "right")
            if (!hasInteriorOffBothLines(left, right)) {
                Log.w(
                    WALL_LOG_TAG,
                    "Walls $left and $right do not enclose a triangle; using the §3.1 defaults",
                )
                left = defaults.left
                right = defaults.right
            }
            val insideX = centroidX(left, right)
            val insideY = centroidY(left, right)
            return listOf(
                WallSegment(left.x1, left.y1, left.x2, left.y2, restitution, insideX, insideY),
                WallSegment(right.x1, right.y1, right.x2, right.y2, restitution, insideX, insideY),
            )
        }
    }
}

private const val WALL_LOG_TAG = "Physics"

/**
 * Whether ([x1], [y1]) -> ([x2], [y2]) can be a [WallSegment]: finite endpoints and a squared
 * length that is positive and finite in the float arithmetic [WallSegment.contact] uses.
 */
private fun isUsableSegment(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
    if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return false
    val wx = x2 - x1
    val wy = y2 - y1
    val lengthSquared = wx * wx + wy * wy
    return lengthSquared > 0f && lengthSquared.isFinite()
}

/**
 * Which side of the line through ([x1], [y1]) -> ([x2], [y2]) the point ([px], [py]) is on:
 * `(p - p1) . (-Wy, Wx)`. Positive on the side the perpendicular `(-Wy, Wx)` points to, zero on the
 * line, non-finite for non-finite input. Evaluated in double so that neither overflow nor rounding
 * can flip or zero the sign for any geometry a float board can describe.
 */
private fun sideOfLine(x1: Float, y1: Float, x2: Float, y2: Float, px: Float, py: Float): Double {
    val wx = (x2 - x1).toDouble()
    val wy = (y2 - y1).toDouble()
    return -wy * (px.toDouble() - x1) + wx * (py.toDouble() - y1)
}

/** This line if it can be a wall, else [default] (with a warning naming the [side] replaced). */
private fun WallLine.orDefault(default: WallLine, side: String): WallLine {
    if (isUsableSegment(x1, y1, x2, y2)) return this
    Log.w(WALL_LOG_TAG, "Degenerate $side wall $this; using the §3.1 default $default")
    return default
}

/** x of the centroid of the apex and the two base corners; see [WallSegment.boardWalls]. */
private fun centroidX(left: WallLine, right: WallLine): Float =
    (((left.x1.toDouble() + right.x1) / 2.0 + left.x2 + right.x2) / 3.0).toFloat()

/** y of the same centroid. */
private fun centroidY(left: WallLine, right: WallLine): Float =
    (((left.y1.toDouble() + right.y1) / 2.0 + left.y2 + right.y2) / 3.0).toFloat()

/**
 * Whether the centroid of [left] and [right] lies strictly off both lines — exactly the condition
 * the [WallSegment] constructor requires of its interior point, computed with the same helper and
 * the same float centroid, so a `true` here guarantees both constructions succeed.
 */
private fun hasInteriorOffBothLines(left: WallLine, right: WallLine): Boolean {
    val insideX = centroidX(left, right)
    val insideY = centroidY(left, right)
    val leftSide = sideOfLine(left.x1, left.y1, left.x2, left.y2, insideX, insideY)
    val rightSide = sideOfLine(right.x1, right.y1, right.x2, right.y2, insideX, insideY)
    return leftSide != 0.0 && leftSide.isFinite() && rightSide != 0.0 && rightSide.isFinite()
}
