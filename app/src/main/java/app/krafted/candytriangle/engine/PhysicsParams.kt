package app.krafted.candytriangle.engine

import android.util.Log
import app.krafted.candytriangle.level.GameConfig
import kotlin.math.roundToInt

/**
 * Every number the 240 Hz step reads, resolved once from [GameConfig] and sanitised.
 *
 * The engine never reads `GameConfig` directly. Resolving here means the per-step code sees plain
 * fields with no lookups and no validity checks, and it gives one place to enforce the invariants
 * the physics relies on — most importantly §3.3's anti-tunnelling bound,
 * `maxBallSpeed / stepsPerSecond < smallest collider radius`.
 *
 * The constructor defaults are the §3.2 table, so a test can say `PhysicsParams(gravity = 0f)`.
 * They duplicate `GameConfig.DEFAULTS` on purpose, and `PhysicsParamsTest` asserts
 * `PhysicsParams() == PhysicsParams.from(GameConfig.DEFAULTS)` so the two cannot drift apart.
 *
 * Angles are radians measured from **straight down** (+y), positive toward +x; see [launchVx].
 *
 * ## Sanitisation
 *
 * `config.json` is hand-edited and the game ships offline with no recovery path (A2 deviation 7),
 * so [from] repairs one value at a time, logs each repair with `Log.w`, and never throws:
 *
 * - A **magnitude** — a radius, `gravity`, a speed, a stuck-ball threshold, dwell or impulse,
 *   `physicsStepMs`, `aimClampDegrees` — that is non-finite or not strictly positive falls back to
 *   its §3.2 default. Zero is not a tuning choice for any of them: zero gravity never ends a drop,
 *   a zero radius is not a collider.
 * - A **coefficient** — the three restitutions, `tangentialDamping`, `movingPegTransfer` — falls
 *   back to its default only when non-finite; a finite value outside 0..1 is coerced into it (a
 *   restitution of 1.7 becomes 1). Zero is legitimate here: a perfectly dead bounce is a tuning.
 * - [stepsPerSecond] is `round(1000 / physicsStepMs)` (4.1667 ms -> 240), then held to
 *   [MIN_STEPS_PER_SECOND]..[MAX_STEPS_PER_SECOND].
 * - [maxBallSpeed] is capped at [ANTI_TUNNELLING_MARGIN] x smallest solid radius x
 *   [stepsPerSecond]; see [ANTI_TUNNELLING_MARGIN] for why the cap sits just under §3.3's bound.
 * - [launchSpeed] is capped at [maxBallSpeed]: the first step would clamp it anyway, and a launch
 *   the terminal-speed clamp immediately rewrites is a config error, not a tuning.
 * - The aim clamp is capped at [MAX_AIM_CLAMP_DEGREES], so every launch has a downward component.
 *
 * Direct construction is trusted and **not** sanitised: it exists for tests, which sometimes need
 * values `from` would refuse (zero gravity, say).
 */
