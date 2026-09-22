package app.krafted.candytriangle.engine

import kotlin.math.sqrt

/**
 * Narrow-phase overlap tests and the §3.3 bounce response, shared by walls, pegs and gems.
 *
 * Stateless and allocation-free: tests write into a caller-owned [Contact], and [resolve] mutates
 * the [Ball] in place. Every function here must be a pure function of its arguments — no clocks, no
 * randomness — because the §11 determinism test replays these calls bit for bit.
 */
object CollisionMath {

    /**
     * How far past exactly-touching [resolve] moves a ball, u.
     *
     * Separating by the bare penetration would leave the ball touching only in exact arithmetic. In
     * float, the new centre is rounded to the nearest representable position, which near the
     * bottom of the board (y up to 1346 u, where adjacent floats are 2^-13, about 1.2e-4 u, apart)
     * can land up to about 6e-5 u short of the target — measured over a sweep of peg contacts,
     * about 46% of bare separations did. The next contact pass would re-detect each such sliver as
     * a fresh overlap and spend a pass separating it again. 1e-3 u clears the worst rounding on
     * the board with an 8x margin, yet is 1/16000 of a ball radius: invisible, and far below the
     * 6.67 u per-step travel that §3.3's anti-tunnelling budget is about. It moves the ball only;
     * velocity is untouched, so it adds no energy.
     */
    const val SEPARATION_SLOP: Float = 1e-3f

    /**
     * Ball-vs-circle overlap, for pegs and gems.
     *
     * Returns `true` and fills [out] when the ball (centre [ballX], [ballY], radius [ballRadius])
     * overlaps the collider (centre [cx], [cy], radius [colliderRadius]); returns `false` and leaves
     * [out] untouched otherwise. The normal points from the collider centre toward the ball centre
     * and the penetration is `ballRadius + colliderRadius - distance`. Exactly touching
     * (penetration 0) is not an overlap.
     *
     * Coincident centres have no geometric normal; the result must still be a valid unit vector, so
     * it is straight up, `(0, -1)` — back toward the launcher, and the same every run.
     *
     * Almost every collider a ball is tested against is nowhere near it, so an axis-aligned
     * bounding-box reject runs first, then a squared-distance reject, and only a real overlap pays
     * for the `sqrt`. Neither reject ever changes the answer. A non-finite position never reports a
     * contact (every comparison is written so NaN reads as "no overlap").
     */
    fun circleContact(
        ballX: Float,
        ballY: Float,
        ballRadius: Float,
        cx: Float,
        cy: Float,
        colliderRadius: Float,
        out: Contact,
    ): Boolean {
        val dx = ballX - cx
        val dy = ballY - cy
        val reach = ballRadius + colliderRadius
        if (!(dx < reach && dx > -reach && dy < reach && dy > -reach)) return false

        val distanceSquared = dx * dx + dy * dy
        if (!(distanceSquared < reach * reach)) return false
        val distance = sqrt(distanceSquared)
        // Rounding can put a squared distance just under reach^2 but its root exactly on reach;
        // this keeps "overlap" meaning a strictly positive penetration.
        if (!(distance < reach)) return false

        if (distance > 0f) {
            out.set(dx / distance, dy / distance, reach - distance)
        } else {
            out.set(0f, -1f, reach)
        }
        return true
    }

