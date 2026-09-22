package app.krafted.candytriangle.engine

/**
 * Scratch output of a narrow-phase overlap test: which way to push the ball, and how far.
 *
 * A test such as [WallSegment.contact] or [CollisionMath.circleContact] fills a caller-supplied
 * instance instead of returning a new object, so the 240 Hz step can reuse one [Contact] for every
 * test it runs and never allocate. The flip side: the values are only meaningful until the next
 * test writes into the same instance — read them immediately. A test that finds no overlap leaves
 * the instance untouched, so stale values from an earlier hit survive a miss.
 */
class Contact {

    /**
     * Unit normal pointing from the collider toward the ball centre: the push-out direction.
     *
     * One case reads differently: a ball whose centre has crossed a [WallSegment]'s line gets the
     * wall's inward normal, which then points from the ball back into the board — still the
     * push-out direction, and the one that undoes the crossing.
     */
    var nx: Float = 0f
    var ny: Float = 0f

    /** Overlap depth along the normal, u. Strictly positive whenever a test returned `true`. */
    var penetration: Float = 0f

    fun set(nx: Float, ny: Float, penetration: Float) {
        this.nx = nx
        this.ny = ny
        this.penetration = penetration
    }

    override fun toString(): String = "Contact(n=($nx, $ny), penetration=$penetration)"
}