data class PhysicsParams(
    /** Fixed steps per simulated second; 240 from §3.2's `physicsStepMs` of 4.1667. */
    val stepsPerSecond: Int = 240,
    /** Downward acceleration, u/s^2. */
    val gravity: Float = 1400f,
    /** Launch speed, u/s. [from] never resolves it above [maxBallSpeed]. */
    val launchSpeed: Float = 900f,
    /**
     * Terminal speed, u/s — applied immediately before every position update. [from] guarantees
     * `maxBallSpeed / stepsPerSecond` stays under the smallest solid radius (§3.3).
     */
    val maxBallSpeed: Float = 1600f,
    val ballRadius: Float = 16f,
    val pegRadius: Float = 7f,
    val gemRadius: Float = 30f,
    val restitutionPeg: Float = 0.60f,
    val restitutionGem: Float = 0.65f,
    val restitutionWall: Float = 0.50f,
    /** §3.3's tangential friction factor per contact. */
    val tangentialDamping: Float = 0.98f,
    /** §3.3 stuck rescue: a ball slower than this, u/s... */
    val stuckSpeedThreshold: Float = 30f,
    /** ...for more than this many consecutive steps (1.0 s at 240 Hz)... */
    val stuckDwellSteps: Int = 240,
    /** ...gets an impulse of this magnitude, u/s... */
    val stuckImpulseSpeed: Float = 120f,
    /** ...aimed horizontally toward this axis, u... */
    val stuckAxisX: Float = 500f,
    /** ...and, when true, downward as well (45 degrees below horizontal). */
    val stuckDownward: Boolean = true,
    /** §3.3: share of a moving peg's horizontal velocity handed to the ball on contact. */
    val movingPegTransfer: Float = 0.50f,
    /**
     * Launch aim clamp either side of straight down, radians (§2: 70 degrees). [from] never
     * resolves it past [MAX_AIM_CLAMP_DEGREES].
     */
    val aimClampRadians: Float = Math.toRadians(70.0).toFloat(),
    /**
     * Where a launched ball appears: on the axis at the middle of §3.1's launcher zone. Not at the
     * apex pivot itself — a 16 u ball there would overlap both walls, which only clear it below
     * y = 16 / sin(21.8 deg), about 43 u.
     */
    val spawnX: Float = 500f,
    val spawnY: Float = 75f,
    /**
     * A ball whose centre passes this y has left the board (§2 drop end). It is the bottom of the
     * cup lane plus a ball radius, so B2's Candy Cup sees the ball cross its whole lane first.
     */
    val exitY: Float = 1346f,
) {

    /** The fixed timestep, s: exactly `1 / stepsPerSecond`. */
    val dt: Float = 1f / stepsPerSecond

    /** The furthest a ball can travel in one step, u — what §3.3's anti-tunnelling claim bounds. */
    val maxStepDisplacement: Float = maxBallSpeed / stepsPerSecond

    /**
     * [aimRadians] clamped to plus or minus [aimClampRadians]. A non-finite aim means straight
     * down.
     *
     * Written as comparisons rather than `coerceIn`, which throws when its bounds cross: a launch
     * must never throw, even on a hand-built params object with a nonsensical clamp.
     */
    fun clampAim(aimRadians: Float): Float = when {
        !aimRadians.isFinite() -> 0f
        aimRadians > aimClampRadians -> aimClampRadians
        aimRadians < -aimClampRadians -> -aimClampRadians
        else -> aimRadians
    }

    /**
     * Launch velocity x for [aimRadians] (clamped first): `launchSpeed * sin(aim)`, via StrictMath.
     *
     * Evaluated in double and rounded once. `StrictMath` is fdlibm, bit-identical on the JVM and on
     * ART, so a given aim launches identically on the test host and on a phone; it is also exactly
     * odd, so mirrored aims launch exactly mirrored balls, and aim 0 gives exactly 0.
     */
    fun launchVx(aimRadians: Float): Float =
        (launchSpeed * StrictMath.sin(clampAim(aimRadians).toDouble())).toFloat()

    /**
     * Launch velocity y for [aimRadians] (clamped first): `launchSpeed * cos(aim)` — always > 0.
     *
     * Always positive because [from] caps the clamp at [MAX_AIM_CLAMP_DEGREES]; aim 0 gives exactly
     * [launchSpeed].
     */
    fun launchVy(aimRadians: Float): Float =
        (launchSpeed * StrictMath.cos(clampAim(aimRadians).toDouble())).toFloat()

    companion object {

        /**
         * The slowest step rate [from] accepts. Below it the anti-tunnelling cap would force the
         * terminal speed so low (7 u x 60 Hz = 420 u/s) that the game stops being pachinko.
         */
        const val MIN_STEPS_PER_SECOND: Int = 60

        /** The fastest step rate [from] accepts — beyond it a phone cannot keep up anyway. */
        const val MAX_STEPS_PER_SECOND: Int = 2000

        /**
         * The widest aim clamp [from] accepts, degrees either side of straight down. Anything at or
         * past 90 could launch a ball sideways or upward, and even exactly 90 fails in float:
         * `toRadians(90.0).toFloat()` lands a hair *past* PI / 2, so its cosine — the launch's
         * downward component — comes out negative.
         */
        const val MAX_AIM_CLAMP_DEGREES: Float = 89f

        /**
         * [from] caps [maxBallSpeed] at this fraction of §3.3's bound
         * (`smallest solid radius x stepsPerSecond`), not at the bound itself.
         *
         * The bound is strict, and the step reaches it through two rounded operations — the speed
         * clamp's rescale and the position update — so a speed resolved to within an ulp of it
         * could produce a step a hair *longer* than the radius. One percent is five orders of
         * magnitude more headroom than float rounding needs, leaves §3.2's own 1600 u/s (6.67 u of
         * a 7 u radius, 95%) untouched, and keeps the cap monotonic: a faster configured speed
         * never resolves to a slower one.
         */
        const val ANTI_TUNNELLING_MARGIN: Float = 0.99f

        private const val TAG = "PhysicsParams"

        /** §2's aim clamp, the fallback for an unusable `aimClampDegrees`. */
        private const val DEFAULT_AIM_CLAMP_DEGREES: Float = 70f

        /**
         * Resolves [config] into engine parameters. Never throws: a non-finite, non-positive or
         * out-of-range value falls back to its §3.2 default (or is clamped into range), and a
         * `maxBallSpeed` that would break the anti-tunnelling bound is clamped down to it.
         *
         * See the class docs for the rules. Derived values follow §3.1: the spawn point is the
         * launcher pivot's x at the middle of the launcher zone, and the exit line is the bottom of
         * the cup lane plus a ball radius (never above the open base plus a ball radius).
         */
        fun from(config: GameConfig): PhysicsParams {
            // The §3.2 table, from the constructor defaults: the fallback for every field.
            val spec = PhysicsParams()
            val physics = config.physics
            val stuck = physics.stuckBall
            val board = config.board

            val stepsPerSecond = resolveStepsPerSecond(physics.physicsStepMs, spec.stepsPerSecond)

            val ballRadius = positiveOr("physics.ballRadius", physics.ballRadius, spec.ballRadius)
            val pegRadius = positiveOr("physics.pegRadius", physics.pegRadius, spec.pegRadius)
            val gemRadius = positiveOr("physics.gemRadius", physics.gemRadius, spec.gemRadius)

            val maxBallSpeed = resolveMaxBallSpeed(
                configured = positiveOr(
                    "physics.maxBallSpeed",
                    physics.maxBallSpeed,
                    spec.maxBallSpeed,
                ),
                stepsPerSecond = stepsPerSecond,
                // §3.3: the ball's own radius counts too — it is what keeps a wall un-steppable.
                smallestRadius = minOf(pegRadius, gemRadius, ballRadius),
            )
            val configuredLaunchSpeed =
                positiveOr("physics.launchSpeed", physics.launchSpeed, spec.launchSpeed)
            val launchSpeed = if (configuredLaunchSpeed <= maxBallSpeed) {
                configuredLaunchSpeed
            } else {
                Log.w(TAG, "physics.launchSpeed $configuredLaunchSpeed exceeds maxBallSpeed; capping")
                maxBallSpeed
            }

            val dwellSeconds = positiveOr(
                "physics.stuckBall.dwellSeconds",
                stuck.dwellSeconds,
                spec.stuckDwellSteps.toFloat() / spec.stepsPerSecond,
            )
            // A double product, so an absurd dwell saturates in roundToInt instead of overflowing.
            val stuckDwellSteps =
                (dwellSeconds.toDouble() * stepsPerSecond).roundToInt().coerceAtLeast(1)

            // In double, so two huge but finite bounds cannot overflow into a non-finite midpoint.
            val launcherZoneMid =
                ((board.launcherZone.yTop.toDouble() + board.launcherZone.yBottom) / 2.0).toFloat()

            return PhysicsParams(
                stepsPerSecond = stepsPerSecond,
                gravity = positiveOr("physics.gravity", physics.gravity, spec.gravity),
                launchSpeed = launchSpeed,
                maxBallSpeed = maxBallSpeed,
                ballRadius = ballRadius,
                pegRadius = pegRadius,
                gemRadius = gemRadius,
                restitutionPeg =
                    unitOr("physics.restitutionPeg", physics.restitutionPeg, spec.restitutionPeg),
                restitutionGem =
                    unitOr("physics.restitutionGem", physics.restitutionGem, spec.restitutionGem),
                restitutionWall = unitOr(
                    "physics.restitutionWall",
                    physics.restitutionWall,
                    spec.restitutionWall,
                ),
                tangentialDamping = unitOr(
                    "physics.tangentialDamping",
                    physics.tangentialDamping,
                    spec.tangentialDamping,
                ),
                stuckSpeedThreshold = positiveOr(
                    "physics.stuckBall.speedThreshold",
                    stuck.speedThreshold,
                    spec.stuckSpeedThreshold,
                ),
                stuckDwellSteps = stuckDwellSteps,
                stuckImpulseSpeed = positiveOr(
                    "physics.stuckBall.impulseSpeed",
                    stuck.impulseSpeed,
                    spec.stuckImpulseSpeed,
                ),
                stuckAxisX =
                    finiteOr("physics.stuckBall.towardAxisX", stuck.towardAxisX, spec.stuckAxisX),
                stuckDownward = stuck.downward,
                movingPegTransfer = unitOr(
                    "physics.movingPegs.tangentialTransfer",
                    physics.movingPegs.tangentialTransfer,
                    spec.movingPegTransfer,
                ),
                aimClampRadians = resolveAimClampRadians(board.launcher.aimClampDegrees),
                spawnX = finiteOr("board.launcher.pivot.x", board.launcher.pivot.x, spec.spawnX),
                spawnY = finiteOr("board.launcherZone midpoint", launcherZoneMid, spec.spawnY),
                exitY = resolveExitY(board.cupLane.yBottom, board.baseY, ballRadius, spec),
            )
        }

        /** [from] applied to `GameConfig.DEFAULTS`. */
        val DEFAULT: PhysicsParams by lazy { from(GameConfig.DEFAULTS) }

        private fun resolveStepsPerSecond(physicsStepMs: Float, fallback: Int): Int {
            if (!(physicsStepMs.isFinite() && physicsStepMs > 0f)) {
                Log.w(TAG, "physics.physicsStepMs $physicsStepMs is unusable; using $fallback Hz")
                return fallback
            }
            // 1000 / 4.1667 = 239.998, which is 240 once rounded. The double quotient of a positive
            // finite float is itself finite, and roundToInt saturates rather than overflowing.
            val rounded = (1000.0 / physicsStepMs).roundToInt()
            val clamped = rounded.coerceIn(MIN_STEPS_PER_SECOND, MAX_STEPS_PER_SECOND)
            if (clamped != rounded) {
                Log.w(TAG, "physics.physicsStepMs $physicsStepMs is $rounded Hz; using $clamped Hz")
            }
            return clamped
        }

        /** §3.3 anti-tunnelling: `min(configured, margin x smallest radius x step rate)`. */
        private fun resolveMaxBallSpeed(
            configured: Float,
            stepsPerSecond: Int,
            smallestRadius: Float,
        ): Float {
            val cap = smallestRadius * stepsPerSecond * ANTI_TUNNELLING_MARGIN
            if (configured <= cap) return configured
            Log.w(
                TAG,
                "physics.maxBallSpeed $configured moves ${configured / stepsPerSecond} u a step " +
                    "at $stepsPerSecond Hz, against a smallest solid radius of " +
                    "$smallestRadius u (§3.3 anti-tunnelling); clamping to $cap",
            )
            return cap
        }

        private fun resolveAimClampRadians(aimClampDegrees: Float): Float {
            val degrees = positiveOr(
                "board.launcher.aimClampDegrees",
                aimClampDegrees,
                DEFAULT_AIM_CLAMP_DEGREES,
            )
            val capped = if (degrees <= MAX_AIM_CLAMP_DEGREES) {
                degrees
            } else {
                Log.w(
                    TAG,
                    "board.launcher.aimClampDegrees $degrees would allow a launch with no " +
                        "downward component; capping at $MAX_AIM_CLAMP_DEGREES",
                )
                MAX_AIM_CLAMP_DEGREES
            }
            // Same expression as the constructor default, so 70 resolves to the identical float.
            return Math.toRadians(capped.toDouble()).toFloat()
        }

        private fun resolveExitY(
            cupLaneBottom: Float,
            baseY: Float,
            ballRadius: Float,
            spec: PhysicsParams,
        ): Float {
            val laneBottom =
                finiteOr("board.cupLane.yBottom", cupLaneBottom, spec.exitY - spec.ballRadius)
            val exitY = laneBottom + ballRadius
            // A cup lane configured above the open base would retire balls still inside the
            // triangle; the ball must at least have cleared the base.
            val floor = baseY + ballRadius
            if (baseY.isFinite() && exitY < floor) {
                Log.w(TAG, "board.cupLane.yBottom $cupLaneBottom is above the base; exit at $floor")
                return floor
            }
            return exitY
        }

        private fun positiveOr(name: String, value: Float, fallback: Float): Float {
            if (value.isFinite() && value > 0f) return value
            Log.w(TAG, "$name $value is not a positive number; using $fallback")
            return fallback
        }

        private fun unitOr(name: String, value: Float, fallback: Float): Float {
            if (!value.isFinite()) {
                Log.w(TAG, "$name $value is not finite; using $fallback")
                return fallback
            }
            val coerced = value.coerceIn(0f, 1f)
            if (coerced != value) {
                Log.w(TAG, "$name $value is outside 0..1; using $coerced")
            }
            return coerced
        }

        private fun finiteOr(name: String, value: Float, fallback: Float): Float {
            if (value.isFinite()) return value
            Log.w(TAG, "$name $value is not finite; using $fallback")
            return fallback
        }
    }
}