    /**
     * Applies one [contact] to [ball]: separates it, and — if the collider is closing on it —
     * bounces it (§3.3) and, for a moving peg, hands it some of the peg's speed (§3.3 moving rows).
     *
     * The collider's velocity is `u = (surfaceVx, 0)`, non-zero only for a §3.3 moving peg; `n` is
     * the contact normal and `e` the collider's [restitution].
     *
     * 1. **Separation, always:** the ball moves out along the contact normal by the full
     *    penetration plus [SEPARATION_SLOP], leaving it just clear of touching — so re-running the
     *    same overlap test against the same collider returns `false`, and the next contact pass
     *    does not re-detect a rounding sliver.
     * 2. **Approach, judged in the collider's frame:** the ball is approaching iff
     *    `(v - u) . n < 0`; if not, the contact was separation only and the result is `0f`. Judging
     *    it relative to the collider is what lets a peg sweeping into a slow or resting ball
     *    register at all.
     * 3. **Bounce, on the ball's own velocity**, with `vn = v . n` and `vT = v - vn n`:
     *
     *    ```
     *    vB = e * (v - 2 vn n) + (1 - e) * tangentialDamping * vT    if vn < 0   // §3.3, verbatim
     *    vB = v                                                      otherwise
     *    ```
     *
     *    Expanded, the normal component becomes `-e * vn` and the tangential component
     *    `(e + (1 - e) * tangentialDamping) * vT`. The `vn < 0` guard is for a peg that caught up
     *    with a ball already leaving along `n`: reflecting that ball would throw it back into the
     *    peg, so it keeps its velocity and only the hand-off below applies.
     * 4. **Moving-row hand-off**, only when `surfaceVx != 0` — §3.3's "50% of the peg's
     *    instantaneous horizontal velocity is transferred to the ball", as a kick in the peg's
     *    direction that can bring the ball up to, but never past, the peg's own horizontal speed:
     *
     *    ```
     *    u.x > 0:  v'.x = max(vB.x, min(vB.x + transfer * u.x, u.x))
     *    u.x < 0:  v'.x = min(vB.x, max(vB.x + transfer * u.x, u.x))
     *              v'.y = vB.y
     *    ```
     *
     *    The cap matters because a ball resting on a moving peg micro-bounces nearly every step:
     *    uncapped, it would gain `transfer * u.x` 240 times a second; capped, it converges to
     *    riding along at the peg's speed — never reversed, never launched past the peg.
     *
     * For a static collider (`u = 0`) this is exactly the §3.3 wall formula: `v - u` is `v`, and it
     * is literally that `v - u` the bounce reflects, so the arithmetic is the same bit for bit. The
     * hand-off is skipped outright rather than multiplied by zero, so [transfer] really is
     * irrelevant then — even a non-finite one — and a `-0.0` component is left as it is. Formulas
     * are evaluated term by term as written, in float, so the code reads straight off the PRD; the
     * cap's `min`/`max` are written as comparisons so that a NaN kick is ignored rather than
     * propagated.
     *
     * @param restitution the collider's `e`, 0..1.
     * @param transfer §3.3's share of a moving collider's horizontal velocity handed to the ball
     *   (`movingPegs.tangentialTransfer`, 0.5). Irrelevant when `surfaceVx == 0`.
     * @return the impact speed in the collider's frame, `-((v - u) . n)`, strictly positive, if
     *   the ball was approaching and steps 3-4 were applied; `0f` if it was only separated
     *   (resting, or already leaving the collider).
     */
    fun resolve(
        ball: Ball,
        contact: Contact,
        restitution: Float,
        tangentialDamping: Float,
        surfaceVx: Float,
        transfer: Float,
    ): Float {
        val nx = contact.nx
        val ny = contact.ny

        // 1. Separation.
        val push = contact.penetration + SEPARATION_SLOP
        ball.x += nx * push
        ball.y += ny * push

        // 2. Approach, in the collider's frame: (v - u) . n < 0, with u = (surfaceVx, 0).
        val relVx = ball.vx - surfaceVx
        val vy = ball.vy
        val closingSpeed = relVx * nx + vy * ny // (v - u) . n
        if (!(closingSpeed < 0f)) return 0f

        // 3. Bounce, on the ball's own velocity. Against a static collider that is v - u itself,
        //    reused as such so the static path is the plain §3.3 wall formula bit for bit.
        val moving = surfaceVx != 0f
        val vx = if (moving) ball.vx else relVx
        val normalSpeed = if (moving) vx * nx + vy * ny else closingSpeed // v . n
        var outX = vx
        var outY = vy
        if (normalSpeed < 0f) {
            val e = restitution
            // v - 2 (v . n) n: v mirrored across the contact tangent.
            val mirroredX = vx - 2f * normalSpeed * nx
            val mirroredY = vy - 2f * normalSpeed * ny
            // vT = v - (v . n) n: the tangential part of v.
            val tangentX = vx - normalSpeed * nx
            val tangentY = vy - normalSpeed * ny
            // vB = e * mirrored + (1 - e) * tangentialDamping * vT
            val tangentShare = (1f - e) * tangentialDamping
            outX = e * mirroredX + tangentShare * tangentX
            outY = e * mirroredY + tangentShare * tangentY
        }

        // 4. Moving-row hand-off: a kick of transfer * u.x toward the peg's speed, capped at it.
        if (moving) {
            val kicked = outX + transfer * surfaceVx
            if (surfaceVx > 0f) {
                // v'.x = max(vB.x, min(vB.x + transfer * u.x, u.x))
                val capped = if (kicked > surfaceVx) surfaceVx else kicked
                if (capped > outX) outX = capped
            } else {
                // v'.x = min(vB.x, max(vB.x + transfer * u.x, u.x))
                val capped = if (kicked < surfaceVx) surfaceVx else kicked
                if (capped < outX) outX = capped
            }
        }

        ball.vx = outX
        ball.vy = outY
        return -closingSpeed
    }
}
