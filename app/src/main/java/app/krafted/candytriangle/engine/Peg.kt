package app.krafted.candytriangle.engine

import kotlin.math.PI

/**
 * What a solid circle collider stands for, so a listener can tell a peg tick from a gem smash
 * without a second lookup. Candies are *not* here: they are non-solid sensors (§4.1) and never
 * enter the physics world.
 */
enum class ColliderKind {
    /** A lattice peg (§3.1), radius [PhysicsParams.pegRadius], restitution `restitutionPeg`. */
    PEG,

    /** The solid body of a gem (§4.2), radius [PhysicsParams.gemRadius], `restitutionGem`. */
    GEM,
}

/**
 * Horizontal oscillation of a moving peg row (§3.3, World 3+):
 *
 * ```
 * x(t)  = baseX + amplitude * sin(2 PI t / periodSeconds)
 * vx(t) = amplitude * (2 PI / periodSeconds) * cos(2 PI t / periodSeconds)
 * ```
 *
 * `t` is simulation time derived from the integer step counter, never wall-clock time, so a moving
 * row is exactly as reproducible as a static one. [periodSeconds] must be > 0 — `LevelDef` already
 * guarantees that for authored rows. A motion that breaks that (or has a non-finite [amplitude])
 * degrades to a peg held still at [baseX]; see [Peg.updateMotion].
 */
data class PegMotion(
    /** Rest position, u — the centre of the oscillation. */
    val baseX: Float,
    /** `A`, u. */
    val amplitude: Float,
    /** `T`, seconds. */
    val periodSeconds: Float,
)

/**
 * A solid circular collider (§8 `Peg.kt`): a lattice peg, or the physical body of a gem.
 *
 * Gems share this type because, to the physics, a gem *is* a bigger, bouncier peg (§3.2 gives it
 * only a radius and a restitution). Gameplay meaning — multipliers, effects — lives in B3's
 * `board/Gem.kt`, which will own a `Peg` of kind [ColliderKind.GEM] and flip [active] when smashed.
 *
 * [x] and [vx] are mutable because a peg in a moving row (§3.3) is re-positioned every step by
 * [updateMotion]. Everything else is fixed for the life of the board.
 */
class Peg(
    /** Stable identifier, unique within one [PhysicsWorld]; assigned by whoever builds the board. */
    val id: Int,
    x: Float,
    /** Centre y, u. Pegs only ever move horizontally. */
    val y: Float,
    val radius: Float,
    /** Coefficient of restitution for a ball bouncing off this collider, 0..1. */
    val restitution: Float,
    val kind: ColliderKind = ColliderKind.PEG,
    /** Non-null for a peg in a moving row. `x` at construction should equal [PegMotion.baseX]. */
    val motion: PegMotion? = null,
) {

    /** Centre x, u. Constant for a static peg; re-evaluated every step for a moving one. */
    var x: Float = x

    /**
     * Instantaneous horizontal velocity, u/s — zero for a static peg. The contact response uses it
     * both to judge approach in the peg's frame and for §3.3's 50% velocity transfer.
     */
    var vx: Float = 0f

    /** Inactive colliders are skipped by the step entirely (B3 deactivates a smashed gem). */
    var active: Boolean = true

    /**
     * Sets [x] and [vx] to their values at simulation time [timeSeconds] per [PegMotion].
     * A no-op for a static peg. Called by [PhysicsWorld.step] before any contact test, and once at
     * [PhysicsWorld] construction with `t = 0`.
     *
     * A pure function of [timeSeconds] and [motion], so it is idempotent and replayable: calling it
     * twice with the same time, or on two pegs with equal motions, gives bit-identical results.
     *
     * - **Precision:** evaluated in double and rounded to float once. The phase is reduced to a
     *   fraction of a turn, `(t mod T) / T`, *before* any trig, and `t mod T` is exact in IEEE-754,
     *   so a row a week into a session is as precise as one a second in. (Scaling first,
     *   `2 PI t / T`, would lose low bits of the phase as `t` grows.) The trig then works on that
     *   fraction directly, so no rounded multiple of `2 PI` enters the phase at all.
     * - **Determinism and allocation:** trig goes through [DeterministicTrig], which is bit-identical
     *   on the JVM and on ART *and* allocation-free. Not `StrictMath`: it is just as reproducible,
     *   but on JDK 21+ it allocates a `double[2]` per call, and this runs for every moving peg on
     *   every step, which must not allocate. Not `Math`: it does not allocate, but it is not
     *   reproducible across platforms.
     * - **Degrades, never throws:** a period that is not > 0 (zero, negative, NaN), a non-finite
     *   amplitude or a non-finite time puts the peg at [PegMotion.baseX] with `vx = 0` — a static
     *   peg rather than a NaN one, since a NaN collider would turn every ball that met it NaN.
     */
    fun updateMotion(timeSeconds: Double) {
        val m = motion ?: return
        val period = m.periodSeconds.toDouble()
        val amplitude = m.amplitude.toDouble()
        if (!(period > 0.0) || !amplitude.isFinite() || !timeSeconds.isFinite()) {
            x = m.baseX
            vx = 0f
            return
        }
        // An infinite period needs no special case: t mod inf = t and t / inf = 0, so the peg sits
        // at baseX with vx = A * 0 * cos(0) = 0 — the natural limit of an ever-slower oscillation.
        val turns = (timeSeconds % period) / period
        x = (m.baseX + amplitude * DeterministicTrig.sinTurns(turns)).toFloat()
        vx = (amplitude * (TWO_PI / period) * DeterministicTrig.cosTurns(turns)).toFloat()
    }

    override fun toString(): String = "Peg#$id($kind, x=$x, y=$y, r=$radius, active=$active)"
}

/** One full oscillation, radians. Scaling by 2 is exact, so this is exactly twice [PI]. */
private const val TWO_PI: Double = 2.0 * PI
